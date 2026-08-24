package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.McpAuthResult;
import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import java.io.File;
import java.util.List;
import java.util.Map;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotFatalErrorEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotQuotaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.session.GithubCopilotAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Drives GitHub Copilot via a persistent SDK session (one CopilotClient + CopilotSession per plugin AI session),
 * replacing the previous one-shot `copilot -p ... --output-format json` process-per-turn model. The persistent session
 * is what makes graceful mid-turn interrupt (session.abort()) and live context-window usage (session.usage_info)
 * possible — neither is exposed by one-shot -p mode in CLI 1.0.70.
 */
public class GithubCopilotProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(GithubCopilotProcessManager.class.getName());
    /**
     * The sdk sends a reset date but it is incorrect, set to true when the sdk returns it correctly.
     */
    private static final boolean ENABLE_RESET_DATE = false;

    /**
     * Copilot's own tools that this plugin withholds, because an IDE-aware equivalent exists and routes through
     * NetBeans' view of the code: {@code edit}/{@code create} are covered by ApplyEdit/WriteFile via the diff panel,
     * {@code glob} by SearchTypes/SearchInFiles/GetProjectStructure, and {@code view} by GetFileContent.
     *
     * <p>
     * Withholding beats denying at the permission gate. The gate still refuses these (kind {@code read}), but only
     * after Copilot has spent a tool call on one, and every refusal posts a system message — a short survey produced
     * six. Excluded, they are never offered, so there is nothing to refuse and nothing to announce, and the "Internal
     * Command" notice stays rare enough to be worth reading.
     *
     * <p>
     * {@code bash} is deliberately NOT excluded: running commands is the one capability the plugin's tools do not
     * cover, so it stays available and is gated by an explicit confirmation instead (kind {@code shell}).
     */
    private static final List<String> EXCLUDED_NATIVE_TOOLS = List.of("edit", "create", "glob", "view");

    static SessionConfig buildCreateConfig(String sessionId, String model,
            Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers, PermissionHandler permissionHandler) {
        return new SessionConfig()
                .setSessionId(sessionId)
                .setModel(model)
                .setExcludedTools(EXCLUDED_NATIVE_TOOLS)
                .setOnPermissionRequest(permissionHandler)
                // An MCP server asking for OAuth cannot be serviced from here — we
                // have no browser flow, and our own server needs no auth. Cancel
                // rather than leave the request pending and hang the turn.
                .setOnMcpAuthRequest((request, invocation)
                        -> CompletableFuture.completedFuture(McpAuthResult.cancelled()))
                .setMcpServers(mcpServers);
    }

    static ResumeSessionConfig buildResumeConfig(String model,
            Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers, PermissionHandler permissionHandler) {
        return new ResumeSessionConfig()
                .setModel(model)
                .setExcludedTools(EXCLUDED_NATIVE_TOOLS)
                .setOnPermissionRequest(permissionHandler)
                .setMcpServers(mcpServers);
    }

    static boolean sessionListContains(List<SessionMetadata> sessions, String targetSessionId) {
        if (sessions == null || targetSessionId == null || targetSessionId.isBlank()) {
            return false;
        }
        return sessions.stream().map(SessionMetadata::getSessionId).anyMatch(targetSessionId::equals);
    }

    static boolean isSessionNotFoundFailure(Throwable failure) {
        String message = deepestMessage(failure);
        return message != null && message.toLowerCase().contains("session not found");
    }

    private static boolean isCorruptedSessionFailure(Throwable failure) {
        String message = deepestMessage(failure);
        return message != null && (message.contains("could not be loaded") || message.contains("corrupted"));
    }

    private static String deepestMessage(Throwable failure) {
        String lastMessage = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                lastMessage = current.getMessage();
            }
        }
        return lastMessage;
    }

    // Copilot's own resumable session id. Normally equals the plugin session id,
    // but is replaced with a fresh UUID after a corrupted resume so MCP routing
    // (which keys on the plugin session id) is unaffected.
    private volatile String copilotSessionId = null;
    private volatile boolean sessionCorrupted = false;
    private volatile GithubCopilotMcpRegistrar registrar = null;
    private GithubCopilotAiSession copilotAiSession = null;
    private GithubCopilotSessionEventBridge eventBridge = null;

    private CopilotClient client = null;
    private CopilotSession copilotSession = null;
    private volatile GithubCopilotPermissionHandler permissionHandler = null;
    private volatile Consumer<String> onModelFallback;

    public GithubCopilotProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    public void setOnModelFallback(Consumer<String> onModelFallback) {
        this.onModelFallback = onModelFallback;
    }

    @Override
    public synchronized void start(String executablePath, String model) {
        stop();
        this.executablePath = executablePath;
        this.model = model;

        if (!GithubCopilotExecutableLocator.isExecutableFile(executablePath)) {
            running = false;
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatStartFailed("copilot executable not found at " + executablePath)));
            listener.onAiProcessEvent(new GithubCopilotFatalErrorEvent(
                    "EXECUTABLE_NOT_FOUND", "GitHub Copilot CLI not found"));
            return;
        }
        if (currentSession == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSessionNotConfigured()));
            return;
        }
        sessionId = currentSession.id();
        if (sessionCorrupted) {
            copilotSessionId = java.util.UUID.randomUUID().toString();
            sessionCorrupted = false;
        }
        else {
            copilotSessionId = currentSession.id();
        }

        if (registrar != null) {
            McpServerRegistry.deregister(registrar);
            registrar = null;
        }
        GithubCopilotMcpRegistrar reg = new GithubCopilotMcpRegistrar(sessionId);
        boolean mcpReady;
        try {
            mcpReady = McpServerRegistry.register(reg).get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mcpReady = false;
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "MCP registration failed for session " + sessionId, e);
            mcpReady = false;
        }
        if (!mcpReady) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatMcpSetupFailed()));
            return;
        }
        registrar = reg;
        copilotAiSession = new GithubCopilotAiSession(currentSession, listener);
        // session.send() is fire-and-forget: its future resolves as soon as the
        // message is queued, long before the assistant finishes responding. The
        // real end-of-turn signal is the bridge's TurnCompleteEvent, so clear
        // `processing` there (before forwarding to the UI) — mirrors exactly how
        // the old one-shot-process code cleared it on TurnCompleteEvent rather
        // than on process exit.
        AiProcessEventListener turnAwareListener = event -> {
            boolean isTurnComplete = event instanceof kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
            boolean shouldFire;
            synchronized (GithubCopilotProcessManager.this) {
                if (isTurnComplete) {
                    processing = false;
                }
                shouldFire = !cancelledByUser;
            }
            if (shouldFire) {
                listener.onAiProcessEvent(event);
            }
            if (isTurnComplete) {
                GithubCopilotQuotaService.getQuotaAsync(executablePath, quota -> {
                    if (quota != null) {
                        GithubCopilotQuotaEvent quotaEvent = new GithubCopilotQuotaEvent(
                                quota.unlimited(), quota.usedRequests(), quota.entitlementRequests(),
                                quota.remainingPercentage(), quota.resetDate(), ENABLE_RESET_DATE);
                        listener.onAiProcessEvent(quotaEvent);
                        GithubCopilotAiImplementation.publishQuota(quotaEvent);
                    }
                });
            }
        };
        eventBridge = new GithubCopilotSessionEventBridge(turnAwareListener);
        eventBridge.setOnError(msg -> {
            synchronized (GithubCopilotProcessManager.this) {
                processing = false;
            }
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.EXITED,
                    "GitHub Copilot: " + msg));
        });
        eventBridge.setSessionNameSupplier(() -> {
            AiSession s = currentSession;
            return s == null ? null : s.name();
        });

        CopilotClientOptions opts = new CopilotClientOptions();
        opts.setCliPath(executablePath);
        client = new CopilotClient(opts);
        try {
            client.start().get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
            copilotSession = createOrResumeSession(client, model);
            copilotSessionId = copilotSession.getSessionId();
            eventBridge.attach(copilotSession);
        }
        catch (Exception e) {
            handleSessionStartFailure(e);
            return;
        }

        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY, StatusMessageUtil.formatReady("GitHub Copilot")));
        listener.onAiProcessEvent(new GithubCopilotFatalErrorEvent(null, null));
        GithubCopilotQuotaService.getQuotaAsync(executablePath, quota -> {
            if (quota != null) {
                GithubCopilotQuotaEvent quotaEvent = new GithubCopilotQuotaEvent(
                        quota.unlimited(), quota.usedRequests(), quota.entitlementRequests(),
                        quota.remainingPercentage(), quota.resetDate(), false);
                listener.onAiProcessEvent(quotaEvent);
                GithubCopilotAiImplementation.publishQuota(quotaEvent);
            }
        });
    }

    /**
     * Resume an existing Copilot session only when it is actually present in the SDK session store; otherwise create a
     * fresh one under the stable plugin session id so later reopen/resume uses the same id without a noisy "session not
     * found" exception on first start.
     */
    private CopilotSession createOrResumeSession(CopilotClient client, String model)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers = buildMcpServers();
        if (!storedSessionExists(client, copilotSessionId)) {
            return createSession(client, model, mcpServers, copilotSessionId);
        }
        try {
            GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(listener, sessionId);
            permissionHandler = handler;
            return client.resumeSession(copilotSessionId, buildResumeConfig(model, mcpServers, handler))
                    .get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
        }
        catch (ExecutionException resumeFailure) {
            if (isCorruptedSessionFailure(resumeFailure)) {
                sessionCorrupted = true;
                copilotSessionId = java.util.UUID.randomUUID().toString();
            }
            else if (!isSessionNotFoundFailure(resumeFailure)) {
                LOG.log(Level.INFO, "Resume failed for " + copilotSessionId + ", creating instead", resumeFailure);
            }
            return createSession(client, model, mcpServers, copilotSessionId);
        }
    }

    private boolean storedSessionExists(CopilotClient client, String targetSessionId)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return false;
        }
        List<SessionMetadata> sessions = client.listSessions().get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
        return sessionListContains(sessions, targetSessionId);
    }

    private CopilotSession createSession(CopilotClient client, String model,
            Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers, String targetSessionId)
            throws ExecutionException, InterruptedException, TimeoutException {
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(listener, sessionId);
        permissionHandler = handler;
        return client.createSession(buildCreateConfig(targetSessionId, model, mcpServers, handler))
                .get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
    }

    private Map<String, com.github.copilot.rpc.McpServerConfig> buildMcpServers() {
        String endpoint = McpServerRegistry.endpointUrlFor(AiTypeEnum.GitHubCoPilot);
        if (endpoint == null || sessionId == null) {
            return Map.of();
        }
        McpHttpServerConfig mcpServer = new McpHttpServerConfig()
                .setUrl(endpoint)
                .setTools(List.of("*"))
                // McpServerConfig#setTimeout expects milliseconds, matching this enum.
                .setTimeout(Math.toIntExact(GithubCopilotTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis()));
        return Map.of(StringConst.PLUGIN_ID, mcpServer);
    }

    /**
     * Maps a session-start failure to the same fatal-error events the old process-based flow reported after a nonzero
     * exit + stderr grep — now read directly off the failed future's message instead of joined stderr lines. Message
     * text confirmed live against the real CLI (not guessed): "Not authenticated..." and "...is not available."
     * respectively.
     */
    private void handleSessionStartFailure(Exception e) {
        running = false;
        // Best-effort teardown of whatever start() set up before failing: a
        // started CopilotClient owns a spawned `copilot --server` OS process,
        // and nothing else would ever close it when this start never completes.
        // Mirrors stop()'s ordering (dispose AI session -> deregister MCP ->
        // cancel pending dialogs -> close client) with every step guarded so
        // cleanup cannot mask the original failure.
        GithubCopilotAiSession sess = copilotAiSession;
        copilotAiSession = null;
        if (sess != null) {
            try {
                sess.dispose();
            }
            catch (Exception disposeEx) {
                LOG.log(Level.FINE, "Ignoring error disposing AI session after failed start", disposeEx);
            }
        }
        GithubCopilotMcpRegistrar reg = registrar;
        registrar = null;
        if (reg != null) {
            try {
                McpServerRegistry.deregister(reg);
            }
            catch (Exception deregEx) {
                LOG.log(Level.WARNING, "Could not deregister MCP endpoint after failed start", deregEx);
            }
        }
        GithubCopilotPermissionHandler permHandler = permissionHandler;
        permissionHandler = null;
        if (permHandler != null) {
            try {
                permHandler.cancelPendingPermissions();
            }
            catch (Exception cancelEx) {
                LOG.log(Level.FINE, "Ignoring error cancelling pending permissions after failed start", cancelEx);
            }
        }
        CopilotSession staleSession = copilotSession;
        copilotSession = null;
        eventBridge = null;
        if (staleSession != null) {
            try {
                staleSession.close();
            }
            catch (Exception closeEx) {
                LOG.log(Level.FINE, "Ignoring error closing Copilot session after failed start", closeEx);
            }
        }
        CopilotClient staleClient = client;
        client = null;
        if (staleClient != null) {
            try {
                staleClient.close();
            }
            catch (Exception closeEx) {
                LOG.log(Level.FINE, "Ignoring error closing Copilot client after failed start", closeEx);
            }
        }
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String lower = msg.toLowerCase();
        if (msg.contains("could not be loaded") || msg.contains("corrupted")) {
            sessionCorrupted = true;
        }
        if (lower.contains("not authenticat") || lower.contains("unauthorized")) {
            listener.onAiProcessEvent(new GithubCopilotFatalErrorEvent(
                    "AUTHENTICATION_REQUIRED", "Not authenticated — run `copilot login` in a terminal"));
        }
        else if (lower.contains("is not available") && model != null && !"auto".equalsIgnoreCase(model)) {
            model = "auto";
            // Report the fallback so the implementation can persist it and refresh the info bar.
            Consumer<String> cb = onModelFallback;
            if (cb != null) {
                cb.accept(model);
            }
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Model was not available for your account — switched to 'auto'. Please resend your message."));
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                StatusMessageUtil.formatSendFailed(msg)));
        LOG.log(Level.WARNING, "GitHub Copilot session start failed", e);
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing) {
            return;
        }

        if (copilotSession == null) {
            // recycleForModelChange() closed the old session after a model switch.
            // Re-establishing costs two blocking RPCs (listSessions + resume/create)
            // and sendPrompt runs on the EDT, so hand off to a background thread and
            // send from there once the session is up. Mark the turn busy first so the
            // UI shows "thinking" and a second send cannot race the re-establish.
            cancelledByUser = false;
            processing = true;
            if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
                sessionWorkingDir = workingDir;
            }
            new Thread(() -> reestablishAndSend(text, workingDir, projectDirs),
                    "copilot-model-recycle").start();
            return;
        }

        if (copilotSession == null) {
            return;
        }

        cancelledByUser = false;
        processing = true;
        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        CopilotSession session = copilotSession;
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "copilot prompt: {0}", text);
        }
        // session.send() only resolves once the message is queued (fire-and-forget) —
        // it does NOT mean the turn finished. Only handle the failure-to-queue case
        // here; the success path's `processing` reset happens on TurnCompleteEvent
        // in the turnAwareListener built in start().
        session.send(text).whenComplete((messageId, err) -> {
            if (err != null) {
                synchronized (GithubCopilotProcessManager.this) {
                    processing = false;
                }
                if (!cancelledByUser) {
                    LOG.log(Level.WARNING, "GitHub Copilot send failed", err);
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                            StatusMessageUtil.formatSendFailed(err.getMessage())));
                }
            }
        });
    }

    /**
     * Re-establishes the CopilotSession after a model change and then sends the queued prompt. Runs on a background
     * thread: createOrResumeSession() blocks on RPC for up to two minutes per call, and every sendPrompt() caller is on
     * the EDT. The RPC deliberately happens OUTSIDE the monitor so a concurrent stop() (e.g. the user closing the tab
     * mid-recycle) is not blocked by it; only publishing the new session takes the lock.
     */
    private void reestablishAndSend(String text, File workingDir, List<File> projectDirs) {
        CopilotSession created = null;
        try {
            created = createOrResumeSession(client, model);
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to re-establish Copilot session after model change", e);
        }
        synchronized (this) {
            processing = false;
            if (created == null || !running) {
                if (created != null) {
                    created.close();
                }
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(
                                "could not re-establish the session after the model change")));
                return;
            }
            copilotSession = created;
            copilotSessionId = created.getSessionId();
            if (eventBridge != null) {
                eventBridge.attach(created);
            }
        }
        // Re-enter the normal path now that a session exists: it re-arms
        // `processing` and runs the usual send/failure handling.
        sendPrompt(text, workingDir, projectDirs);
    }

    /**
     * Graceful interrupt: Cancel aborts the current turn without tearing down the session (session.abort()) —
     * previously this had to kill the whole OS process since one-shot -p had no in-band signal. Mail interjects the
     * inter-AI mail notification into the running turn via immediate-mode send instead of killing anything — Mail was
     * always meant to interrupt and inject, never to kill (confirmed with Chris).
     */
    @Override
    public void interrupt(InterruptTypeEnum type) {
        CopilotSession session = copilotSession;
        if (session == null) {
            // "Stop did nothing because nothing was running" and "Stop ran but
            // output kept coming" are indistinguishable after the fact — this
            // branch is the first of those. A silent no-op is what let Codex's
            // equivalent Mail drop go unnoticed for so long, so both types are
            // logged here, not just Cancel.
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "GitHub Copilot interrupt: IGNORED, no session (type={0}, session={1})",
                        new Object[]{type, sessionId});
            }
            return;
        }
        switch (type) {
            case Cancel -> {
                // Stamping the moment the user actually pressed Stop is the only way
                // to measure the wind-down tail afterwards: without it, "it carried
                // on after I stopped it" cannot be told apart from a normal
                // wind-down, and the agent's own log gives no click time to compare
                // against. Note: session.abort() is called here unconditionally
                // (this class has no processing-gated early return like the other
                // AI types), so turnInFlight is logged rather than gating on it.
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "GitHub Copilot interrupt: user pressed Stop (session={0}, turnInFlight={1})",
                            new Object[]{sessionId, processing});
                }
                cancelledByUser = true;
                // Cancel any outstanding permission dialog before session.abort(), so
                // Copilot receives the permission reply (userNotAvailable) before the
                // abort — mirrors OpenCodeAiProcessManager/CodexAiProcessManager
                // interrupt()'s ordering.
                GithubCopilotPermissionHandler handler = permissionHandler;
                if (handler != null) {
                    handler.cancelPendingPermissions();
                }
                session.abort();
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "GitHub Copilot interrupt: session.abort() sent (session={0})", sessionId);
                }
                synchronized (GithubCopilotProcessManager.this) {
                    processing = false;
                }
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
            }
            case Mail -> {
                if (processing) {
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "GitHub Copilot interrupt: Mail received, injecting notice (session={0})",
                                sessionId);
                    }
                    com.github.copilot.rpc.MessageOptions options = new com.github.copilot.rpc.MessageOptions()
                            .setPrompt("[inbox] You have a new message — check your inbox NOW.")
                            .setMode("immediate");
                    session.send(options);
                }
                else if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO,
                            "GitHub Copilot interrupt: Mail IGNORED, no turn in flight — message will arrive via "
                            + "normal inbox flush (session={0})", sessionId);
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        // Logged before the state is torn down, so the record says what was
        // actually in flight at the moment of the stop rather than the
        // cleared-out aftermath.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "GitHub Copilot stop: shutting session down (session={0}, turnInFlight={1}, sessionAlive={2})",
                    new Object[]{sessionId, processing, copilotSession != null});
        }
        running = false;
        processing = false;
        cancelledByUser = true;
        GithubCopilotAiSession sess = copilotAiSession;
        copilotAiSession = null;
        if (sess != null) {
            sess.dispose();
        }
        GithubCopilotMcpRegistrar reg = registrar;
        registrar = null;
        if (reg != null) {
            McpServerRegistry.deregister(reg);
        }
        // Complete any outstanding permission dialog exceptionally so its .handle()
        // continuation fires and replies userNotAvailable() to Copilot — otherwise a
        // stop() while awaiting approval leaves the dialog up and the turn wedged.
        GithubCopilotPermissionHandler permHandler = permissionHandler;
        permissionHandler = null;
        if (permHandler != null) {
            permHandler.cancelPendingPermissions();
        }
        if (client != null) {
            client.close();
        }
        client = null;
        copilotSession = null;
        eventBridge = null;
        sessionId = null;
        copilotSessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigDir = null;
    }

    public synchronized void recycleForModelChange() {
        if (!running || processing) {
            return;
        }
        CopilotSession oldSession = copilotSession;
        copilotSession = null;
        if (oldSession != null) {
            oldSession.close();
        }
    }

    // Called from the EDT (history load applies the stored session id; the
    // diff/tool-use path checks MCP state). Deliberately NOT synchronized:
    // start() holds this manager's monitor for seconds (copilot --server
    // spawn + MCP registration + session handshake), and sharing the monitor
    // here froze the NetBeans UI whenever a tab opened while a start was in
    // flight. All fields touched are volatile, so visibility is preserved
    // without the lock.
    @Override
    public void resumeSession(String existingSessionId) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return;
        }
        copilotSessionId = existingSessionId;
        sessionWorkingDir = null;
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }

    public void onTabActivated() {
    }
}
