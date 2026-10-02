package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AbstractAcpClientHandler;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AbstractAcpProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AcpMethodEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.session.GrokAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Manages Grok (xAI's CLI, https://docs.x.ai/build/cli) via one long-lived {@code grok agent stdio} process
 * per plugin session, speaking the same Agent Client Protocol as OpenCode. Lifecycle, the handshake-turn/
 * active-turn tokens, the protocol builders and the permission bridge are shared with OpenCode through
 * {@link AbstractAcpProcessManager}/{@link AbstractAcpClientHandler}; this class supplies Grok's launch
 * command, its model/reasoning-effort config options, and its usage/shutdown specifics.
 *
 * <p>
 * Unlike the CLI's old headless {@code grok -p} mode (one process per turn), {@code agent stdio} is a single
 * process for the whole session: {@link #start} only validates the executable and registers the MCP server;
 * the process itself is spawned lazily on the first {@link #sendPrompt}, exactly as {@code
 * OpenCodeAiProcessManager} does.
 */
public class GrokAiProcessManager extends AbstractAcpProcessManager {

    private static final Logger LOG = Logger.getLogger(GrokAiProcessManager.class.getName());

    /**
     * Grace period {@link #stop()} gives Grok to exit after its stdin is closed before escalating to a forced
     * kill. Verified by live probe (Boss's handover): closing stdin alone does NOT make Grok exit.
     */
    private static final long SHUTDOWN_GRACE_MILLIS = 3_000L;

    /**
     * Test seam: overrides {@link #SHUTDOWN_GRACE_MILLIS} so a test proving the forced-kill escalation does
     * not have to wait out the real grace period. Null in production.
     */
    static volatile Long shutdownGraceMillisForTests = null;

    private static long shutdownGraceMillis() {
        Long override = shutdownGraceMillisForTests;
        return override != null ? override : SHUTDOWN_GRACE_MILLIS;
    }

    static List<String> buildAcpCommand(String executablePath) {
        return buildAcpCommand(executablePath, null);
    }

    /**
     * @param debugFilePath when non-null, appends {@code --debug --debug-file <debugFilePath>} — accepted by
     *                      {@code grok agent} (per {@code grok agent --help}) — so a live failure that is
     *                      hard to reproduce from this plugin's own debug-JSON log has Grok's own internal
     *                      trace to check too. Null when {@link PluginSettings#isDebugJson()} is off, so a
     *                      normal run never pays for a debug file nobody will read.
     */
    static List<String> buildAcpCommand(String executablePath, String debugFilePath) {
        List<String> args = new ArrayList<>(List.of("--no-auto-update", "agent", "stdio"));
        if (debugFilePath != null) {
            args.add("--debug");
            args.add("--debug-file");
            args.add(debugFilePath);
        }
        return GrokExecutableLocator.buildHostCommand(executablePath, args.toArray(new String[0]));
    }

    static JsonObject buildSessionNewParams(String absoluteCwd, String mcpEndpointUrl) {
        return AbstractAcpProcessManager.buildSessionParams(absoluteCwd, mcpEndpointUrl, null);
    }

    static JsonObject buildSessionLoadParams(String grokSessionId, String absoluteCwd, String mcpEndpointUrl) {
        return AbstractAcpProcessManager.buildSessionParams(absoluteCwd, mcpEndpointUrl, grokSessionId);
    }

    Predicate<String> ownSessionConfigFileCheck() {
        return AbstractAcpClientHandler.ownSessionConfigFileCheck(sessionId);
    }

    @Override
    protected AcpConnection currentAcpConnection() {
        return connection;
    }

    @Override
    protected String currentAcpSessionId() {
        return acpSessionId;
    }

    /**
     * Grok's original {@code interrupt} never captured the handler under the lock — using the
     * capture-under-lock form here anyway is strictly stronger, not a behaviour change back to anything Grok
     * actually depended on: it just closes the same race OpenCode's baseline was already guarding against.
     */
    @Override
    protected Runnable capturePermissionCancellerUnderLock() {
        GrokAcpClientHandler h = activeHandler;
        return h != null ? h::cancelPendingPermissions : null;
    }

    @Override
    protected String backendDisplayNameForLogging() {
        return "Grok";
    }

    // Package-private, mirroring OpenCodeAiProcessManager, so tests can inject a pipe-backed
    // AcpConnection and assert wire ordering.
    volatile AcpConnection connection = null;
    volatile String acpSessionId = null;
    volatile String pendingAcpResumeId = null;
    volatile JsonArray sessionConfigOptions = null;
    volatile GrokAcpClientHandler activeHandler = null;
    /**
     * Per-model context-window size (total tokens), read once from {@code initialize}/{@code session/new}'s
     * {@code models.availableModels[]._meta.totalContextTokens} and kept for the life of the connection —
     * every known model's window, not just the one Grok started with, so a model switch via
     * {@code set_config_option} picks up the new model's window on its next {@link #reportUsage} without a
     * second round-trip. Empty (never null) when the agent's response carries no such data.
     */
    volatile Map<String, Integer> modelContextWindows = Map.of();
    /**
     * Wall-clock time of the last {@code session/prompt} send, or 0 if none has been sent yet — diagnostics
     * only, read by {@link #handleProcessExit}'s exit log so an unexpected exit's log line says how long
     * after the prompt it happened, without needing to cross-reference timestamps by hand.
     */
    private volatile long lastPromptSentAtMillis = 0L;
    /**
     * Grok's own {@code --debug-file} trace for the live connection, or null when debug-JSON is off (no file
     * was requested). Contains unredacted prompts and secrets verbatim (Grok's own writer, not this
     * plugin's), so it lives under the session's private config dir, never {@code java.io.tmpdir}, and is
     * deleted in {@link #stop()}.
     */
    private volatile File grokDebugLogFile = null;

    private GrokAiSession grokAiSession = null;
    /**
     * Package-private, not private: tests assert the stored effort directly rather than through a
     * since-removed pure {@code buildReasoningEffortArgs(model)} seam (the CLI predecessor's approach) — ACP
     * applies the configured effort as a side effect of the handshake, not a value a caller builds CLI flags
     * from.
     */
    volatile String reasoningEffort;
    /**
     * Only a session-sourced effort is ever cleared on an unsupported value, never the global default.
     */
    volatile boolean reasoningEffortFromSession;
    private volatile Runnable onReasoningEffortCleared;
    /**
     * Serial queue for config changes made on a running session (model, then the effort check that depends on
     * it). One thread, so they apply in the order they were made; it exits when idle.
     */
    private final ThreadPoolExecutor configOperations = newConfigOperationQueue();
    /**
     * Guards the stored effort and its scope flag, so a rejected level is only cleared while the field still
     * holds the level that was judged, never over a newer pick.
     */
    private final Object effortFieldLock = new Object();
    volatile Runnable onSessionEstablished = null;

    public GrokAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    void setHandshakeTurnForTesting(Object turn) {
        beginHandshakeTurn(turn);
    }

    void setOnSessionEstablished(Runnable r) {
        this.onSessionEstablished = r;
    }

    public void configureReasoningEffort(String level, boolean fromSession) {
        synchronized (effortFieldLock) {
            this.reasoningEffort = (level == null || level.isBlank()) ? null : level;
            this.reasoningEffortFromSession = fromSession;
        }
    }

    public void setOnReasoningEffortCleared(Runnable callback) {
        this.onReasoningEffortCleared = callback;
    }

    public boolean isSessionLive() {
        return connection != null && acpSessionId != null;
    }

    @Override
    protected void setAcpConnection(AcpConnection conn) {
        connection = conn;
    }

    @Override
    protected void setAcpSessionId(String sid) {
        acpSessionId = sid;
    }

    @Override
    protected void setPendingAcpResumeId(String resumeId) {
        pendingAcpResumeId = resumeId;
    }

    @Override
    protected JsonArray currentConfigOptions() {
        return sessionConfigOptions;
    }

    @Override
    protected void setCurrentConfigOptions(JsonArray options) {
        sessionConfigOptions = options;
    }

    /**
     * Also drops config state scoped to the dead connection ({@code sessionConfigOptions},
     * {@code modelContextWindows}): both are re-read fresh on the next handshake, and a stale model's context
     * window surviving into a new process would be a silent lie.
     */
    @Override
    protected void onConnectionDetached() {
        activeHandler = null;
        sessionConfigOptions = null;
        modelContextWindows = Map.of();
    }

    /**
     * Confirmed root cause of a live Grok failure (exit 143, SIGTERM, right after the first prompt, no
     * stderr) — see {@link AbstractAcpProcessManager#startProcessOnOwnerThread}. Kept so a regression of that
     * fix shows up here again, with the time since the last prompt to judge how far into a turn it happened.
     */
    @Override
    protected void logProcessExitDiagnostics(int code, boolean suppress) {
        if (PluginSettings.isDebugJson()) {
            long sincePrompt = lastPromptSentAtMillis == 0 ? -1 : System.currentTimeMillis() - lastPromptSentAtMillis;
            LOG.log(Level.INFO,
                    "Grok process exited: code={0} ({1}), stoppedByUs={2}, msSinceLastPromptSent={3}",
                    new Object[]{code, code == 143 ? "SIGTERM" : code == 137 ? "SIGKILL" : "see exit code",
                                 suppress, sincePrompt});
        }
    }

    @Override
    public synchronized void start(String executablePath, String model) {
        stop();
        this.executablePath = executablePath;
        this.model = model;

        if (!GrokExecutableLocator.isExecutableFile(executablePath)) {
            running = false;
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatStartFailed("grok executable not found at " + executablePath)));
            return;
        }
        if (currentSession == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSessionNotConfigured()));
            return;
        }
        sessionId = currentSession.id();

        GrokAiMcpRegistrar reg = new GrokAiMcpRegistrar(sessionId);
        try {
            boolean ok = McpServerRegistry.register(reg).get(GrokTimeoutEnum.GROK_CLI_CONFIG_WRITE_MILLIS.millis(), TimeUnit.MILLISECONDS);
            if (ok) {
                registrar = reg;
            }
            else {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "MCP server registration returned false — running without MCP tools"));
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "MCP registration failed for session " + sessionId, e);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "MCP server unavailable — running without MCP tools"));
        }

        grokAiSession = new GrokAiSession(currentSession, listener);
        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("Grok")));
    }

    @Override
    protected void onSendAccepted() {
        processing = true;
    }

    /**
     * Seam for tests: overridden to return a controllable fake {@link Process} instead of actually spawning
     * the grok CLI.
     *
     * <p>
     * Production spawns on a dedicated owner thread that outlives the handshake thread calling this, not on
     * the calling thread itself — see {@link AbstractAcpProcessManager#startProcessOnOwnerThread}. {@code
     * grok agent stdio} arms {@code prctl(PR_SET_PDEATHSIG, SIGTERM)}, a per-THREAD Linux kernel feature:
     * without this, the handshake thread finishing (moments after the first prompt is sent) SIGTERMs grok the
     * instant that thread exits, regardless of whether the JVM process itself is still running — a live
     * failure confirmed from Grok's own source (exit 143, no stderr, right after the prompt).
     */
    Process startProcess(ProcessBuilder pb) throws IOException {
        return startProcessOnOwnerThread(pb);
    }

    @Override
    protected String sendRefusalReason() {
        if (processing) {
            return "Grok is already processing a turn";
        }
        if (pendingDiff) {
            return "Grok is waiting for a pending diff review";
        }
        return "Grok session is not running";
    }

    /**
     * Spawns the {@code grok agent stdio} process and performs the ACP handshake. Always called on a
     * background thread. The instance monitor is held only for brief state writes, never across the blocking
     * waits.
     */
    @Override
    protected void spawnAndHandshake(File workDir) throws Exception {
        String debugFilePath = null;
        if (PluginSettings.isDebugJson()) {
            try {
                // The session's own private config dir (~/.ai-coder/grok/{sessionId}/), never java.io.tmpdir:
                // this file is Grok's own unredacted trace and can contain prompts and secrets verbatim
                // (review finding). Deleted in stop().
                File debugFile = PluginUtil.getPluginAiSessionConfigDir(AiTypeEnum.GROK, sessionId)
                        .resolve("grok-acp-debug.log").toFile();
                debugFilePath = debugFile.getAbsolutePath();
                grokDebugLogFile = debugFile;
                LOG.log(Level.INFO, "Grok debug log: {0}", debugFilePath);
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "could not create Grok's debug log directory; starting without --debug-file", e);
            }
        }
        List<String> cmd = buildAcpCommand(executablePath, debugFilePath);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null && workDir.isDirectory()) {
            pb.directory(workDir);
        }
        recentStderr.clear();
        Process process = startProcess(pb);
        synchronized (this) {
            if (!running) {
                process.destroyForcibly();
                throw new IOException("stop() called before handshake began");
            }
            currentProcess = process;
        }
        startStderrDrainer(process);

        AcpConnection[] connHolder = new AcpConnection[1];
        GrokAcpClientHandler handler = new GrokAcpClientHandler(listener,
                () -> onHandlerDisconnected(connHolder[0]),
                this::trackToolCallLifecycle, ownSessionConfigFileCheck(), null, sessionId);
        AcpConnection conn = new AcpConnection(process.getOutputStream(), process.getInputStream(), handler, "Grok");
        connHolder[0] = conn;

        process.onExit().thenRun(() -> handleProcessExit(process));

        JsonObject initResult;
        try {
            initResult = conn.sendRequest(AcpMethodEnum.INITIALIZE, buildInitializeParams(Installer.VERSION))
                    .get(30, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            conn.close();
            synchronized (this) {
                if (currentProcess == process) {
                    currentProcess = null;
                }
            }
            process.destroyForcibly();
            throw new IOException("ACP initialize failed: " + e.getMessage(), e);
        }
        int proto = initResult.has(AcpJsonKeyEnum.PROTOCOL_VERSION.key()) ? initResult.get(AcpJsonKeyEnum.PROTOCOL_VERSION.key()).getAsInt() : -1;
        if (proto != 1) {
            conn.close();
            synchronized (this) {
                if (currentProcess == process) {
                    currentProcess = null;
                }
            }
            process.destroyForcibly();
            throw new IOException("Unsupported ACP protocol version: " + proto + " (expected 1)");
        }

        String mcpBaseUrl = McpServerRegistry.endpointUrlFor(AiTypeEnum.GROK);
        String resumeId = pendingAcpResumeId;
        JsonObject sessionResult = null;
        boolean resumed = false;

        if (resumeId != null) {
            // Grok replays the whole prior conversation as session/update notifications between sending
            // session/load and answering it; the plugin's own persisted history already shows it, so the
            // replay must never reach the UI as new output (live-confirmed bug).
            long loadSuppressionToken = handler.beginSuppressingSessionUpdatesForLoad();
            try {
                sessionResult = conn.sendRequest(AcpMethodEnum.SESSION_LOAD,
                        buildSessionLoadParams(resumeId, workDir.getAbsolutePath(), mcpBaseUrl))
                        .get(30, TimeUnit.SECONDS);
                resumed = true;
            }
            catch (Exception e) {
                LOG.log(Level.INFO, "session/load failed; falling back to session/new: {0}", e.getMessage());
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Previous Grok session could not be resumed; starting fresh"));
            }
            finally {
                // Clearing must be ordered AFTER every replay notification the reader already queued on
                // acp-notify, not run synchronously the instant get() returns — review finding: the load's
                // response and a queued replay chunk complete on different executors, so a synchronous clear
                // here could race ahead of acp-notify and leak the replay's tail.
                Runnable clearSuppression = () -> handler.endSuppressingSessionUpdatesForLoad(loadSuppressionToken);
                try {
                    conn.runOnNotifyThread(clearSuppression);
                }
                catch (RejectedExecutionException e) {
                    clearSuppression.run();
                }
            }
        }

        if (!resumed) {
            try {
                sessionResult = conn.sendRequest(AcpMethodEnum.SESSION_NEW,
                        buildSessionNewParams(workDir.getAbsolutePath(), mcpBaseUrl))
                        .get(30, TimeUnit.SECONDS);
            }
            catch (Exception e) {
                conn.close();
                synchronized (this) {
                    if (currentProcess == process) {
                        currentProcess = null;
                    }
                }
                process.destroyForcibly();
                throw new IOException("ACP session/new failed: " + e.getMessage(), e);
            }
        }

        String sid = resolveSessionId(resumed, resumeId, sessionResult);
        if (sid == null || sid.isBlank()) {
            conn.close();
            synchronized (this) {
                if (currentProcess == process) {
                    currentProcess = null;
                }
            }
            process.destroyForcibly();
            throw new IOException("session/new returned no " + AcpJsonKeyEnum.SESSION_ID.key());
        }

        // ---- Publish results, or report a dead agent, under the lock shared by every ACP backend ----
        final JsonObject resolvedSessionResult = sessionResult;
        publishConnectionOrReportExit(conn, process,
                () -> {
                    acpSessionId = sid;
                    pendingAcpResumeId = null;
                    if (currentSession != null && currentSession.settings() instanceof kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings gs) {
                        gs.setAcpSessionId(sid);
                    }
                    if (resolvedSessionResult.has(AcpJsonKeyEnum.CONFIG_OPTIONS.key()) && resolvedSessionResult.get(AcpJsonKeyEnum.CONFIG_OPTIONS.key()).isJsonArray()) {
                        sessionConfigOptions = resolvedSessionResult.getAsJsonArray(AcpJsonKeyEnum.CONFIG_OPTIONS.key());
                    }
                    modelContextWindows = parseModelContextWindows(initResult, resolvedSessionResult);
                    activeHandler = handler;
                    this.connection = conn;
                },
                () -> handleProcessExit(process));
        if (resumed) {
            LOG.log(Level.INFO, "Resumed Grok ACP session: {0}", acpSessionId);
        }
        else {
            LOG.log(Level.INFO, "Started new Grok ACP session: {0}", acpSessionId);
        }
        Runnable cb = onSessionEstablished;
        if (cb != null) {
            cb.run();
        }
        applyInitialConfigOptionsOnQueue();
    }

    /**
     * Runs the start-up model/effort apply on the config-operation queue and waits for it, so a pick made on
     * the session as soon as it is published is queued behind it rather than sent alongside it: otherwise the
     * start-up response could land last and leave the agent on the pre-pick value. Waiting keeps the first
     * turn from being sent before the start-up values are in place, as before. If the wait times out the
     * handshake carries on and the task keeps running, but only against the session it was queued for: it is
     * dropped the moment that session is replaced, so it can never apply to a later one.
     */
    void applyInitialConfigOptionsOnQueue() {
        try {
            BooleanSupplier current = sessionGuard();
            configOperations.submit(() -> {
                if (current.getAsBoolean()) {
                    applyInitialConfigOptionsIfNeeded(current);
                }
            }).get(2, TimeUnit.MINUTES);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Applying Grok's start-up config options failed: {0}", e.getMessage());
        }
    }

    /**
     * Applies the session's stored model/reasoning-effort against what Grok actually started with, same shape
     * as {@code OpenCodeAiProcessManager.applyInitialModelOption}: only ever sent when it differs from the
     * agent's current value, and only when the agent actually offers it.
     */
    void applyInitialConfigOptionsIfNeeded() {
        applyInitialConfigOptionsIfNeeded(ALWAYS_CURRENT);
    }

    private void applyInitialConfigOptionsIfNeeded(BooleanSupplier current) {
        // Snapshotted once, not re-read from the volatile field per call: a crash can race in between this
        // method's two config-option calls (model, then reasoning_effort) and null sessionConfigOptions via
        // detachDeadConnection. Without a stable snapshot, the second call would see "no options" and
        // mistake a dead connection for the agent rejecting reasoning_effort, wrongly clearing a
        // session-sourced value the agent never actually got a chance to accept or reject.
        JsonArray options = sessionConfigOptions;
        if (options == null) {
            return;
        }
        applyConfigOptionIfNeeded(options, "model", model, current);
        // The effort is judged against what the model request left, not the pre-model snapshot: the levels a
        // model accepts depend on the model, so the old snapshot would reject an effort only the new model has.
        // The snapshot is still the fallback when the session went away meanwhile (nothing newer to read).
        JsonArray confirmed = sessionConfigOptions;
        reconcileReasoningEffort(confirmed != null ? confirmed : options, current);
    }

    /**
     * The effort half shared by start-up and every live change: applies the stored effort against
     * {@code options}, which must be the latest confirmed ones, and clears a session-sourced value the agent
     * rejects only when the agent is on the model that was asked for.
     */
    private void reconcileReasoningEffort(JsonArray options, BooleanSupplier current) {
        applyReasoningEffortIfNeeded(options, agentIsOnRequestedModel(options), current);
    }

    /**
     * Pushes the stored reasoning effort to a session that is already running — the live counterpart of the
     * effort half of {@link #applyInitialConfigOptionsIfNeeded}, which otherwise only runs once, when the
     * session is established. Same per-model support check and same clearing of a rejected session-sourced
     * value. Blocks until the agent answers, so it only ever runs on the config-operation queue
     * ({@link #applyReasoningEffortToLiveSessionAsync}, {@link #changeModelOnLiveSessionAsync}), where it
     * sees the options left by the latest confirmed model change. A session-sourced effort is cleared only
     * when the agent's model is the one that was asked for: if a model change failed, the options describe a
     * model the user did not pick, and judging the effort against them would discard a value the chosen model
     * may accept.
     */
    void applyReasoningEffortToLiveSession() {
        applyReasoningEffortToLiveSession(ALWAYS_CURRENT);
    }

    private void applyReasoningEffortToLiveSession(BooleanSupplier current) {
        JsonArray options = sessionConfigOptions;
        if (options == null) {
            return;
        }
        reconcileReasoningEffort(options, current);
    }

    /**
     * Identifies the running ACP session: the connection plus its session id. A task queued for one session
     * must not act on the next, so the queue captures this when a task is queued and compares it before the
     * task runs and after every request it makes. Package-private so a test can stand in for a real
     * connection.
     */
    Object currentSessionToken() {
        AcpConnection conn = connection;
        String sid = acpSessionId;
        return conn == null || sid == null ? null : new SessionToken(conn, sid);
    }

    private record SessionToken(AcpConnection connection, String acpSessionId) {

    }

    /**
     * True while the session that was running when this was created is still the running one. A task queued
     * when there was no session never runs.
     */
    private BooleanSupplier sessionGuard() {
        Object token = currentSessionToken();
        return () -> token != null && token.equals(currentSessionToken());
    }

    private static final BooleanSupplier ALWAYS_CURRENT = () -> true;

    /**
     * True when the agent's current model is the one this manager was asked to use, or when that cannot be
     * told (no requested model, or the agent reports no model option).
     */
    private boolean agentIsOnRequestedModel(JsonArray options) {
        String requested = model;
        if (requested == null || requested.isBlank()) {
            return true;
        }
        for (JsonElement el : options) {
            if (el.isJsonObject() && "model".equals(optionId(el.getAsJsonObject()))
                && el.getAsJsonObject().has(AcpJsonKeyEnum.CURRENT_VALUE.key())) {
                return requested.equals(el.getAsJsonObject().get(AcpJsonKeyEnum.CURRENT_VALUE.key()).getAsString());
            }
        }
        return true;
    }

    private static String optionId(JsonObject option) {
        return option.has(AcpJsonKeyEnum.ID.key()) ? option.get(AcpJsonKeyEnum.ID.key()).getAsString() : null;
    }

    /**
     * Queues {@link #applyReasoningEffortToLiveSession}. The effort pick comes from the EDT and the check
     * blocks on the agent, so it runs on the config-operation queue, behind any model change already waiting
     * for the agent: effort and model changes are applied strictly in the order they were made, and the
     * effort is judged against the options the agent returned for the model it ended up on.
     */
    public void applyReasoningEffortToLiveSessionAsync() {
        BooleanSupplier current = sessionGuard();
        configOperations.execute(() -> {
            if (current.getAsBoolean()) {
                applyReasoningEffortToLiveSession(current);
            }
        });
    }

    /**
     * Queues a model change on the running session followed by the effort check against the options that
     * change returned. Queued rather than sent directly so the two can never interleave with another effort
     * or model change. The wait happens on the queue's own thread: this is called from the EDT, and a model
     * switch's completion is delivered on the ACP connection's dispatch thread, where waiting for a second
     * response could never be answered.
     */
    public void changeModelOnLiveSessionAsync(String newModel) {
        BooleanSupplier current = sessionGuard();
        configOperations.execute(() -> {
            if (!current.getAsBoolean()) {
                return;
            }
            try {
                setConfigOption("model", newModel).get(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception e) {
                if (!current.getAsBoolean()) {
                    return;
                }
                LOG.log(Level.WARNING, "Grok rejected model=\"{0}\": {1}", new Object[]{newModel, e.getMessage()});
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "\"" + newModel + "\" was rejected by Grok for model"));
            }
            if (current.getAsBoolean()) {
                applyReasoningEffortToLiveSession(current);
            }
        });
    }

    /**
     * Returns once every config operation queued so far has finished, so tests can assert on the outcome
     * without sleeping.
     */
    void awaitConfigOperationsForTesting() throws Exception {
        configOperations.submit(() -> {
        }).get(10, TimeUnit.SECONDS);
    }

    private static ThreadPoolExecutor newConfigOperationQueue() {
        ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "grok-config-operations");
            thread.setDaemon(true);
            return thread;
        });
        queue.allowCoreThreadTimeOut(true);
        return queue;
    }

    private void applyReasoningEffortIfNeeded(JsonArray options, boolean mayClear, BooleanSupplier current) {
        String effort = reasoningEffort;
        if (effort != null && !effort.isBlank()) {
            boolean applied = applyConfigOptionIfNeeded(options, "reasoning_effort", effort, current);
            if (!applied && mayClear && current.getAsBoolean()) {
                clearRejectedReasoningEffort(effort);
            }
        }
    }

    /**
     * Clears {@code rejected} when it is a session-sourced value and is still what is stored; a newer pick
     * made while the agent was being asked is left alone. Returns whether it cleared.
     */
    boolean clearRejectedReasoningEffort(String rejected) {
        synchronized (effortFieldLock) {
            if (!reasoningEffortFromSession || !rejected.equals(reasoningEffort)) {
                return false;
            }
            reasoningEffort = null;
        }
        Runnable cb = onReasoningEffortCleared;
        if (cb != null) {
            cb.run();
        }
        return true;
    }

    /**
     * @return true if {@code value} is (or already was) in effect for {@code configId} — false means either
     *         the agent does not offer {@code configId} at all, or it does but does not accept {@code value}
     *         for the current model, so the caller can decide whether a session-sourced value should be
     *         cleared. {@code options} is the caller's own stable snapshot, not re-read here, so a session
     *         going away mid-method can never be mistaken for the agent itself rejecting {@code value}.
     */
    private boolean applyConfigOptionIfNeeded(JsonArray options, String configId, String value, BooleanSupplier current) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!current.getAsBoolean()) {
            return true;
        }
        String agentCurrent = null;
        List<String> available = new ArrayList<>();
        boolean optionExists = false;
        for (JsonElement el : options) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if (configId.equals(opt.has(AcpJsonKeyEnum.ID.key()) ? opt.get(AcpJsonKeyEnum.ID.key()).getAsString() : null)) {
                optionExists = true;
                if (opt.has(AcpJsonKeyEnum.CURRENT_VALUE.key())) {
                    agentCurrent = opt.get(AcpJsonKeyEnum.CURRENT_VALUE.key()).getAsString();
                }
                if (opt.has(AcpJsonKeyEnum.OPTIONS.key()) && opt.get(AcpJsonKeyEnum.OPTIONS.key()).isJsonArray()) {
                    for (JsonElement v : opt.getAsJsonArray(AcpJsonKeyEnum.OPTIONS.key())) {
                        if (v.isJsonObject() && v.getAsJsonObject().has(AcpJsonKeyEnum.VALUE.key())) {
                            available.add(v.getAsJsonObject().get(AcpJsonKeyEnum.VALUE.key()).getAsString());
                        }
                    }
                }
                break;
            }
        }
        if (!optionExists) {
            return false;
        }
        if (!available.isEmpty() && !available.contains(value)) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "\"" + value + "\" is not available for " + configId + "; using Grok's default"));
            return false;
        }
        if (value.equals(agentCurrent)) {
            return true;
        }
        try {
            JsonArray confirmed = setConfigOption(configId, value).get(30, TimeUnit.SECONDS);
            if (current.getAsBoolean()) {
                sessionConfigOptions = confirmed;
            }
        }
        catch (Exception e) {
            if (current.getAsBoolean()) {
                LOG.log(Level.WARNING, "Grok rejected {0}=\"{1}\": {2}", new Object[]{configId, value, e.getMessage()});
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "\"" + value + "\" was rejected by Grok for " + configId));
            }
        }
        return true;
    }

    /**
     * Safety net: a load whose ordered clear never ran (e.g. the connection it belonged to closed before
     * acp-notify got to it) must never silence a real turn — mirrors
     * OpenCodeAcpClientHandler.clearTextSuppression's role for the compaction route.
     */
    @Override
    protected void clearTurnStartState() {
        GrokAcpClientHandler handler = activeHandler;
        if (handler != null) {
            handler.clearTurnRefusals();
            handler.clearSuppressingSessionUpdatesForLoad();
        }
    }

    @Override
    protected void onNewTurnStarting() {
        lastPromptSentAtMillis = System.currentTimeMillis();
    }

    @Override
    protected void onTurnComplete(JsonObject result) {
        reportUsage(result);
    }

    /**
     * Grok reports usage once, in the {@code session/prompt} result's {@code _meta} (no streamed
     * {@code usage_update} session/update) — verified facts: {@code {inputTokens, outputTokens,
     * cachedReadTokens, reasoningTokens, totalTokens, modelId}}. The context-WINDOW size is not in that
     * payload; it comes from {@link #modelContextWindows}, read once at handshake time from
     * {@code models.availableModels[]._meta.totalContextTokens} and looked up by the turn's own
     * {@code modelId} so a model switch reports the new model's window, not the one Grok started with. 0 when
     * the model is not in that map — the info bar already treats a non-positive max as "keep whatever window
     * size I last knew", never as a reason to show zero.
     */
    void reportUsage(JsonObject result) {
        if (result == null || !result.has("_meta") || !result.get("_meta").isJsonObject()) {
            return;
        }
        JsonObject meta = result.getAsJsonObject("_meta");
        int totalTokens = meta.has("totalTokens") && meta.get("totalTokens").isJsonPrimitive() ? meta.get("totalTokens").getAsInt() : -1;
        if (totalTokens < 0) {
            return;
        }
        String modelId = meta.has("modelId") && meta.get("modelId").isJsonPrimitive() ? meta.get("modelId").getAsString() : model;
        Integer maxTokens = modelContextWindows.get(modelId);
        listener.onAiProcessEvent(new GrokTokenUsageEvent(totalTokens, maxTokens != null ? maxTokens : 0, modelId));
    }

    /**
     * Reads every known model's context-window size from {@code initialize}/{@code session/new}'s
     * {@code models.availableModels[]._meta.totalContextTokens} (Boss's handover: confirmed 256000 for
     * grok-4.7 against a live probe). The model-identifying field inside each entry is tried under several
     * plausible names ({@code id}, {@code modelId}, {@code model}, {@code name}) rather than one assumed
     * name, since the exact field was not independently verifiable from this seat — the probe transcript this
     * was specified from is outside this session's file-access scope. Returns an empty map, never null, when
     * neither response carries recognisable model metadata — a missing window degrades to 0
     * ({@link #reportUsage}), not an exception.
     */
    static Map<String, Integer> parseModelContextWindows(JsonObject... results) {
        Map<String, Integer> windows = new HashMap<>();
        for (JsonObject result : results) {
            if (result == null || !result.has("models") || !result.get("models").isJsonObject()) {
                continue;
            }
            JsonObject models = result.getAsJsonObject("models");
            if (!models.has("availableModels") || !models.get("availableModels").isJsonArray()) {
                continue;
            }
            for (JsonElement el : models.getAsJsonArray("availableModels")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject modelEntry = el.getAsJsonObject();
                String id = firstStringField(modelEntry, "id", "modelId", "model", "name");
                if (id == null || !modelEntry.has("_meta") || !modelEntry.get("_meta").isJsonObject()) {
                    continue;
                }
                JsonObject meta = modelEntry.getAsJsonObject("_meta");
                if (meta.has("totalContextTokens") && meta.get("totalContextTokens").isJsonPrimitive()) {
                    windows.put(id, meta.get("totalContextTokens").getAsInt());
                }
            }
        }
        return windows;
    }

    private static String firstStringField(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isString()) {
                return obj.get(key).getAsString();
            }
        }
        return null;
    }

    /**
     * Closes stdin (ending the ACP connection), waits {@link #SHUTDOWN_GRACE_MILLIS} for Grok to exit on its
     * own, then kills it. Verified by live probe: closing stdin alone does not make Grok exit.
     */
    @Override
    public synchronized void stop() {
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Grok stop: shutting session down (session={0}, turnInFlight={1}, connected={2})",
                    new Object[]{acpSessionId, processing, connection != null});
        }
        running = false;
        processing = false;
        cancelledByUser = true;
        resetMailInterruptHold();
        clearHandshakeTurn();
        clearActiveTurn();

        GrokAcpClientHandler h = activeHandler;
        activeHandler = null;
        AcpConnection conn = connection;
        connection = null;
        String sid = acpSessionId;
        acpSessionId = null;
        pendingAcpResumeId = null;
        Process proc = currentProcess;
        currentProcess = null;

        if (h != null) {
            h.cancelPendingPermissions();
        }
        if (conn != null && sid != null) {
            sendCancelNotification(conn, sid);
        }
        if (conn != null) {
            conn.close(); // closes stdin/stdout; Grok does not exit on this alone (verified by probe)
        }
        if (proc != null) {
            Thread killer = new Thread(() -> {
                try {
                    if (!proc.waitFor(shutdownGraceMillis(), TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly();
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    proc.destroyForcibly();
                }
            }, "grok-stop-reaper");
            killer.setDaemon(true);
            killer.start();
        }

        GrokAiSession sess = grokAiSession;
        grokAiSession = null;
        var reg = registrar;
        registrar = null;
        if (sess != null) {
            sess.dispose();
        }
        if (reg != null) {
            McpServerRegistry.deregister(reg);
        }
        File debugLog = grokDebugLogFile;
        grokDebugLogFile = null;
        if (debugLog != null) {
            debugLog.delete();
        }
        sessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigDir = null;
        sessionConfigOptions = null;
        modelContextWindows = Map.of();
        recentStderr.clear();
    }

}
