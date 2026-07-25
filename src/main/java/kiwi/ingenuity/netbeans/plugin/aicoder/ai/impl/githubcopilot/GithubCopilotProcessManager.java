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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Drives GitHub Copilot via a persistent SDK session (one CopilotClient +
 * CopilotSession per plugin AI session), replacing the previous one-shot
 * `copilot -p ... --output-format json` process-per-turn model. The persistent
 * session is what makes graceful mid-turn interrupt (session.abort()) and live
 * context-window usage (session.usage_info) possible — neither is exposed by
 * one-shot -p mode in CLI 1.0.70.
 */
public class GithubCopilotProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(GithubCopilotProcessManager.class.getName());

    static SessionConfig buildCreateConfig(String sessionId, String model,
            Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers) {
        return new SessionConfig()
                .setSessionId(sessionId)
                .setModel(model)
                .setExcludedTools(List.of("edit", "create"))
                .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                // An MCP server asking for OAuth cannot be serviced from here — we
                // have no browser flow, and our own server needs no auth. Cancel
                // rather than leave the request pending and hang the turn.
                .setOnMcpAuthRequest((request, invocation)
                        -> CompletableFuture.completedFuture(McpAuthResult.cancelled()))
                .setMcpServers(mcpServers);
    }

    static ResumeSessionConfig buildResumeConfig(String model,
            Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers) {
        return new ResumeSessionConfig()
                .setModel(model)
                .setExcludedTools(List.of("edit", "create"))
                .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
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

    public GithubCopilotProcessManager(AiProcessEventListener listener) {
        super(listener);
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
            mcpReady = McpServerRegistry.register(reg).get(2, TimeUnit.MINUTES);
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
                        listener.onAiProcessEvent(new GithubCopilotQuotaEvent(
                                quota.unlimited(), quota.usedRequests(), quota.entitlementRequests(),
                                quota.remainingPercentage(), quota.resetDate()));
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
            client.start().get(2, TimeUnit.MINUTES);
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
                listener.onAiProcessEvent(new GithubCopilotQuotaEvent(
                        quota.unlimited(), quota.usedRequests(), quota.entitlementRequests(),
                        quota.remainingPercentage(), quota.resetDate()));
            }
        });
    }

    /**
     * Resume an existing Copilot session only when it is actually present in
     * the SDK session store; otherwise create a fresh one under the stable
     * plugin session id so later reopen/resume uses the same id without a noisy
     * "session not found" exception on first start.
     */
    private CopilotSession createOrResumeSession(CopilotClient client, String model)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers = buildMcpServers();
        if (!storedSessionExists(client, copilotSessionId)) {
            return createSession(client, model, mcpServers, copilotSessionId);
        }
        try {
            return client.resumeSession(copilotSessionId, buildResumeConfig(model, mcpServers)).get(2, TimeUnit.MINUTES);
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
        List<SessionMetadata> sessions = client.listSessions().get(2, TimeUnit.MINUTES);
        return sessionListContains(sessions, targetSessionId);
    }

    private CopilotSession createSession(CopilotClient client, String model,
            Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers, String targetSessionId)
            throws ExecutionException, InterruptedException, TimeoutException {
        return client.createSession(buildCreateConfig(targetSessionId, model, mcpServers)).get(2, TimeUnit.MINUTES);
    }

    private Map<String, com.github.copilot.rpc.McpServerConfig> buildMcpServers() {
        McpHookServer server = McpServerRegistry.getServer();
        if (server == null || sessionId == null) {
            return Map.of();
        }
        String endpoint = server.getBaseUrl() + "/mcp/" + AiTypeEnum.GitHubCoPilot.key();
        McpHttpServerConfig mcpServer = new McpHttpServerConfig().setUrl(endpoint).setTools(List.of("*"));
        return Map.of(StringConst.PLUGIN_ID, mcpServer);
    }

    /**
     * Maps a session-start failure to the same fatal-error events the old
     * process-based flow reported after a nonzero exit + stderr grep — now read
     * directly off the failed future's message instead of joined stderr lines.
     * Message text confirmed live against the real CLI (not guessed): "Not
     * authenticated..." and "...is not available." respectively.
     */
    private void handleSessionStartFailure(Exception e) {
        running = false;
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
            GithubCopilotPluginSettings.setModel("auto");
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Model was not available for your account — switched to 'auto'. Please resend your message."));
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                StatusMessageUtil.formatSendFailed(msg)));
        LOG.log(Level.WARNING, "GitHub Copilot session start failed", e);
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing || copilotSession == null) {
            return;
        }
        cancelledByUser = false;
        processing = true;
        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        CopilotSession session = copilotSession;
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.WARNING, "copilot prompt: {0}", text);
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
     * Graceful interrupt: Cancel aborts the current turn without tearing down
     * the session (session.abort()) — previously this had to kill the whole OS
     * process since one-shot -p had no in-band signal. Mail interjects the
     * inter-AI mail notification into the running turn via immediate-mode send
     * instead of killing anything — Mail was always meant to interrupt and
     * inject, never to kill (confirmed with Chris).
     */
    @Override
    public void interrupt(InterruptTypeEnum type) {
        CopilotSession session = copilotSession;
        if (session == null) {
            return;
        }
        switch (type) {
            case Cancel -> {
                cancelledByUser = true;
                session.abort();
                processing = false;
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
            }
            case Mail -> {
                if (processing) {
                    com.github.copilot.rpc.MessageOptions options = new com.github.copilot.rpc.MessageOptions()
                            .setPrompt("[inbox] You have a new message — check it when convenient.")
                            .setMode("immediate");
                    session.send(options);
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
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
