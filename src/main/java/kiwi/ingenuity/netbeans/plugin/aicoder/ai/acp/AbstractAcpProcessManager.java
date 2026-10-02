package kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

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
     * Backstop wait for a HELD Mail interrupt: if an in-flight tool call never reports a terminal status, the
     * safety valve delivers {@code session/cancel} anyway after this long.
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

    /**
     * How many of the agent's stderr lines are kept to show in an EXITED message. Shared by every ACP
     * backend's {@link #startStderrDrainer}/{@link #handleProcessExit}.
     */
    protected static final int MAX_STDERR_LINES = 100;

    /**
     * The agent's most recent stderr lines, drained by {@link #startStderrDrainer} on its own thread so a
     * chatty agent can never fill the pipe and block. Never private: each backend's own {@code stop()} clears
     * it (not moved here — stop() is explicitly backend-specific), so subclasses need direct access.
     */
    protected final List<String> recentStderr = new CopyOnWriteArrayList<>();

    /**
     * The registered MCP server for this session, or null if none is active — shared storage for
     * {@link #isMcpActive}. Every ACP backend registers an {@link AiMcpRegistrar} the same way; only its
     * construction (in each backend's own {@code start()}) differs.
     */
    protected volatile AiMcpRegistrar registrar = null;

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

    // ---- Mail interrupt must never abort an MCP tool call this plugin is itself servicing ----
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
     * turn, through {@link #sendCancelNotification}, with the in-flight-tool-call hold described above. ACP
     * has no separate mail-injection method; {@code session/cancel} ends the turn and cuts any in-flight tool
     * call with it. The queued inbox message is delivered on the next turn through the normal inbox flush.
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
        Runnable permissionCanceller;
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
            // Captured here, under the lock, so a concurrent handleProcessExit/onHandlerDisconnected/stop()
            // clearing the active handler in the window below can never leave a pending permission dialog
            // undismissed — same pattern as interrupt()'s capture-under-lock fix.
            permissionCanceller = hold ? null : capturePermissionCancellerUnderLock();
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
        if (permissionCanceller != null) {
            permissionCanceller.run();
        }
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
        Runnable permissionCanceller;
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
            // Captured here, under the lock — see interruptMail's identical reasoning.
            permissionCanceller = flush ? capturePermissionCancellerUnderLock() : null;
        }
        if (flush) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "{0} interrupt: Mail flushed — in-flight tool call completed (session={1})",
                        new Object[]{backendDisplayNameForLogging(), sid});
            }
            if (permissionCanceller != null) {
                permissionCanceller.run();
            }
            if (conn != null && sid != null) {
                sendCancelNotification(conn, sid);
            }
        }
    }

    /**
     * Backstop for a HELD Mail interrupt: if {@link #inFlightToolCalls} never returns to zero — a lost
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
            Runnable permissionCanceller;
            synchronized (this) {
                flush = pendingMailInterrupt && currentAcpConnection() == connAtHold;
                if (flush) {
                    pendingMailInterrupt = false;
                    inFlightToolCalls = 0;
                    inFlightToolCallIds.clear();
                    conn = currentAcpConnection();
                    sid = currentAcpSessionId();
                    // Captured here, under the lock — see interruptMail's identical reasoning.
                    permissionCanceller = capturePermissionCancellerUnderLock();
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
            if (permissionCanceller != null) {
                permissionCanceller.run();
            }
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

    // ---- Items below this point moved here from GrokAiProcessManager/OpenCodeAiProcessManager, each
    // previously an identical (or near-identical) copy. spawnAndHandshake is declared abstract here only
    // because a method that IS moving (handshakeAndSend) needs to call it; its own body stays in each
    // subclass, untouched by this refactor. ----
    /**
     * Spawns the agent process and performs the ACP handshake. Always called on a background thread. Not
     * moved here — each backend's launch command, env vars and port/debug-file handling are its own — but
     * declared abstract so {@link #handshakeAndSend} (which IS shared) can call it.
     */
    protected abstract void spawnAndHandshake(File workDir) throws Exception;

    /**
     * The reason a send was refused, for the INFO a refused send must post before its TurnCompleteEvent. Each
     * backend has its own set of reasons (e.g. OpenCode's compaction-in-progress case, which Grok has no
     * equivalent of), so this stays abstract rather than a shared list of reasons.
     */
    protected abstract String sendRefusalReason();

    /**
     * Runs right after {@link #sendPrompt} accepts a send (refusal checks passed, {@code cancelledByUser}
     * cleared), before the handshake-or-sendTurn branch. No-op by default (OpenCode's original sendPrompt set
     * {@code processing} only inside the handshake branch below, never here); Grok's original set
     * {@code processing = true} unconditionally at this point instead — kept exactly as found rather than
     * unified, though the two are provably equivalent in effect since {@link #sendTurn} (shared) always sets
     * {@code processing = true} itself on every path that reaches it.
     */
    protected void onSendAccepted() {
    }

    /**
     * Decorates the prompt text sent in a turn. Identity by default (Grok's original {@code sendTurn} sent
     * {@code text} verbatim); OpenCode overrides to prepend its {@code MCP_TOOL_PREFERENCE} reminder to every
     * turn.
     */
    protected String decoratePromptText(String text) {
        return text;
    }

    /**
     * Clears per-turn state on the active handler just before a new turn's prompt is sent — turn refusals for
     * every backend, plus whatever else that backend's handler type needs cleared. Abstract (not a hook with
     * a no-op default) because the handler field's type and the extra clears differ per backend: Grok's
     * handler has only the session/load-replay suppression; OpenCode's also clears its compaction
     * text-suppression flag, which Grok's handler has no equivalent of (no compaction feature at all).
     */
    protected abstract void clearTurnStartState();

    /**
     * Runs after {@link #resetMailInterruptHold} and {@code processing = true}, right before a turn's prompt
     * is actually sent. No-op by default; Grok overrides to stamp {@code lastPromptSentAtMillis} for its
     * exit-diagnostics log.
     */
    protected void onNewTurnStarting() {
    }

    /**
     * Sends one turn's prompt over the live connection. Previously an identical copy in both backends apart
     * from {@link #decoratePromptText}, {@link #clearTurnStartState} and {@link #onNewTurnStarting} (see
     * their javadoc for the difference each preserves). Not final:
     * {@code OpenCodeAiProcessManagerHandshakeTest} subclasses the process manager and overrides this (and
     * {@link #sendPrompt}) to record the prompt text instead of actually sending it, so a real
     * handshake/connection is never needed to test the hand-off from {@link #deliverAfterHandshake}.
     */
    public synchronized void sendTurn(String text) {
        String decoratedText = decoratePromptText(text);
        JsonObject promptItem = new JsonObject();
        promptItem.addProperty(AcpJsonKeyEnum.TYPE.key(), "text");
        promptItem.addProperty(AcpJsonKeyEnum.TEXT.key(), decoratedText);
        JsonArray promptArray = new JsonArray();
        promptArray.add(promptItem);

        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), currentAcpSessionId());
        params.add(AcpJsonKeyEnum.PROMPT.key(), promptArray);

        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "{0} prompt [{1}]: {2}",
                    new Object[]{backendDisplayNameForLogging().toLowerCase(), currentAcpSessionId(), text});
        }
        clearTurnStartState();
        // A new turn starts with no in-flight tool calls and no held mail interrupt. Stale state could only
        // have survived a teardown path that failed to clear it; resetting here keeps the next turn clean
        // regardless.
        resetMailInterruptHold();
        processing = true;
        onNewTurnStarting();
        Object turn = new Object();
        beginActiveTurn(turn); // before the send: a write to a dead agent fails it synchronously
        CompletableFuture<JsonObject> promptFuture = currentAcpConnection().sendRequest(AcpMethodEnum.SESSION_PROMPT, params);
        promptFuture
                .thenAccept(result -> {
                    if (claimTurn(turn)) {
                        handleTurnComplete(result);
                    }
                })
                .exceptionally(ex -> {
                    if (claimTurn(turn)) {
                        handleTurnError(ex);
                    }
                    return null;
                });
    }

    /**
     * Background-thread entry point when no ACP connection exists yet, handing the prompt off once the
     * connection is live. Previously an identical copy in both backends apart from {@link #onSendAccepted}
     * (see its javadoc). The handshake thread is a daemon, so a handshake still waiting cannot keep the JVM
     * alive for the handshake timeout. Not final: {@code OpenCodeAiProcessManagerHandshakeTest} overrides
     * this (and {@link #sendTurn}) to record delivery instead of actually sending.
     */
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        // isBusy() includes isWorkInFlight(), which is always false for Grok today (it never calls runWork).
        // If Grok ever gains runWork it inherits this refusal, and its sendRefusalReason should then name
        // work-in-flight.
        if (isBusy() || pendingDiff || !running) {
            // A submit AiTopComponent has already locked the UI for must never return silently, or the tab
            // would stay locked forever (cross-cutting rule found in the Copilot and Codex backends). None of
            // these refusals has a closer of its own, so INFO says why and TurnCompleteEvent releases the lock.
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, sendRefusalReason()));
            listener.onAiProcessEvent(new TurnCompleteEvent());
            return;
        }
        cancelledByUser = false;
        onSendAccepted();

        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        File effectiveWorkDir = sessionWorkingDir != null ? sessionWorkingDir : workingDir;

        if (currentAcpConnection() == null) {
            // spawnAndHandshake blocks for up to 60 s; sendPrompt runs on the EDT. Hand off to a background
            // thread and return immediately so the UI stays responsive. processing=true prevents a second
            // submit from racing the handshake.
            processing = true;
            Object turn = new Object();
            beginHandshakeTurn(turn);
            Thread t = new Thread(() -> handshakeAndSend(text, effectiveWorkDir, turn),
                    backendDisplayNameForLogging().toLowerCase() + "-handshake");
            t.setDaemon(true);
            t.start();
            return;
        }

        sendTurn(text);
    }

    /**
     * Runs unconditionally right after a turn completes, before the wasRunning branch below. No-op by default
     * (OpenCode has no equivalent); Grok overrides to call {@code reportUsage(result)}.
     */
    protected void onTurnComplete(JsonObject result) {
    }

    /**
     * Runs just before the TurnCompleteEvent a completed turn posts, when the session is still running. No-op
     * by default — Grok's original {@code handleTurnComplete} had no refusal-reporting concept at all (its
     * handler exposes no such events); OpenCode overrides to tell the UI a policy refusal ended this turn (so
     * the agent can be woken for it).
     */
    protected void onTurnCompleteWhileRunning(JsonObject result, boolean stoppedByUser) {
    }

    /**
     * Runs instead of the above when the turn completed but the session was no longer running (so no
     * TurnCompleteEvent is posted). No-op by default — Grok's original {@code handleTurnComplete} did nothing
     * in this case either, so ANY turn refusals its handler might have recorded while not running would
     * survive into the next turn (asymmetry flagged to Boss, not fixed); OpenCode overrides to discard its
     * turn's refusals so they are never carried into a later turn.
     */
    protected void onTurnCompleteWhileNotRunning() {
    }

    /**
     * Reacts to the {@code session/prompt} response. Previously an identical copy in both backends apart from
     * {@link #onTurnComplete}, {@link #onTurnCompleteWhileRunning} and {@link #onTurnCompleteWhileNotRunning}
     * (see their javadoc for the difference each preserves). Public final: called directly by each backend's
     * own test suite, from a class in that backend's package rather than a subclass of this one, with no test
     * fixture needing to override it.
     */
    public final void handleTurnComplete(JsonObject result) {
        boolean wasRunning;
        boolean stoppedByUser;
        synchronized (this) {
            processing = false;
            // Turn over: nothing left mid-turn to interrupt, so clear any HELD mail interrupt WITHOUT
            // sending — the mail was already delivered by the broker and is visible in the session's own
            // context on its next turn either way (mirrors OpenCode/Claude/Grok).
            resetMailInterruptHold();
            wasRunning = running;
            // Read BEFORE it is cleared: the only thing that tells a turn the user stopped from one a refusal
            // ended, and both come back as stopReason "cancelled".
            stoppedByUser = cancelledByUser;
            cancelledByUser = false;
        }
        onTurnComplete(result);
        if (wasRunning) {
            // BEFORE the turn-complete event, never after: the UI handles that event by deciding whether the
            // session carries straight on or goes idle, and it can only take a refusal into account if it has
            // already been told of it. Events reach the UI in the order they are posted here.
            onTurnCompleteWhileRunning(result, stoppedByUser);
            listener.onAiProcessEvent(new TurnCompleteEvent());
        }
        else {
            onTurnCompleteWhileNotRunning();
        }
    }

    /**
     * Runs when a turn error is anything other than a cancellation, before the generic FAILED fallback below.
     * No-op by default (Grok's original {@code handleTurnError} had no refusal-discarding concept at all);
     * OpenCode overrides to discard its turn's refusals, since a turn that failed for any other reason is not
     * one a refusal ended and its refusals must not be carried into a later turn.
     */
    protected void onTurnErrorDiscardingRefusals() {
    }

    /**
     * Runs when the turn error IS a cancellation (ACP's -32800 REQUEST_CANCELLED), right before the shared
     * TurnCompleteEvent it closes with. No-op by default; OpenCode overrides to tell the UI a policy refusal
     * ended this turn, the same as {@link #onTurnCompleteWhileRunning} does for the non-error path.
     */
    protected void onTurnErrorCancelled(boolean stoppedByUser) {
    }

    /**
     * Gives a backend the chance to handle a terminal turn error specially, before the generic FAILED
     * fallback. Returns true if it already posted its own closing status (the shared {@link #handleTurnError}
     * then does nothing more). False by default (Grok's original {@code handleTurnError} had no special-case
     * beyond REQUEST_CANCELLED, which the shared method already handles); OpenCode overrides to map
     * AUTH_REQUIRED to its fixed "run opencode auth login" message.
     */
    protected boolean onTurnError(Throwable cause) {
        return false;
    }

    /**
     * Reacts to a failed {@code session/prompt} request. Previously an identical copy in both backends apart
     * from {@link #onTurnErrorDiscardingRefusals}, {@link #onTurnErrorCancelled} and {@link #onTurnError}
     * (see their javadoc for the difference each preserves). Public final: called directly by each backend's
     * own test suite, from a class in that backend's package rather than a subclass of this one, with no test
     * fixture needing to override it.
     */
    public final void handleTurnError(Throwable ex) {
        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
        boolean stoppedByUser;
        synchronized (this) {
            processing = false;
            // Same turn-end clearing as handleTurnComplete — a cancelled/errored turn has no in-flight tool
            // calls left to interrupt.
            resetMailInterruptHold();
            stoppedByUser = cancelledByUser;
            cancelledByUser = false;
        }
        boolean cancelledReply = cause instanceof AcpException cancelEx
                                 && cancelEx.code() == AcpErrorCodeEnum.REQUEST_CANCELLED.code();
        if (cancelledReply) {
            // -32800: session/cancel was acknowledged; treat as normal cancel completion. The same refusal
            // report as a cancelled handleTurnComplete applies, before the turn-complete event for the same
            // reason; there is no stopReason here, -32800 itself says "cancelled".
            onTurnErrorCancelled(stoppedByUser);
            listener.onAiProcessEvent(new TurnCompleteEvent());
            return;
        }
        // A turn that failed for any other reason is not one a refusal ended, and its refusals must not be
        // carried into a later turn.
        onTurnErrorDiscardingRefusals();
        if (onTurnError(cause)) {
            return;
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                StatusMessageUtil.formatSendFailed(cause != null ? cause.getMessage() : ex.getMessage())));
    }

    /**
     * Sets the live ACP connection, or clears it ({@code null}). A setter rather than a hoisted field: the
     * field itself stays in each backend's own package-private storage, because existing tests in each
     * backend's own package read and write it directly by name.
     */
    protected abstract void setAcpConnection(AcpConnection conn);

    /**
     * Sets the live ACP session id, or clears it ({@code null}). See {@link #setAcpConnection} for why this
     * is a setter rather than a hoisted field.
     */
    protected abstract void setAcpSessionId(String sid);

    /**
     * Sets the session id the next handshake should resume, or clears it ({@code null}). See
     * {@link #setAcpConnection} for why this is a setter rather than a hoisted field.
     */
    protected abstract void setPendingAcpResumeId(String resumeId);

    /**
     * The configOptions array captured from the last session/new, session/load (or session/resume), or
     * set_config_option response. See {@link #setAcpConnection} for why this is an accessor rather than a
     * hoisted field.
     */
    protected abstract JsonArray currentConfigOptions();

    /**
     * Stores the configOptions array. See {@link #setAcpConnection} for why this is a setter rather than a
     * hoisted field.
     */
    protected abstract void setCurrentConfigOptions(JsonArray options);

    /**
     * Runs after {@link #detachDeadConnection} clears the connection/session/resume fields above, for state
     * scoped to one backend's own connection (its active permission handler, cached config or model data,
     * steer capability, ...). No-op by default.
     */
    protected void onConnectionDetached() {
    }

    /**
     * Backend-specific diagnostics logged right after a process exit, before the EXITED status (if any) is
     * reported. No-op by default (OpenCode's original copy logged nothing here); Grok overrides to log its
     * exit-code annotation and time since the last prompt.
     */
    protected void logProcessExitDiagnostics(int code, boolean suppress) {
    }

    /**
     * Whether {@code candidateId} looks like this backend's own session id, before {@link #resumeSession}
     * accepts it as the next handshake's resume target. True by default (Grok's original copy of this method
     * accepted anything non-blank); OpenCode overrides to require its {@code ses_} prefix, guarding against a
     * stray plugin-level UUID reaching the resume slot.
     */
    protected boolean isPlausibleResumeId(String candidateId) {
        return true;
    }

    /**
     * Diagnostic hook for {@link #interrupt}: a Cancel arrived with no turn in flight. No-op by default
     * (Grok's original copy of this method logged nothing here); OpenCode overrides to log it.
     */
    protected void onCancelIgnored() {
    }

    /**
     * Diagnostic hook for {@link #interrupt}: a Cancel was accepted and the turn is winding down. No-op by
     * default; OpenCode overrides to log it.
     */
    protected void onCancelAccepted(String sid, boolean connected) {
    }

    /**
     * Diagnostic hook for {@link #interrupt}: {@code session/cancel} was actually sent. No-op by default;
     * OpenCode overrides to log it.
     */
    protected void onCancelNotificationSent(String sid) {
    }

    /**
     * Captures, under the caller's lock, a canceller bound to whatever permission handler is active AT THIS
     * MOMENT — not a lazy reference that re-reads the field later. {@link #interrupt}, {@link #interruptMail},
     * {@link #trackToolCallLifecycle} and {@link #startMailInterruptSafetyValve} all call this while still
     * holding the monitor, then run the returned canceller only after releasing it, so a concurrent
     * {@link #handleProcessExit}/{@link #onHandlerDisconnected}/{@code stop()} clearing the active handler in
     * that window can never leave a pending permission dialog undismissed (review finding against
     * {@code interrupt}'s first cut, which read the active handler again only after the unlock — OpenCode's
     * pre-Stage-1 baseline had always captured it inside the lock for exactly this reason). Implementations
     * must evaluate the active handler eagerly (e.g. {@code handler::cancelPendingPermissions} bound to a
     * local already read from the field) and return null when there is none to cancel.
     */
    protected abstract Runnable capturePermissionCancellerUnderLock();

    /**
     * Drains the agent's stderr on its own thread so a chatty agent can never fill the pipe and block. The
     * last {@link #MAX_STDERR_LINES} lines are kept and shown in the EXITED message. Previously an identical
     * copy in both {@code GrokAiProcessManager} and {@code OpenCodeAiProcessManager}, differing only in the
     * backend name baked into the log text and thread name — now read from
     * {@link #backendDisplayNameForLogging}.
     */
    protected final void startStderrDrainer(Process process) {
        String name = backendDisplayNameForLogging().toLowerCase();
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.WARNING, "{0} stderr: {1}", new Object[]{name, McpHookServerUtil.redactAllSecrets(line)});
                    }
                    recentStderr.add(line);
                    while (recentStderr.size() > MAX_STDERR_LINES) {
                        recentStderr.remove(0);
                    }
                }
            }
            catch (IOException e) {
                LOG.log(Level.FINE, name + " stderr drainer ended", e);
            }
        }, name + "-stderr");
        t.setDaemon(true);
        t.start();
    }

    /**
     * {@code dead}'s reader lost its stream. {@link #handleProcessExit} handles exit reporting; this drops
     * the dead connection so the next prompt re-handshakes instead of writing to it. Only for the connection
     * actually published: a handshake still in flight owns its own (see
     * {@link #publishConnectionOrReportExit}'s stream-ended check), and a late callback from a connection
     * already replaced must not touch the one that replaced it. Previously an identical copy in both
     * backends.
     */
    protected final void onHandlerDisconnected(AcpConnection dead) {
        Process owner;
        synchronized (this) {
            if (dead == null || currentAcpConnection() != dead) {
                return;
            }
            owner = currentProcess;
        }
        // An agent that crashed closes its output as it dies, so its exit is normally only moments behind
        // this callback. Leave that case to handleProcessExit, which reports it once as EXITED (with the exit
        // code and stderr) and drops the connection itself. Runs on the dead connection's own notify thread,
        // so the short wait holds up nothing else.
        if (owner != null) {
            try {
                if (owner.waitFor(DISCONNECT_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Still running with its output gone: it can never answer again, and no exit will come to say so.
        // Kill it and detach from it, so its eventual exit is stale and adds no EXITED; closing the connection
        // then fails the prompt in flight, which reports the turn's one FAILED.
        AcpConnection orphaned;
        synchronized (this) {
            if (currentAcpConnection() != dead) {
                return; // handleProcessExit or stop() got there first
            }
            processing = false;
            resetMailInterruptHold();
            orphaned = detachDeadConnection();
            if (currentProcess == owner) {
                currentProcess = null;
            }
        }
        if (owner != null) {
            owner.destroyForcibly();
        }
        if (orphaned != null) {
            orphaned.close();
        }
    }

    /**
     * Drops the published connection of an agent that has died, keeping its ACP session id as the one to
     * resume, so the next prompt starts a fresh process that picks the conversation up again rather than
     * writing to a dead pipe forever. Caller holds the monitor and closes the returned connection outside it.
     * Previously an identical copy in both backends, apart from the backend-specific resets now in
     * {@link #onConnectionDetached}.
     */
    protected final AcpConnection detachDeadConnection() {
        AcpConnection orphaned = currentAcpConnection();
        String sidToKeep = currentAcpSessionId();
        setAcpConnection(null);
        if (sidToKeep != null) {
            setPendingAcpResumeId(sidToKeep);
        }
        setAcpSessionId(null);
        onConnectionDetached();
        return orphaned;
    }

    /**
     * Reports a crashed (or otherwise unexpectedly exited) process as EXITED, exactly once, and ends any turn
     * in flight. A process that exits as part of an orderly {@code stop()} has already had
     * {@code currentProcess} cleared, so this finds nothing current and does nothing. Previously an identical
     * copy in both backends apart from {@link #logProcessExitDiagnostics} (see its javadoc for the difference
     * it preserves) and the {@code cancelledByUser} reset below, now unconditional for both. The reset
     * happens in the same synchronized block as {@link #clearActiveTurn()}, and {@code orphaned.close()}
     * (which fails the pending prompt future) runs after it, so {@code claimTurn()} fails and
     * {@link #handleTurnError} never reads the reset flag. Public (not the package-private visibility this
     * had in each backend before the move) and NOT final: several existing test fixtures in each backend's
     * own package call it directly, or subclass the process manager and override it (wrapping with
     * {@code super.handleProcessExit(...)}) to observe or gate a simulated exit — both need it to stay
     * overridable and visible across packages.
     */
    public void handleProcessExit(Process process) {
        boolean suppress;
        AcpConnection orphaned;
        synchronized (this) {
            if (currentProcess != process) {
                return; // stale exit from a superseded process
            }
            processing = false;
            resetMailInterruptHold();
            currentProcess = null;
            suppress = cancelledByUser;
            cancelledByUser = false;
            orphaned = detachDeadConnection();
            if (suppress || process.exitValue() != 0) {
                // The turn already has its closing status — the EXITED below, or the STOPPED the user's Stop
                // already sent — whether its handshake or its prompt was in flight. Closing the orphaned
                // connection below fails the pending request's future; without clearing these first,
                // claimTurn/claimHandshakeTurn would still succeed for it and add a second closer (a FAILED
                // from handleTurnError) on top of the one already reported. A clean exit (code 0, never
                // user-initiated) reports nothing here, so the turn is left for that FAILED to close
                // normally.
                clearHandshakeTurn();
                clearActiveTurn();
            }
        }
        if (orphaned != null) {
            orphaned.close();
        }
        int code = process.exitValue();
        logProcessExitDiagnostics(code, suppress);
        // A compaction (or other non-turn work) whose process died must not be left locking the UI: its
        // closing FAILED comes from here, not from a response that will never arrive. Harmless — and always a
        // no-op — for a backend that never starts non-turn work through runWork() in the first place.
        if (!failWorkInFlight(backendDisplayNameForLogging() + " exited (code " + code + ") while work was in progress")
            && !suppress && code != 0) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED,
                    StatusMessageUtil.formatExited(backendDisplayNameForLogging(), code, new ArrayList<>(recentStderr))));
        }
    }

    /**
     * Background-thread entry point when no ACP connection exists yet. Calls {@link #spawnAndHandshake}
     * (which blocks up to 60 s), then hands the prompt to {@link #deliverAfterHandshake} once the connection
     * is live. Previously an identical copy in both backends.
     */
    protected final void handshakeAndSend(String text, File workDir, Object turn) {
        try {
            spawnAndHandshake(workDir);
        }
        catch (Exception e) {
            boolean stillOurTurn;
            synchronized (this) {
                stillOurTurn = claimHandshakeTurn(turn);
                if (stillOurTurn) {
                    processing = false;
                }
            }
            // Not our turn any more: Stop, stop() or the exit already ended it, and processing may now
            // belong to a newer turn — report nothing and leave it alone.
            if (stillOurTurn) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(e.getMessage())));
            }
            return;
        }
        deliverAfterHandshake(text, turn);
    }

    /**
     * Post-handshake delivery of the prompt queued by {@code sendPrompt}. Public (not the package-private
     * visibility this had in each backend before the move): each backend's own test suite calls it directly
     * by name, from a class in that backend's package rather than a subclass of this one, so it must be
     * visible across packages. Keeps {@code processing} true through the hand-off: {@code sendTurn} rearms
     * it, so only paths that never reach sendTurn clear it — exactly once, under the monitor. Runs entirely
     * under the monitor so a Stop cannot land between the turn check and {@code sendTurn}. Previously an
     * identical copy in both backends.
     */
    public final synchronized void deliverAfterHandshake(String text, Object turn) {
        if (!claimHandshakeTurn(turn)) {
            // The turn ended while the handshake ran — Stop, stop() or an exit already gave it its closing
            // status. Sending it now would start a turn the user stopped.
            return;
        }
        if (!running || pendingDiff) {
            processing = false; // stop()/diff panel won the race; nobody else will rearm
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, sendRefusalReason()));
            listener.onAiProcessEvent(new TurnCompleteEvent());
            return;
        }
        // Connection is now established; deliver through the normal turn path. Deliberately sendTurn(), not
        // sendPrompt(): with processing still held true, sendPrompt's own guard would reject the re-entry and
        // silently drop the prompt.
        sendTurn(text);
    }

    /**
     * Cancels the in-flight turn (Cancel) or delivers mail by cancelling it (Mail, via
     * {@link #interruptMail}). Previously an identical copy in both backends apart from OpenCode's extra
     * debug-JSON logging, now in {@link #onCancelIgnored}/{@link #onCancelAccepted}/
     * {@link #onCancelNotificationSent}.
     *
     * <p>
     * Mail DOES reach the agent mid-turn for both backends (live-confirmed) — it just has no NON-CANCELLING
     * injection channel to do it with, unlike Codex's turn/steer, Copilot's immediate setPrompt, or Pi's
     * steer. Instead it travels over the same {@code session/cancel} notification, with the same in-flight
     * tool-call hold in {@link #interruptMail}, that Cancel uses here: the running turn is aborted and the
     * mail is delivered as the next one (ABORTS_TURN).
     *
     * <p>
     * The permission canceller is captured under the lock ({@link #capturePermissionCancellerUnderLock}), not
     * read from the active-handler field again after releasing it: a concurrent
     * {@link #handleProcessExit}/{@link #onHandlerDisconnected}/{@code stop()} clearing that field in the gap
     * between unlock and the cancel would otherwise leave a pending permission dialog undismissed (review
     * finding against OpenCode's baseline, which captured its handler inside the lock for the same reason).
     */
    public final void interrupt(InterruptTypeEnum type) {
        if (type == InterruptTypeEnum.Mail) {
            interruptMail();
            return;
        }
        if (type != InterruptTypeEnum.Cancel) {
            return;
        }
        AcpConnection conn;
        String sid;
        Runnable permissionCanceller;
        synchronized (this) {
            if (!processing) {
                onCancelIgnored();
                return;
            }
            cancelledByUser = true;
            processing = false;
            clearHandshakeTurn(); // STOPPED below closes it; a handshake still running must not send it
            conn = currentAcpConnection();
            sid = currentAcpSessionId();
            permissionCanceller = capturePermissionCancellerUnderLock();
        }
        onCancelAccepted(sid, conn != null);
        if (permissionCanceller != null) {
            permissionCanceller.run();
        }
        if (conn != null && sid != null) {
            sendCancelNotification(conn, sid);
            onCancelNotificationSent(sid);
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
    }

    /**
     * Accepts {@code existingSessionId} as the id the next handshake should resume, unless it is blank or
     * fails {@link #isPlausibleResumeId}. Previously an identical copy in both backends apart from that
     * guard.
     */
    public final void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        if (!isPlausibleResumeId(existingSessionId)) {
            return;
        }
        setPendingAcpResumeId(existingSessionId);
    }

    public final boolean isMcpActive() {
        return registrar != null;
    }

    /**
     * The configOptions array captured from the session/new, session/load (or session/resume), or
     * set_config_option response. Null before the ACP handshake completes. Not final: several existing test
     * fixtures subclass the process manager and override this (and {@link #setConfigOption}) to fake
     * configOptions without a real connection.
     */
    public JsonArray configOptions() {
        return currentConfigOptions();
    }

    /**
     * Changes one session config option. Completes with the COMPLETE configOptions snapshot from the
     * response, not just the changed entry — options are interdependent (e.g. effort depends on the selected
     * model), and no config_option_update notification is sent for a change this client itself requested, so
     * the response is the only source of truth. Previously an identical copy in both backends apart from the
     * backend name in the "session is not active" message, now read from
     * {@link #backendDisplayNameForLogging}. Not final — see {@link #configOptions} for why.
     */
    public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
        AcpConnection conn = currentAcpConnection();
        String sid = currentAcpSessionId();
        if (conn == null || sid == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(backendDisplayNameForLogging() + " session is not active"));
        }
        JsonObject params = new JsonObject();
        params.addProperty(AcpJsonKeyEnum.SESSION_ID.key(), sid);
        params.addProperty(AcpJsonKeyEnum.CONFIG_ID.key(), configId);
        params.addProperty(AcpJsonKeyEnum.VALUE.key(), value);
        return conn.sendRequest(AcpMethodEnum.SESSION_SET_CONFIG_OPTION, params)
                .thenApply(result -> {
                    JsonArray options = result != null && result.has(AcpJsonKeyEnum.CONFIG_OPTIONS.key())
                                        && result.get(AcpJsonKeyEnum.CONFIG_OPTIONS.key()).isJsonArray()
                                        ? result.getAsJsonArray(AcpJsonKeyEnum.CONFIG_OPTIONS.key())
                                        : new JsonArray();
                    setCurrentConfigOptions(options);
                    return options;
                });
    }
}
