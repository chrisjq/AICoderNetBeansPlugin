package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.rpc.SessionHistoryCompactParams;
import com.github.copilot.generated.rpc.SessionHistoryCompactResult;
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
import java.util.Objects;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotFatalErrorEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotModelFallbackEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotQuotaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotReasoningEffortClearedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.session.GithubCopilotAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Drives GitHub Copilot via a persistent SDK session (one CopilotClient + CopilotSession per plugin AI
 * session), replacing the previous one-shot `copilot -p ... --output-format json` process-per-turn model. The
 * persistent session is what makes graceful mid-turn interrupt (session.abort()) and live context-window
 * usage (session.usage_info) possible — neither is exposed by one-shot -p mode in CLI 1.0.70.
 */
public class GithubCopilotProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(GithubCopilotProcessManager.class.getName());
    /**
     * The sdk sends a reset date but it is incorrect, set to true when the sdk returns it correctly.
     */
    private static final boolean ENABLE_RESET_DATE = false;

    /**
     * Copilot's own tools that this plugin withholds, because an IDE-aware equivalent exists and routes
     * through NetBeans' view of the code: {@code edit}/{@code create} are covered by ApplyEdit/WriteFile via
     * the diff panel, {@code glob} by SearchTypes/SearchInFiles/GetProjectStructure, and {@code view} by
     * GetFileContent.
     *
     * <p>
     * Withholding beats denying at the permission gate. The gate still refuses these (kind {@code read}), but
     * only after Copilot has spent a tool call on one, and every refusal posts a system message — a short
     * survey produced six. Excluded, they are never offered, so there is nothing to refuse and nothing to
     * announce, and the "Internal Command" notice stays rare enough to be worth reading.
     *
     * <p>
     * {@code bash} is deliberately NOT excluded: running commands is the one capability the plugin's tools do
     * not cover, so it stays available and is gated by an explicit confirmation instead (kind {@code shell}).
     */
    private static final List<String> EXCLUDED_NATIVE_TOOLS = List.of("edit", "create", "glob", "view");

    static SessionConfig buildCreateConfig(String sessionId, String model,
                                           Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers, PermissionHandler permissionHandler,
                                           String reasoningEffort) {
        SessionConfig config = new SessionConfig()
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
        // Only set when non-blank: an unset/cleared effort must be omitted entirely, never sent as null or "".
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            config.setReasoningEffort(reasoningEffort);
        }
        return config;
    }

    static ResumeSessionConfig buildResumeConfig(String model,
                                                 Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers, PermissionHandler permissionHandler,
                                                 String reasoningEffort) {
        ResumeSessionConfig config = new ResumeSessionConfig()
                .setModel(model)
                .setExcludedTools(EXCLUDED_NATIVE_TOOLS)
                .setOnPermissionRequest(permissionHandler)
                .setMcpServers(mcpServers);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            config.setReasoningEffort(reasoningEffort);
        }
        return config;
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
    /**
     * Set when {@link #handleRuntimeError} closes a running turn as FAILED. The SDK still emits that turn's
     * trailing {@code session.idle}, which would otherwise surface as a {@link TurnCompleteEvent} and
     * re-green the tab — and, if the user sent again in between, unlock the NEW turn. Consumed by exactly one
     * idle and re-armed only by the next {@link #sendPrompt} that actually starts a turn.
     */
    private volatile boolean turnClosedByFailure = false;

    private CopilotClient client = null;
    private CopilotSession copilotSession = null;
    private volatile GithubCopilotPermissionHandler permissionHandler = null;
    private volatile Consumer<String> onModelFallback;
    /**
     * {@code null} means "omit the setting — use the model's own default". Applied where the session is
     * constructed (eagerly, inside {@link #start}, unlike pi's lazily-spawned session) — see
     * {@link #resolveValidatedReasoningEffort}.
     */
    private volatile String reasoningEffort = null;
    /**
     * Whether {@link #reasoningEffort} is a value pinned in the session's own settings (true) or one
     * inherited from the global default at session start (false). Only a session-pinned value may be cleared,
     * persisted as cleared, and reported with an INFO event when the model does not support it; a
     * global-sourced value must be silently omitted for this session (global untouched, nothing written into
     * the session, no INFO), and kept in place so a later model that does support it still receives it.
     */
    private volatile boolean reasoningEffortFromSession = true;
    /**
     * Notified when {@link #resolveValidatedReasoningEffort} clears {@link #reasoningEffort} because the
     * model does not support it. This manager has no session-settings/host reference of its own (only
     * {@code GithubCopilotAiImplementation} does), so clearing the in-memory field here is not enough —
     * without this callback the stored session value would never be persisted-cleared, and every subsequent
     * start would re-read the same stale value and fire the INFO event again. Mirrors
     * {@link #onModelFallback}'s callback shape. Invoked only for session-pinned values (rule 3a); never for
     * a global-sourced value.
     */
    private volatile Runnable onReasoningEffortCleared;
    /**
     * The model and stored effort the live {@link #copilotSession} was opened with, valid only while
     * {@link #launchRecorded}. {@link #sendPrompt} compares them with the current ones, so a model or effort
     * change made after the session opened takes effect on the next turn whether or not the background
     * {@link #recycleForModelChange} got there first.
     */
    private volatile String launchedModel;
    private volatile String launchedEffort;
    private volatile boolean launchRecorded;

    public GithubCopilotProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    public void setOnModelFallback(Consumer<String> onModelFallback) {
        this.onModelFallback = onModelFallback;
    }

    public void setOnReasoningEffortCleared(Runnable onReasoningEffortCleared) {
        this.onReasoningEffortCleared = onReasoningEffortCleared;
    }

    public void setReasoningEffort(String reasoningEffort) {
        setReasoningEffort(reasoningEffort, true);
    }

    /**
     * Sets the reasoning effort together with its provenance. {@code fromSession} is true when the value
     * came from the session's own settings, false when it was inherited from the global
     * default: only a session-pinned value may be cleared, persisted and reported (INFO) when unsupported — a
     * global-sourced one is silently omitted for this session instead, since the global belongs to the user
     * and every other session.
     */
    public void setReasoningEffort(String reasoningEffort, boolean fromSession) {
        this.reasoningEffort = reasoningEffort;
        this.reasoningEffortFromSession = fromSession;
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
        eventBridge = createEventBridge(createTurnAwareListener());

        CopilotClientOptions opts = new CopilotClientOptions();
        opts.setCliPath(executablePath);
        client = new CopilotClient(opts);
        try {
            client.start().get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
            CreatedSession created = createOrResumeSession(client, model);
            copilotSession = created.session();
            recordLaunched(created);
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
                GithubCopilotAiImplementation.publishQuota(quotaEvent);
            }
        });
    }

    /**
     * The listener the session bridge forwards through: it owns the turn bookkeeping the raw {@code listener}
     * must not see — clearing {@code processing} on the SDK's end-of-turn signal, and deciding whether a
     * {@link TurnCompleteEvent} is a real completion or must be swallowed.
     *
     * <p>
     * Two idles are swallowed: the trailing idle of a turn already closed by a runtime FAILURE (#2 —
     * otherwise it re-greens the tab, and unlocks a new turn the user has since sent), and any idle a manual
     * compaction emits while it is in flight (#6 — a compaction is non-turn work, so its idle is not a turn
     * completion). Extracted from {@code start()} so both rules can be unit-tested without a live CLI.
     * Package-private: production callers only.
     */
    AiProcessEventListener createTurnAwareListener() {
        return event -> {
            boolean isTurnComplete = event instanceof TurnCompleteEvent;
            boolean shouldFire;
            boolean swallowedIdle = false;
            synchronized (GithubCopilotProcessManager.this) {
                if (isTurnComplete) {
                    boolean wasProcessing = processing;
                    processing = false;
                    swallowedIdle = turnClosedByFailure || (isWorkInFlight() && !wasProcessing);
                    if (swallowedIdle) {
                        turnClosedByFailure = false;
                    }
                    shouldFire = !swallowedIdle && !cancelledByUser;
                }
                else {
                    shouldFire = !cancelledByUser;
                }
            }
            if (shouldFire) {
                listener.onAiProcessEvent(event);
            }
            if (isTurnComplete && !swallowedIdle) {
                GithubCopilotQuotaService.getQuotaAsync(executablePath, quota -> {
                    if (quota != null) {
                        GithubCopilotQuotaEvent quotaEvent = new GithubCopilotQuotaEvent(
                                quota.unlimited(), quota.usedRequests(), quota.entitlementRequests(),
                                quota.remainingPercentage(), quota.resetDate(), ENABLE_RESET_DATE);
                        GithubCopilotAiImplementation.publishQuota(quotaEvent);
                    }
                });
            }
        };
    }

    /**
     * Builds and wires the session bridge for a turn-aware listener. Extracted from {@code start()} so a unit
     * test can drive a real SDK event through {@code bridge.onError -> handleRuntimeError} and verify the
     * wiring, rather than calling {@code handleRuntimeError} directly (which would pass even with the wiring
     * reverted). Package-private: production callers only.
     */
    GithubCopilotSessionEventBridge createEventBridge(AiProcessEventListener turnAwareListener) {
        GithubCopilotSessionEventBridge bridge = new GithubCopilotSessionEventBridge(turnAwareListener);
        bridge.setOnError(this::handleRuntimeError);
        bridge.setSessionNameSupplier(() -> {
            AiSession s = currentSession;
            return s == null ? null : s.name();
        });
        return bridge;
    }

    /**
     * Resume an existing Copilot session only when it is actually present in the SDK session store; otherwise
     * create a fresh one under the stable plugin session id so later reopen/resume uses the same id without a
     * noisy "session not found" exception on first start.
     */
    private CreatedSession createOrResumeSession(CopilotClient client, String model)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers = buildMcpServers();
        // Resolved once per call (not once per builder call below) so a stored-but-unsupported value is cleared
        // with exactly one INFO event even when the resume attempt below falls through to createSession.
        String validatedEffort = resolveValidatedReasoningEffort(model);
        // The stored value is captured after validation (which may clear it) and before the RPCs, so a change
        // made while the session is being opened still reads as different from what it was opened with.
        String storedEffort = reasoningEffort;
        return new CreatedSession(openSession(client, model, mcpServers, validatedEffort), model, storedEffort);
    }

    /**
     * A freshly opened session with the model and the stored reasoning effort in force when it was opened.
     * The
     * stored value is recorded rather than the validated one: it is what {@link #setReasoningEffort} changes,
     * so comparing it detects exactly a user change, and a value the model does not support cannot look like
     * a
     * change on every turn.
     */
    private record CreatedSession(CopilotSession session, String model, String effort) {

    }

    private void recordLaunched(CreatedSession created) {
        launchedModel = created.model();
        launchedEffort = created.effort();
        launchRecorded = true;
    }

    /**
     * True when the live session was opened with a different model or reasoning effort than the ones in force
     * now. A session binds both when it is opened, so such a session must be replaced before the next turn.
     * A session the manager never recorded a launch for (none is live in production) is never stale.
     */
    boolean liveSessionIsStale() {
        if (!launchRecorded) {
            return false;
        }
        return !Objects.equals(launchedModel, model) || !Objects.equals(launchedEffort, reasoningEffort);
    }

    private CopilotSession openSession(CopilotClient client, String model,
                                       Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers, String validatedEffort)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (!storedSessionExists(client, copilotSessionId)) {
            return createSession(client, model, mcpServers, copilotSessionId, validatedEffort);
        }
        try {
            GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(listener, sessionId);
            permissionHandler = handler;
            return resumeSessionHook(client, copilotSessionId, buildResumeConfig(model, mcpServers, handler, validatedEffort))
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
            return createSession(client, model, mcpServers, copilotSessionId, validatedEffort);
        }
    }

    /**
     * Overridable delegation seam for the SDK's resumable-session RPC calls. {@code CopilotClient} is final,
     * so a unit test cannot subclass it; these three hooks let a test subclass this manager and script the
     * exact resume-failure / create fall-through without a real CLI process. They are deliberately
     * package-private and non-final: production behaviour is identical (plain delegation), only the seam is
     * exposed.
     */
    CompletableFuture<List<SessionMetadata>> listSessionsHook(CopilotClient client) {
        return client.listSessions();
    }

    CompletableFuture<CopilotSession> resumeSessionHook(CopilotClient client, String sessionId, ResumeSessionConfig config) {
        return client.resumeSession(sessionId, config);
    }

    CompletableFuture<CopilotSession> createSessionHook(CopilotClient client, SessionConfig config) {
        return client.createSession(config);
    }

    /**
     * Validates {@link #reasoningEffort} against {@code forModel}'s live-discovered supported list before it
     * reaches {@link #buildCreateConfig}/{@link #buildResumeConfig}. Package-private for direct unit testing.
     * Mirrors the reference validate/clear/INFO implementation,
     * {@code OpenCodeAiProcessManager.applyInitialEffortOption}, narrowed for Copilot: an unset
     * value is left alone; a value not supported by the model (including an unknown model, which is treated
     * as "no support") is never sent, and — only when it was pinned in the session
     * ({@link #reasoningEffortFromSession}) — is cleared with exactly one INFO event; a global-sourced value
     * is instead omitted with a FINE log, leaving the global and the session untouched; a supported value is
     * returned unchanged.
     */
    String resolveValidatedReasoningEffort(String forModel) {
        String stored = reasoningEffort;
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (GithubCopilotPluginSettings.getSupportedReasoningEfforts(forModel).contains(stored)) {
            return stored;
        }
        if (!reasoningEffortFromSession) {
            // Rule 3a: the value came from the global default. The global belongs to the user and applies to every
            // other session; one session's model not supporting it says nothing about the rest. Send nothing, log at
            // FINE, and do NOT clear the global, do NOT write into the session, do NOT fire an INFO. The value stays
            // in place so a later model that does support it still receives it.
            LOG.log(Level.FINE, "Reasoning effort \"" + stored + "\" is not available for model \"" + forModel
                                + "\"; omitting it for this session (global default left untouched)");
            return null;
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                "Reasoning effort \"" + stored + "\" is not available for model \"" + forModel + "\"; clearing it"));
        reasoningEffort = null;
        Runnable cleared = onReasoningEffortCleared;
        if (cleared != null) {
            try {
                cleared.run();
            }
            catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Copilot reasoning-effort clear callback failed", ex);
            }
        }
        listener.onAiProcessEvent(new GithubCopilotReasoningEffortClearedEvent());
        return null;
    }

    private boolean storedSessionExists(CopilotClient client, String targetSessionId)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return false;
        }
        List<SessionMetadata> sessions = listSessionsHook(client).get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
        return sessionListContains(sessions, targetSessionId);
    }

    private CopilotSession createSession(CopilotClient client, String model,
                                         Map<String, com.github.copilot.rpc.McpServerConfig> mcpServers, String targetSessionId,
                                         String reasoningEffort)
            throws ExecutionException, InterruptedException, TimeoutException {
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(listener, sessionId);
        permissionHandler = handler;
        return createSessionHook(client, buildCreateConfig(targetSessionId, model, mcpServers, handler, reasoningEffort))
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
     * Maps a session-start failure to the same fatal-error events the old process-based flow reported after a
     * nonzero exit + stderr grep — now read directly off the failed future's message instead of joined stderr
     * lines. Message text confirmed live against the real CLI (not guessed): "Not authenticated..." and
     * "...is not available." respectively.
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
                try {
                    cb.accept(model);
                }
                catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "Copilot model-fallback callback failed", ex);
                }
            }
            listener.onAiProcessEvent(new GithubCopilotModelFallbackEvent(model));
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Model was not available for your account — switched to 'auto'. Please resend your message."));
        }
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                StatusMessageUtil.formatSendFailed(msg)));
        LOG.log(Level.WARNING, "GitHub Copilot session start failed", e);
    }

    /**
     * Requests Copilot's server-side history compaction. This is an RPC operation, not a model turn, so
     * callers must not add a synthetic prompt: no response turn follows.
     */
    CompletableFuture<SessionHistoryCompactResult> compactHistory(String customInstructions) {
        CopilotSession session = copilotSession;
        if (session == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Copilot session is not running"));
        }
        SessionHistoryCompactParams params = new SessionHistoryCompactParams(
                session.getSessionId(), customInstructions,
                SessionHistoryCompactParams.SessionHistoryCompactParamsTrigger.MANUAL, null);
        return session.getRpc().history.compact(params);
    }

    /**
     * Best-effort abort of a manual compaction the plugin has already reported as timed out, so it does not
     * keep running server-side. The SDK exposes this as public API
     * ({@code SessionHistoryApi#abortManualCompaction()}), so it is safe to call. Guarded: abort needs a live
     * session, and a failure here must never mask the timeout the caller is reporting.
     */
    void abortManualCompaction() {
        CopilotSession session = copilotSession;
        if (session == null) {
            return;
        }
        try {
            session.getRpc().history.abortManualCompaction();
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "Could not abort manual compaction after timeout", e);
        }
    }

    /**
     * The runtime-error processor the session bridge's onError callback runs. A mid-turn
     * {@code SessionErrorEvent} / {@code ModelCallFailureEvent} (quota, rate limit, network) is a runtime
     * FAILURE, not a process death: exactly one FAILED, never EXITED — EXITED is reserved for the process
     * actually dying, and this SDK-based backend owns no OS process, so nothing here ever emits it — and
     * {@code processing} is cleared so the next turn can start.
     */
    void handleRuntimeError(String msg) {
        boolean turnInFlight;
        synchronized (this) {
            turnInFlight = processing;
            processing = false;
            if (turnInFlight) {
                // This turn is being closed by FAILED; its own trailing session.idle must not re-green the tab (#2).
                turnClosedByFailure = true;
            }
        }
        if (!turnInFlight && isWorkInFlight()) {
            // Mid-compaction failure: the compaction is non-turn work, so its runWork closer must be the ONE and
            // only closing status (#1). Reporting FAILED here as well would close it twice and let runWork's later
            // closer — FAILED or even READY — paint the tab over the error.
            failWorkInFlight(runtimeFailureMessage(msg));
            return;
        }
        listener.onAiProcessEvent(runtimeFailureStatus(msg));
    }

    /**
     * Maps a runtime failure (a mid-turn {@code SessionErrorEvent} / {@code ModelCallFailureEvent}) to its
     * status. FAILED, not EXITED: EXITED is reserved for the process actually dying, so this SDK-based
     * backend never emits EXITED.
     */
    static StatusEvent runtimeFailureStatus(String msg) {
        return new StatusEvent(StatusEventTypeEnum.FAILED, runtimeFailureMessage(msg));
    }

    static String runtimeFailureMessage(String msg) {
        return "GitHub Copilot: " + msg;
    }

    /**
     * Why a {@link #sendPrompt} cannot start a turn right now, or {@code null} when it can. Checked in the
     * same order as the guards it replaces, so the INFO names the actual blocker.
     */
    private Refusal sendRefusal() {
        if (pendingDiff) {
            return new Refusal("Wait for the pending changes to be resolved before sending another message", true);
        }
        if (!running) {
            return new Refusal("GitHub Copilot is not ready — wait for it to finish starting", true);
        }
        if (processing) {
            // The stale in-flight completion is ignored by the UI contract, so this refusal must release the submit lock now.
            return new Refusal("Wait for GitHub Copilot to finish before sending another message", true);
        }
        if (isWorkInFlight()) {
            return new Refusal("Wait for the current compaction to finish before sending another message", true);
        }
        return null;
    }

    /**
     * A refused {@link #sendPrompt}: the message to show, and whether this refusal is also what has to
     * release the UI lock that {@code handleSubmit} took before calling in.
     */
    private record Refusal(String reason, boolean releasesUi) {

    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        Refusal refusal = sendRefusal();
        if (refusal != null) {
            // handleSubmit has already locked the UI before calling sendPrompt, so a silent return would leave it
            // locked forever. Every refusal therefore names its reason; every
            // refusal also re-enables the UI with a turn-complete; stale in-flight completions are ignored by the UI contract.
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, refusal.reason()));
            if (refusal.releasesUi()) {
                listener.onAiProcessEvent(new TurnCompleteEvent());
            }
            return;
        }

        // A model or effort change binds only when a session is opened. The background recycle normally
        // drops the stale session first, but a send can arrive before it has run; detach the session here so
        // this turn is re-established with the values now in force. It is closed off the EDT, below.
        CopilotSession staleSession = null;
        if (copilotSession != null && liveSessionIsStale()) {
            staleSession = copilotSession;
            copilotSession = null;
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
            CopilotSession toClose = staleSession;
            new Thread(() -> {
                closeQuietly(toClose);
                reestablishAndSend(text, workingDir, projectDirs);
            }, "copilot-model-recycle").start();
            return;
        }

        cancelledByUser = false;
        // Starting a fresh turn: a failure of an EARLIER turn must no longer suppress this turn's own idle.
        turnClosedByFailure = false;
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
     * Re-establishes the CopilotSession after a model change and then sends the queued prompt. Runs on a
     * background thread: createOrResumeSession() blocks on RPC for up to two minutes per call, and every
     * sendPrompt() caller is on the EDT. The RPC deliberately happens OUTSIDE the monitor so a concurrent
     * stop() (e.g. the user closing the tab mid-recycle) is not blocked by it; only publishing the new
     * session takes the lock.
     */
    private void reestablishAndSend(String text, File workingDir, List<File> projectDirs) {
        CopilotSession created = null;
        CreatedSession launch = null;
        try {
            launch = createOrResumeSession(client, model);
            created = launch.session();
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
            recordLaunched(launch);
            copilotSessionId = created.getSessionId();
            if (eventBridge != null) {
                eventBridge.attach(created);
            }
        }
        // Re-enter the normal path now that a session exists: it re-arms
        // `processing` and runs the usual send/failure handling.
        sendPrompt(text, workingDir, projectDirs);
    }

    private static void closeQuietly(CopilotSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "Ignoring error closing a superseded Copilot session", e);
        }
    }

    /**
     * Graceful interrupt: Cancel aborts the current turn without tearing down the session (session.abort()) —
     * previously this had to kill the whole OS process since one-shot -p had no in-band signal. Mail
     * interjects the inter-AI mail notification into the running turn via immediate-mode send instead of
     * killing anything — Mail was always meant to interrupt and inject, never to kill (confirmed with Chris).
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
        turnClosedByFailure = false;
        // A stop while non-turn work (a compaction) is in flight closes it as FAILED — exactly once — so the
        // session is never left holding a BUSY that only a late completion (which stop's teardown may orphan)
        // would close. start() calls stop() first, which covers the restart path too.
        failWorkInFlight("GitHub Copilot stopped before the compaction finished");
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
        launchRecorded = false;
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
