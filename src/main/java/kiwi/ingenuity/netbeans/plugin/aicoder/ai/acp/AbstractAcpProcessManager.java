package kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

/**
 * Shared turn ownership for ACP-backed process managers.
 *
 * <p>
 * The handshake token prevents a late asynchronous handshake from delivering a prompt after its turn has
 * stopped. The active-turn token similarly lets exactly one terminal ACP response own completion. Backends
 * keep their transport, persistence and event-specific behavior.
 */
public abstract class AbstractAcpProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(AbstractAcpProcessManager.class.getName());

    /**
     * Backstop wait for a HELD Mail interrupt (F5): if an in-flight tool call never reports a terminal
     * status, the safety valve delivers {@code session/cancel} anyway after this long.
     */
    public static final long MAIL_INTERRUPT_HOLD_MILLIS = 180_000L;

    /**
     * How long a lost connection waits for its agent to exit before treating the agent as hung — shared by
     * every ACP backend's handshake-publish decision ({@link #publishConnectionOrReportExit}) and its
     * independent disconnect-handling path.
     */
    protected static final long DISCONNECT_EXIT_GRACE_MILLIS = 2_000L;

    /**
     * The turn a handshake in flight was started for: set by {@code sendPrompt} when it hands the prompt to
     * the handshake thread, cleared by whatever ends that turn first — Stop, {@code
     * stop()}, or an exit that reports EXITED. Stop and process exit MUST clear it: the handshake only sends
     * the prompt, reports FAILED, or clears {@code processing} while its turn is still this one, so a
     * handshake that outlived its turn can neither start a turn the user stopped, add a second closing
     * status, nor clear the {@code processing} of a newer turn. Compared by identity, only through {@link #beginHandshakeTurn}/{@link #claimHandshakeTurn}/
     * {@link #clearHandshakeTurn} — private so no subclass can compare it directly. Guarded by {@code this}.
     */
    private Object handshakeTurn;

    private Object activeTurn;

    protected AbstractAcpProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    protected final synchronized void beginHandshakeTurn(Object turn) {
        handshakeTurn = turn;
    }

    protected final synchronized boolean claimHandshakeTurn(Object turn) {
        if (handshakeTurn != turn) {
            return false;
        }
        handshakeTurn = null;
        return true;
    }

    protected final synchronized void clearHandshakeTurn() {
        handshakeTurn = null;
    }

    /**
     * Gives an outgoing ACP prompt a unique completion owner before it is sent.
     */
    protected final synchronized void beginActiveTurn(Object turn) {
        activeTurn = turn;
    }

    /**
     * Claims a prompt result once. Stale results from a stopped or superseded turn return false and must not
     * emit another closing event.
     */
    protected final synchronized boolean claimTurn(Object turn) {
        if (activeTurn != turn) {
            return false;
        }
        activeTurn = null;
        return true;
    }

    /**
     * Discards the owner of an in-flight prompt when another path already closes its turn.
     */
    protected final synchronized void clearActiveTurn() {
        activeTurn = null;
    }

    // ---- F5: Mail interrupt must never abort an MCP tool call this plugin is itself servicing ----
    // ACP backends treat session/cancel as "the user doesn't want to proceed" and cut whatever the agent is
    // waiting on, including a tool call this plugin is itself servicing over the MCP HTTP endpoint. Cutting
    // that call loses work for a message that is already queued in the inbox anyway, so a Mail interrupt is
    // HELD while any tool call is in flight and flushed the moment the last one reports a terminal status
    // (trackToolCallLifecycle), with the safety valve as the backstop if no terminal status ever arrives.
    // Shared because every ACP backend has the same transport reality: no separate mid-turn steer channel,
    // Mail and Cancel both resolve to the same session/cancel notification.
    /**
     * Count of ACP tool calls in flight (a {@code tool_call}/{@code tool_call_update} with status pending or
     * in_progress) not yet released by a completed/failed status. Guarded by {@code this}.
     */
    private int inFlightToolCalls = 0;

    /**
     * toolCallIds currently holding {@link #inFlightToolCalls} open. Keys the count by call id so repeated
     * in_progress (or re-sent pending) updates for the same call never double-count — the id is removed
     * exactly once, on a completed/failed status. Guarded by {@code this}.
     */
    private final Set<String> inFlightToolCallIds = new HashSet<>();

    /**
     * True when a Mail interrupt was requested while {@link #inFlightToolCalls} was &gt; 0 and is waiting to
     * be sent — either by {@link #trackToolCallLifecycle} as soon as the count returns to zero, or by
     * {@link #startMailInterruptSafetyValve} if it never does. Guarded by {@code this}.
     */
    private boolean pendingMailInterrupt = false;

    /**
     * Test seam for {@link #startMailInterruptSafetyValve}'s wait. Public: subclasses' own tests live in a
     * different package and are not themselves subclasses of this class, so a {@code protected} field would
     * not be reachable from them even through a subclass-typed reference — see the JLS protected-access rule.
     * Production default is {@link #MAIL_INTERRUPT_HOLD_MILLIS}.
     */
    public int mailInterruptSafetyValveMillis = (int) MAIL_INTERRUPT_HOLD_MILLIS;

    /**
     * Test seam: how many watchdogs {@link #startMailInterruptSafetyValve} has started. A second Mail
     * interrupt arriving while one is already held must be a no-op — one pending flag and one watchdog cover
     * any number of queued messages — so asserts pin this at 1 across the second-interrupt case. Guarded by
     * {@code this}. Public for the same cross-package test-visibility reason as
     * {@link #mailInterruptSafetyValveMillis}.
     */
    public int mailInterruptSafetyValveStarts = 0;

    public final synchronized int getInFlightToolCalls() {
        return inFlightToolCalls;
    }

    public final synchronized boolean isMailInterruptPending() {
        return pendingMailInterrupt;
    }

    /**
     * Clears the in-flight-tool-call count and any held Mail interrupt — called at the start of a fresh turn
     * and at every point a turn ends, so stale state from a teardown path can never survive into the next
     * turn or be reported for a turn it does not belong to.
     */
    protected final synchronized void resetMailInterruptHold() {
        inFlightToolCalls = 0;
        inFlightToolCallIds.clear();
        pendingMailInterrupt = false;
    }

    /**
     * The connection to send {@code session/cancel} on, or null if none is live. Read by the Mail-hold
     * machinery under {@code this}'s monitor; implementations should return the field directly rather than
     * compute it.
     */
    protected abstract AcpConnection currentAcpConnection();

    /**
     * The ACP session id to send {@code session/cancel} for, or null if none is live.
     */
    protected abstract String currentAcpSessionId();

    /**
     * Cancels any pending permission dialog on the currently active handler, or does nothing if there is
     * none. Called before a cancel notification is sent, so a dialog left open by the cancelled turn is never
     * left waiting for a decision that can no longer matter.
     */
    protected abstract void cancelPendingPermissionsOnActiveHandler();

    /**
     * Short backend name for the debug-JSON log lines this class writes — e.g. {@code "OpenCode"},
     * {@code "Grok"}. Purely cosmetic: never parsed, never sent on the wire.
     */
    protected abstract String backendDisplayNameForLogging();

    /**
     * Sends {@code session/cancel} as a fire-and-forget notification. The one shared mechanism for every
     * cancel — mid-turn interrupts, Mail, and teardown alike. Harmless when no turn is in flight: a
     * notification expects no response, so an agent with nothing to cancel simply ignores it.
     */
    protected final void sendCancelNotification(AcpConnection conn, String sid) {
        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), sid);
        conn.sendNotification(AcpMethodEnum.SESSION_CANCEL, params);
    }

    /**
     * Delivers an inbox notification by cancelling the running turn, so the agent reads its mail on the next
     * turn, through {@link #sendCancelNotification}, with the F5 hold described above. ACP has no separate
     * mail-injection method; {@code session/cancel} ends the turn and cuts any in-flight tool call with it.
     * The queued inbox message is delivered on the next turn through the normal inbox flush.
     *
     * <p>
     * Every failure path is a silent no-op by design: the message is already queued in the inbox and will be
     * delivered by the normal inbox flush, so a missing connection costs promptness, never the message.
     */
    protected final void interruptMail() {
        AcpConnection conn;
        String sid;
        boolean hold;
        boolean alreadyPending;
        int inFlightCount;
        synchronized (this) {
            if (!processing) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "{0} interrupt: Mail IGNORED, no turn in flight", backendDisplayNameForLogging());
                }
                return;
            }
            conn = currentAcpConnection();
            sid = currentAcpSessionId();
            hold = inFlightToolCalls > 0;
            alreadyPending = pendingMailInterrupt;
            inFlightCount = inFlightToolCalls;
            if (hold) {
                pendingMailInterrupt = true;
            }
        }
        if (hold) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "{0} interrupt: Mail HELD — tool call in flight (inFlight={1})",
                        new Object[]{backendDisplayNameForLogging(), inFlightCount});
            }
            if (!alreadyPending) {
                startMailInterruptSafetyValve(conn);
            }
            return;
        }
        cancelPendingPermissionsOnActiveHandler();
        if (conn != null && sid != null) {
            sendCancelNotification(conn, sid);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "{0} interrupt: Mail cancel sent (session={1})", new Object[]{backendDisplayNameForLogging(), sid});
            }
        }
    }

    /**
     * Updates {@link #inFlightToolCalls} from one tool-call lifecycle signal ({@code (toolCallId, status)},
     * forwarded by the client handler for every {@code tool_call}/{@code tool_call_update}) and flushes a
     * HELD Mail interrupt ({@link #pendingMailInterrupt}) as soon as the count returns to zero — before
     * whatever update comes next, which is what keeps the interrupt from landing mid-tool-call.
     *
     * <p>
     * Counted per toolCallId ({@link #inFlightToolCallIds}) so repeated in_progress updates for the same call
     * never double-count; released exactly once by a completed/failed status. Unknown statuses (anything
     * outside the documented pending/in_progress/completed/failed vocabulary) are ignored — the count stays
     * where it was and the safety valve is the backstop, rather than guessing whether the update ends the
     * call.
     */
    public final void trackToolCallLifecycle(String toolCallId, String status) {
        if (toolCallId == null || toolCallId.isBlank() || status == null || status.isBlank()) {
            return;
        }
        AcpToolCallStatusEnum s = AcpToolCallStatusEnum.fromWire(status);
        if (s == null) {
            return;
        }
        boolean flush;
        AcpConnection conn;
        String sid;
        synchronized (this) {
            if (s.isTerminal()) {
                if (inFlightToolCallIds.remove(toolCallId)) {
                    inFlightToolCalls = Math.max(0, inFlightToolCalls - 1);
                }
            }
            else if (s.isInFlight()) {
                if (inFlightToolCallIds.add(toolCallId)) {
                    inFlightToolCalls++;
                }
            }
            flush = pendingMailInterrupt && inFlightToolCalls == 0;
            if (flush) {
                pendingMailInterrupt = false;
            }
            conn = currentAcpConnection();
            sid = currentAcpSessionId();
        }
        if (flush) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "{0} interrupt: Mail flushed — in-flight tool call completed (session={1})",
                        new Object[]{backendDisplayNameForLogging(), sid});
            }
            cancelPendingPermissionsOnActiveHandler();
            if (conn != null && sid != null) {
                sendCancelNotification(conn, sid);
            }
        }
    }

    /**
     * Backstop for a HELD Mail interrupt (F5): if {@link #inFlightToolCalls} never returns to zero — a lost
     * update, an unknown status, or the agent itself hanging — {@link #trackToolCallLifecycle} would
     * otherwise never flush it and the interrupt would wait forever. Delivers the cancel anyway after
     * {@link #mailInterruptSafetyValveMillis}. Guards on {@code currentAcpConnection() == connAtHold} (the
     * connection captured when the hold began) so a watchdog from an earlier, already-resolved hold can never
     * fire against a later, unrelated session.
     */
    private void startMailInterruptSafetyValve(AcpConnection connAtHold) {
        synchronized (this) {
            mailInterruptSafetyValveStarts++;
        }
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(mailInterruptSafetyValveMillis);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean flush;
            AcpConnection conn;
            String sid;
            synchronized (this) {
                flush = pendingMailInterrupt && currentAcpConnection() == connAtHold;
                if (flush) {
                    pendingMailInterrupt = false;
                    inFlightToolCalls = 0;
                    inFlightToolCallIds.clear();
                    conn = currentAcpConnection();
                    sid = currentAcpSessionId();
                }
                else {
                    return;
                }
            }
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO,
                        "{0} interrupt: Mail safety valve fired after {1}ms — tool call still in flight, "
                        + "delivering anyway (session={2})",
                        new Object[]{backendDisplayNameForLogging(), mailInterruptSafetyValveMillis, sid});
            }
            cancelPendingPermissionsOnActiveHandler();
            if (conn != null && sid != null) {
                sendCancelNotification(conn, sid);
            }
        }, "acp-mail-interrupt-safety-valve");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Builds the protocol-v1 initialize request shared by ACP agents.
     */
    protected static JsonObject buildInitializeParams(String clientVersion) {
        JsonObject fs = new JsonObject();
        fs.addProperty(AcpJsonKeyEnum.READ_TEXT_FILE.key(), true);
        fs.addProperty(AcpJsonKeyEnum.WRITE_TEXT_FILE.key(), true);
        JsonObject capabilities = new JsonObject();
        capabilities.add(AcpJsonKeyEnum.FS.key(), fs);
        capabilities.addProperty(AcpJsonKeyEnum.TERMINAL.key(), false);
        JsonObject info = new JsonObject();
        info.addProperty(AcpJsonKeyEnum.NAME.key(), StringConst.PLUGIN_ID);
        info.addProperty(AcpJsonKeyEnum.VERSION.key(), clientVersion);
        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.PROTOCOL_VERSION.key(), 1);
        params.add(AcpJsonKeyEnum.CLIENT_CAPABILITIES.key(), capabilities);
        params.add(AcpJsonKeyEnum.CLIENT_INFO.key(), info);
        return params;
    }

    /**
     * Builds the common session/new or session/load MCP-server envelope.
     */
    protected static JsonObject buildSessionParams(String cwd, String mcpEndpointUrl, String sessionId) {
        JsonObject params = new JsonObject();
        if (sessionId != null) {
            params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), sessionId);
        }
        params.addProperty(AcpJsonKeyEnum.CWD.key(), cwd);
        JsonArray servers = new JsonArray();
        if (mcpEndpointUrl != null) {
            JsonObject entry = new JsonObject();
            entry.addProperty(AcpJsonKeyEnum.TYPE.key(), "http");
            entry.addProperty(AcpJsonKeyEnum.NAME.key(), StringConst.PLUGIN_ID);
            entry.addProperty(AcpJsonKeyEnum.URL.key(), mcpEndpointUrl);
            entry.add(AcpJsonKeyEnum.HEADERS.key(), new JsonArray());
            servers.add(entry);
        }
        params.add(AcpJsonKeyEnum.MCP_SERVERS.key(), servers);
        return params;
    }

    protected static String resolveSessionId(boolean resumed, String resumeId, JsonObject sessionResult) {
        if (resumed) {
            return resumeId;
        }
        return sessionResult != null && sessionResult.has(AcpJsonKeyEnum.SESSION_ID.key())
               ? sessionResult.get(AcpJsonKeyEnum.SESSION_ID.key()).getAsString() : null;
    }

    /**
     * Starts {@code pb} on a dedicated, long-lived daemon thread that then blocks in
     * {@link Process#waitFor()} for the process's ENTIRE life, rather than returning once {@code start()}
     * does — confirmed root cause of a live Grok failure (exit 143, SIGTERM, right after the first prompt, no
     * stderr): {@code grok agent stdio} arms {@code prctl(PR_SET_PDEATHSIG, SIGTERM)} at startup, a Linux
     * kernel feature that fires when the THREAD that forked the child exits, NOT when the process that
     * spawned it exits. {@link ProcessBuilder#start()} forks on the calling thread
     * (posix_spawn/jspawnhelper), so a process started from a short-lived worker thread — this plugin's
     * handshake thread, which used to return moments after sending the first prompt — got SIGTERM'd by the
     * kernel the instant that thread finished, independent of anything the JVM process itself was doing.
     * Keeping the forking thread alive for as long as the child process avoids this for any ACP agent that
     * arms PDEATHSIG, not just Grok, which is why this lives here rather than in one subclass.
     *
     * <p>
     * The CALLING thread (the handshake thread) is unaffected: it still gets the {@link Process} back as soon
     * as {@code pb.start()} itself returns, and continues the handshake on itself exactly as before — only
     * the {@code start()} call's forking thread changes, not which thread does the handshake.
     *
     * @throws IOException if {@code pb.start()} itself throws
     */
    protected static Process startProcessOnOwnerThread(ProcessBuilder pb) throws IOException {
        return startProcessOnOwnerThread(pb::start, ownerThread -> {
        });
    }

    /**
     * Test seam: takes the starter as a {@link Callable} rather than a {@link ProcessBuilder} directly —
     * {@code ProcessBuilder} is {@code final} and cannot be subclassed to inject a failing or slow {@code
     * start()}, so a test passes its own {@link Callable} here instead (production always passes {@code
     * pb::start}). {@code ownerThreadObserver} receives the owner thread before it starts, so a test can
     * {@code join()} it after the process exits without the production API exposing that thread at all.
     */
    static Process startProcessOnOwnerThread(Callable<Process> starter, Consumer<Thread> ownerThreadObserver) throws IOException {
        CompletableFuture<Process> started = new CompletableFuture<>();
        Thread owner = new Thread(() -> {
            Process process;
            try {
                process = starter.call();
            }
            catch (Throwable t) {
                // Not just IOException: a RuntimeException (NPE, SecurityException, ...) from start() must
                // still complete `started`, or the caller's get() below blocks forever (review finding).
                started.completeExceptionally(t);
                return;
            }
            started.complete(process);
            try {
                process.waitFor();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "acp-process-owner");
        owner.setDaemon(true);
        ownerThreadObserver.accept(owner);
        owner.start();
        try {
            return started.get(processStartTimeoutSeconds(), TimeUnit.SECONDS);
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("process start failed on its owner thread", cause);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyIfStartedAfterAllOrNow(started);
            throw new IOException("interrupted while starting process", e);
        }
        catch (TimeoutException e) {
            destroyIfStartedAfterAllOrNow(started);
            throw new IOException("timed out waiting for the process to start", e);
        }
    }

    /**
     * How long the caller waits for {@code pb.start()} to complete on its owner thread before giving up.
     * {@code start()} itself is normally near-instant; this only bounds the pathological case (review
     * finding: an unbounded {@code get()} let a stuck {@code start()} hang the handshake forever).
     */
    private static final long PROCESS_START_TIMEOUT_SECONDS = 30L;

    /**
     * Test seam: overrides {@link #PROCESS_START_TIMEOUT_SECONDS} so a test proving the timeout path does not
     * have to wait out the real 30 s. Null in production.
     */
    static volatile Long processStartTimeoutSecondsForTests = null;

    private static long processStartTimeoutSeconds() {
        Long override = processStartTimeoutSecondsForTests;
        return override != null ? override : PROCESS_START_TIMEOUT_SECONDS;
    }

    /**
     * Called only once the caller has already given up waiting (interrupted, or timed out) on {@code
     * started}. If {@code pb.start()} nonetheless succeeds — whether that has already happened, or happens
     * moments later on the owner thread — the live process would otherwise be orphaned: nothing holds a
     * reference to it, and the owner thread would sit in {@code waitFor()} on it forever. Destroying it here
     * also unblocks that {@code waitFor()}, so the owner thread itself exits cleanly rather than leaking.
     * Runs inline, synchronously, if {@code started} is already complete; otherwise runs later on whichever
     * thread completes it (the owner thread's own {@code started.complete(process)} call).
     */
    private static void destroyIfStartedAfterAllOrNow(CompletableFuture<Process> started) {
        started.whenComplete((process, ex) -> {
            if (process != null) {
                process.destroyForcibly();
            }
        });
    }

    /**
     * Runs on the handshake thread after the session id is known and before the connection is published.
     * No-op in production; the window it marks is the one the agent can die, be stopped, or be superseded in,
     * so a test can hold the handshake here and let any of those land in it deterministically (see
     * {@code OpenCodeHandshakeRaceTest}).
     */
    protected void beforeHandshakePublish(AcpConnection conn) {
    }

    /**
     * Decides whether the handshake may publish its connection or must instead report the agent as dead — the
     * decision and its detection (an ended stream, a grace wait for an exit that is probably only moments
     * away, then an is-alive check) do not depend on any backend-specific state, so every ACP backend shares
     * one implementation rather than each carrying an identical copy.
     *
     * @param publishUnderLock runs inside the SAME {@code synchronized (this)} the decision itself is made
     *                         under, only when the connection may actually be published — sets the backend's
     *                         own session/connection fields ({@code this.connection}, {@code acpSessionId},
     *                         any persisted settings, etc.)
     * @param reportCrashExit  the backend's own {@code handleProcessExit(process)} — called once, outside the
     *                         lock, only when the agent crashed and its {@code onExit} callback has not yet
     *                         run for it, so the crash is reported exactly once, with its exit code, as
     *                         EXITED, never as a start-up FAILED racing the same EXITED
     *
     * @throws IOException if the handshake must fail: {@code stop()} ran, a later start already claimed
     *                     {@code currentProcess}, the agent hung (output ended, still alive), or the agent
     *                     crashed (in which case {@code reportCrashExit} has already run before this throws)
     */
    protected final void publishConnectionOrReportExit(AcpConnection conn, Process process,
                                                       Runnable publishUnderLock, Runnable reportCrashExit) throws IOException {
        beforeHandshakePublish(conn);
        if (conn.isStreamEnded()) {
            // The agent's output has ended: either it crashed (it closes its output as it dies, so the exit
            // is moments away) or it is hung. Give a crash the chance to show as one before deciding, outside
            // the lock, which reportCrashExit needs.
            try {
                process.waitFor(DISCONNECT_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        boolean exitedUnreported = false;
        synchronized (this) {
            if (!running) {
                conn.close();
                process.destroyForcibly();
                if (currentProcess == process) {
                    currentProcess = null;
                }
                throw new IOException("stop() called during handshake");
            }
            if (currentProcess != process) {
                // The process died between answering session/new and this publish, and the backend's own
                // exit handling has already run for it while there was no connection to drop. Publishing conn
                // now would hand every later prompt a dead connection and leak its executors.
                conn.close();
                process.destroyForcibly();
                throw new IOException(backendDisplayNameForLogging() + " exited during start-up");
            }
            if (conn.isStreamEnded()) {
                if (!process.isAlive()) {
                    // It crashed, and its exit has not been handled yet. Report it as the crash it is —
                    // EXITED with its code and stderr — by running the exit handling now, below.
                    exitedUnreported = true;
                }
                else {
                    // Hung: output gone, process still running, so nothing can be read from conn and no exit
                    // will come to say so. Detach first so the process's exit arrives as stale and adds no
                    // EXITED: the handshake's own FAILED is the turn's one closer.
                    currentProcess = null;
                    conn.close();
                    process.destroyForcibly();
                    throw new IOException(backendDisplayNameForLogging() + " stopped responding during start-up");
                }
            }
            if (!exitedUnreported) {
                publishUnderLock.run();
            }
        }
        if (exitedUnreported) {
            // reportCrashExit reports the crash once and ends the turn, so the catch this throw reaches adds
            // nothing. Its own onExit callback, when it runs, finds the process no longer current.
            conn.close();
            reportCrashExit.run();
            throw new IOException(backendDisplayNameForLogging() + " exited during start-up");
        }
    }
}
