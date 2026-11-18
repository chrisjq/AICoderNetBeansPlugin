package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.DebugDirectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotFatalErrorEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotModelFallbackEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotQuotaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotReasoningEffortClearedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotCompaction;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotLaunch;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotMcpServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionGate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdk;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSelector;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSessionSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.session.GithubCopilotAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Drives GitHub Copilot via a persistent SDK session (one CopilotSdkClient + CopilotSdkSession per plugin AI session),
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

    static CopilotSessionSpec buildSessionSpec(String sessionId, String model,
                                               Map<String, CopilotMcpServer> mcpServers,
                                               CopilotPermissionGate gate, String reasoningEffort) {
        return new CopilotSessionSpec(sessionId, model,
                                      reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort,
                                      EXCLUDED_NATIVE_TOOLS, mcpServers, gate, true);
    }

    static boolean sessionListContains(List<String> sessions, String targetSessionId) {
        return sessions != null && targetSessionId != null && !targetSessionId.isBlank()
                && sessions.contains(targetSessionId);
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
    // Volatile: interrupt, compactHistory and abortManualCompaction read these without the monitor.
    private volatile GithubCopilotAiSession copilotAiSession = null;
    private volatile GithubCopilotSessionEventBridge eventBridge = null;
    /**
     * Bumped by every start and every teardown. Work that runs outside the monitor (a re-establish, an SDK event)
     * captures it first and drops its result when it has changed, because the session it belonged to is gone.
     */
    private final AtomicLong generation = new AtomicLong();
    /**
     * True while a background {@link #reestablishAndSend} is in flight. A second send is refused meanwhile, so at most
     * one re-establish runs per generation and a Stop pressed during it cannot be undone by a later send. Guarded by
     * this manager's monitor.
     */
    private boolean reestablishing = false;
    /**
     * Set when {@link #handleRuntimeError} closes a running turn as FAILED. The SDK still emits that turn's trailing
     * {@code session.idle}, which would otherwise surface as a {@link TurnCompleteEvent} and re-green the tab — and, if
     * the user sent again in between, unlock the NEW turn. Consumed by exactly one idle and re-armed only by the next
     * {@link #sendPrompt} that actually starts a turn.
     */
    private volatile boolean turnClosedByFailure = false;

    private volatile CopilotSdkClient client = null;
    private volatile CopilotSdkSession copilotSession = null;
    /**
     * The SDK the live {@link #client} was started with. A failed re-establish compares it with what a fresh probe
     * selects, so it can tell a CLI that changed protocol from an ordinary failure.
     */
    private volatile CopilotSdk activeSdk = null;
    /**
     * The CLI file's stamp when {@link #activeSdk} was selected, or null when the path could not be resolved. A failed
     * re-establish probes again only when the stamp has changed since.
     */
    private volatile String activeStamp = null;
    /**
     * Tests only: an SDK used instead of the selector. Survives the {@link #stop} that {@link #start} begins with, and
     * while it is set nothing is re-probed or retried.
     */
    private volatile CopilotSdk pinnedSdk = null;
    private volatile SdkSelection selection = SdkSelection.DEFAULT;
    private volatile GithubCopilotPermissionHandler permissionHandler = null;
    private volatile Consumer<String> onModelFallback;
    /**
     * {@code null} means "omit the setting — use the model's own default". Applied where the session is constructed
     * (eagerly, inside {@link #start}, unlike pi's lazily-spawned session) — see
     * {@link #resolveValidatedReasoningEffort}.
     */
    private volatile String reasoningEffort = null;
    /**
     * Whether {@link #reasoningEffort} is a value pinned in the session's own settings (true) or one inherited from the
     * global default at session start (false). Only a session-pinned value may be cleared, persisted as cleared, and
     * reported with an INFO event when the model does not support it; a global-sourced value must be silently omitted
     * for this session (global untouched, nothing written into the session, no INFO), and kept in place so a later
     * model that does support it still receives it.
     */
    private volatile boolean reasoningEffortFromSession = true;
    /**
     * Notified when {@link #resolveValidatedReasoningEffort} clears {@link #reasoningEffort} because the model does not
     * support it. This manager has no session-settings/host reference of its own (only
     * {@code GithubCopilotAiImplementation} does), so clearing the in-memory field here is not enough — without this
     * callback the stored session value would never be persisted-cleared, and every subsequent start would re-read the
     * same stale value and fire the INFO event again. Mirrors {@link #onModelFallback}'s callback shape. Invoked only
     * for session-pinned values (rule 3a); never for a global-sourced value.
     */
    private volatile Runnable onReasoningEffortCleared;
    /**
     * The model and stored effort the live {@link #copilotSession} was opened with, valid only while
     * {@link #launchRecorded}. {@link #sendPrompt} compares them with the current ones, so a model or effort change
     * made after the session opened takes effect on the next turn whether or not the background
     * {@link #recycleForModelChange} got there first.
     */
    private volatile String launchedModel;
    private volatile String launchedEffort;
    private volatile boolean launchRecorded;

    public GithubCopilotProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    /**
     * How the manager picks an SDK for a CLI: {@link CopilotSdkSelector} in production, a fake in tests.
     */
    interface SdkSelection {

        SdkSelection DEFAULT = new SdkSelection() {
            @Override
            public CopilotSdk sdkFor(String executablePath) throws CopilotSdkException {
                return CopilotSdkSelector.sdkFor(executablePath);
            }

            @Override
            public void invalidate(String executablePath) {
                CopilotSdkSelector.invalidate(executablePath);
            }

            @Override
            public String stampFor(String executablePath) {
                return CopilotSdkSelector.stampFor(executablePath);
            }
        };

        CopilotSdk sdkFor(String executablePath) throws CopilotSdkException;

        void invalidate(String executablePath);

        /**
         * The "mtime:size" stamp of the CLI file, or null when the path cannot be resolved to a file.
         */
        String stampFor(String executablePath);
    }

    /**
     * Test seam: pins one SDK for every start, bypassing selection, re-probing and the retry. Null restores selection.
     */
    void setSdkForTests(CopilotSdk sdk) {
        this.pinnedSdk = sdk;
    }

    /**
     * Test seam: replaces the selector, so the selection-and-retry path can run without a CLI. Null restores the real
     * {@link CopilotSdkSelector}.
     */
    void setSdkSelectionForTests(SdkSelection selection) {
        this.selection = selection == null ? SdkSelection.DEFAULT : selection;
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
     * Sets the reasoning effort together with its provenance. {@code fromSession} is true when the value came from the
     * session's own settings, false when it was inherited from the global default: only a session-pinned value may be
     * cleared, persisted and reported (INFO) when unsupported — a global-sourced one is silently omitted for this
     * session instead, since the global belongs to the user and every other session.
     */
    public void setReasoningEffort(String reasoningEffort, boolean fromSession) {
        this.reasoningEffort = reasoningEffort;
        this.reasoningEffortFromSession = fromSession;
    }

    /**
     * Stops whatever runs now, then starts afresh. Not synchronized: the old client is closed by {@link #stop} outside
     * the monitor and before the new one starts, so the two never overlap and the close never waits on an SDK reader
     * thread that needs the monitor. A start that slipped in between (it left a client behind) is stopped again.
     */
    @Override
    public void start(String executablePath, String model) {
        while (true) {
            stop();
            synchronized (this) {
                if (client == null) {
                    startLocked(executablePath, model);
                    return;
                }
            }
        }
    }

    private synchronized void startLocked(String executablePath, String model) {
        generation.incrementAndGet();
        this.executablePath = executablePath;
        this.model = model;

        if (!GithubCopilotExecutableLocator.isExecutableFile(executablePath)) {
            running = false;
            String failure = StatusMessageUtil.formatStartFailed("copilot executable not found at " + executablePath);
            debugLog(DebugDirectionEnum.EVENT, "error", failure);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, failure));
            listener.onAiProcessEvent(new GithubCopilotFatalErrorEvent(
                    "EXECUTABLE_NOT_FOUND", "GitHub Copilot CLI not found"));
            return;
        }
        if (currentSession == null) {
            String failure = StatusMessageUtil.formatSessionNotConfigured();
            debugLog(DebugDirectionEnum.EVENT, "error", failure);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, failure));
            return;
        }
        sessionId = currentSession.id();
        registerDebugSecret(currentSession.secret());
        if (sessionCorrupted) {
            copilotSessionId = UUID.randomUUID().toString();
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
            String failure = StatusMessageUtil.formatMcpSetupFailed();
            debugLog(DebugDirectionEnum.EVENT, "error", failure);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, failure));
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

        // Throwable, not Exception: a LinkageError from a broken SDK jar must still reach the UI as FAILED and release
        // the MCP registrar, or the tab stays "starting" for good.
        try {
            StartedClient started = openClientAndSession(executablePath, planSession(model));
            client = started.client();
            activeSdk = started.sdk();
            activeStamp = started.stamp();
            adopt(started.created());
            eventBridge.attach(copilotSession);
        }
        catch (Throwable failure) {
            handleSessionStartFailure(failure);
            return;
        }

        running = true;
        debugLog(DebugDirectionEnum.EVENT, "start", "");
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
     * The listener the session bridge forwards through: it owns the turn bookkeeping the raw {@code listener} must not
     * see — clearing {@code processing} on the SDK's end-of-turn signal, and deciding whether a
     * {@link TurnCompleteEvent} is a real completion or must be swallowed.
     *
     * <p>
     * Two idles are swallowed: the trailing idle of a turn already closed by a runtime FAILURE (#2 — otherwise it
     * re-greens the tab, and unlocks a new turn the user has since sent), and any idle a manual compaction emits while
     * it is in flight (#6 — a compaction is non-turn work, so its idle is not a turn completion). Extracted from
     * {@code start()} so both rules can be unit-tested without a live CLI. Package-private: production callers only.
     *
     * <p>
     * The listener belongs to the generation it was created in: once a stop or restart has moved the generation on, an
     * event from the old session (one already waiting for the monitor when the subscription closed) is dropped whole.
     */
    AiProcessEventListener createTurnAwareListener() {
        long owner = generation.get();
        return event -> {
            boolean isTurnComplete = event instanceof TurnCompleteEvent;
            boolean shouldFire;
            boolean swallowedIdle = false;
            synchronized (GithubCopilotProcessManager.this) {
                if (generation.get() != owner) {
                    return;
                }
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
     * Builds and wires the session bridge for a turn-aware listener. Extracted from {@code start()} so a unit test can
     * drive a real SDK event through {@code bridge.onError -> handleRuntimeError} and verify the wiring, rather than
     * calling {@code handleRuntimeError} directly (which would pass even with the wiring reverted). Package-private:
     * production callers only.
     *
     * <p>
     * Like the turn-aware listener, the error path belongs to the generation the bridge was built in: a late session
     * error from a client already torn down must not close the next run's turn.
     */
    GithubCopilotSessionEventBridge createEventBridge(AiProcessEventListener turnAwareListener) {
        long owner = generation.get();
        GithubCopilotSessionEventBridge bridge = new GithubCopilotSessionEventBridge(turnAwareListener);
        bridge.setDebugLogSink(value -> debugLog(DebugDirectionEnum.RECEIVED, "event", value));
        bridge.setEventSink((kind, payload) -> debugLog(DebugDirectionEnum.EVENT, kind, payload));
        bridge.setOnError(msg -> handleRuntimeError(msg, owner));
        bridge.setSessionNameSupplier(() -> {
            AiSession s = currentSession;
            return s == null ? null : s.name();
        });
        return bridge;
    }

    /**
     * A started client with its first session, the SDK that started it and the CLI stamp it was selected for.
     */
    private record StartedClient(CopilotSdk sdk, String stamp, CopilotSdkClient client, CreatedSession created) {

    }

    /**
     * An SDK chosen by the selector, with the CLI stamp it was chosen for (null when the path cannot be resolved).
     */
    private record Selected(CopilotSdk sdk, String stamp) {

    }

    /**
     * Selects an SDK for the CLI, starts a client and opens the session. The SDK is selected on every start (the
     * selector caches per executable, so this is cheap); a CLI updated across a protocol boundary is then picked up on
     * the next start.
     *
     * <p>
     * When the start, create or resume fails and the CLI file has changed since it was selected, the CLI is probed
     * again and the attempt is repeated once, but only if the fresh probe selects a different SDK: a protocol change,
     * not the error text, decides. A failure of the first selection (probe failure, unsupported protocol, an SDK that
     * will not load) is final. A pinned test SDK is never retried.
     */
    private StartedClient openClientAndSession(String path, SessionPlan plan) throws Exception {
        CopilotSdk pinned = pinnedSdk;
        // The stamp is read before the probe: a CLI replaced in between then looks changed, never unchanged.
        String stamp = pinned != null ? null : selection.stampFor(path);
        CopilotSdk first = pinned != null ? pinned : selection.sdkFor(path);
        try {
            return openWith(first, stamp, path, plan);
        }
        catch (Throwable failure) {
            Selected second = pinned != null || failure instanceof InterruptedException
                              ? null : reselect(first, stamp, path);
            if (second == null) {
                throw failure;
            }
            LOG.log(Level.INFO, "GitHub Copilot start with {0} failed and the CLI now selects {1}; retrying once",
                    new Object[]{first.label(), second.sdk().label()});
            try {
                return openWith(second.sdk(), second.stamp(), path, plan);
            }
            catch (Throwable retryFailure) {
                retryFailure.addSuppressed(failure);
                throw retryFailure;
            }
        }
    }

    /**
     * Starts a client with {@code sdk} and opens the session. A client that started but could not open a session is
     * closed before the failure propagates, so a retry never leaves the first CLI process running.
     */
    private StartedClient openWith(CopilotSdk sdk, String stamp, String path, SessionPlan plan) throws Exception {
        LOG.log(Level.INFO, "GitHub Copilot selected {0}", sdk.label());
        CopilotSdkClient opened = sdk.start(new CopilotLaunch(path, List.of(), null,
                                                              Duration.ofMillis(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis())));
        try {
            return new StartedClient(sdk, stamp, opened, openSession(opened, plan));
        }
        catch (Throwable failure) {
            closeClientQuietly(opened);
            throw failure;
        }
    }

    /**
     * After a failure with {@code used}, selected when the CLI had {@code usedStamp}: when the CLI file is unchanged
     * the cached probe still describes it, so there is nothing to retry and no probe is made (a probe can take as long
     * as a session start). Otherwise forgets the cached probe, probes again, and returns the newly selected SDK only
     * when it is a different one. Null (including when the probe itself fails) means "do not retry".
     */
    private Selected reselect(CopilotSdk used, String usedStamp, String path) {
        try {
            String current = selection.stampFor(path);
            if (usedStamp != null && usedStamp.equals(current)) {
                return null;
            }
            selection.invalidate(path);
            CopilotSdk fresh = selection.sdkFor(path);
            return fresh == null || fresh.getClass().equals(used.getClass()) ? null : new Selected(fresh, current);
        }
        catch (CopilotSdkException | RuntimeException | LinkageError reprobe) {
            LOG.log(Level.FINE, "Probing the Copilot CLI again after a failed start also failed", reprobe);
            return null;
        }
    }

    private static void closeClientQuietly(CopilotSdkClient closing) {
        if (closing == null) {
            return;
        }
        try {
            closing.close();
        }
        catch (RuntimeException | LinkageError closeEx) {
            LOG.log(Level.FINE, "Ignoring error closing a Copilot client", closeEx);
        }
    }

    /**
     * The most specific message in {@code failure}'s cause chain, or its class name when none carries one.
     */
    private static String describeFailure(Throwable failure) {
        String message = deepestMessage(failure);
        return message != null ? message : failure.getClass().getSimpleName();
    }

    /**
     * Everything a session open needs from this manager's fields, read up front (under the monitor, by the callers that
     * run the open outside it), so the open itself touches no field and a superseded open has nothing to undo.
     */
    private record SessionPlan(String model, String targetId, String pluginSessionId,
                               Map<String, CopilotMcpServer> mcpServers, String validatedEffort, String storedEffort) {

    }

    /**
     * Builds the plan for opening a session on {@code model}, raising its notices at once.
     */
    private SessionPlan planSession(String model) {
        return planSession(model, Runnable::run);
    }

    /**
     * Builds the plan for opening a session on {@code model}. This is where the MCP-unavailable and reasoning-effort
     * notices are raised, once per open: each listener event and callback is handed to {@code notices}, so a caller
     * that plans under the monitor can deliver them after releasing it.
     */
    private SessionPlan planSession(String model, Consumer<Runnable> notices) {
        Map<String, CopilotMcpServer> mcpServers = buildMcpServers(notices);
        String validatedEffort = resolveValidatedReasoningEffort(model, notices);
        // Read after the validation, which may have cleared it.
        String storedEffort = reasoningEffort;
        return new SessionPlan(model, copilotSessionId, sessionId, mcpServers, validatedEffort, storedEffort);
    }

    /**
     * An opened session with what it was opened with, and what the manager adopts along with it: the permission handler
     * that gates it, and whether the stored session turned out to be corrupted.
     */
    private record CreatedSession(CopilotSdkSession session, String model, String effort,
                                  GithubCopilotPermissionHandler handler, boolean corrupted) {

    }

    /**
     * Makes {@code created} the live session. Callers hold the monitor and have checked the open is still current.
     */
    private void adopt(CreatedSession created) {
        GithubCopilotPermissionHandler previous = permissionHandler;
        if (previous != null && previous != created.handler()) {
            // The session it gated was dropped for this one.
            previous.close();
        }
        permissionHandler = created.handler();
        if (created.corrupted()) {
            sessionCorrupted = true;
        }
        copilotSession = created.session();
        recordLaunched(created);
        copilotSessionId = created.session().sessionId();
    }

    private void recordLaunched(CreatedSession created) {
        launchedModel = created.model();
        launchedEffort = created.effort();
        launchRecorded = true;
    }

    boolean liveSessionIsStale() {
        return launchRecorded && (!Objects.equals(launchedModel, model)
                || !Objects.equals(launchedEffort, reasoningEffort));
    }

    /**
     * Opens the session {@code plan} describes on {@code client}: resumes the stored Copilot session only when the SDK
     * session store actually lists it, otherwise creates a fresh one under the stable plugin session id, so a first
     * start has no noisy "session not found". It reads and writes no manager field: what the open learns (the
     * permission handler, a corrupted stored session) is returned for {@link #adopt} to apply, so an open whose result
     * is thrown away changes nothing.
     */
    private CreatedSession openSession(CopilotSdkClient client, SessionPlan plan)
            throws ExecutionException, InterruptedException, TimeoutException {
        GithubCopilotPermissionHandler handler = new GithubCopilotPermissionHandler(listener, plan.pluginSessionId());
        String config = describeConfig(plan.model(), plan.mcpServers(), plan.validatedEffort());
        String targetId = plan.targetId();
        CopilotSessionSpec spec = buildSessionSpec(targetId, plan.model(), plan.mcpServers(), handler,
                                                   plan.validatedEffort());
        if (!storedSessionExists(client, targetId)) {
            debugLog(DebugDirectionEnum.SENT, "session.create", config);
            CopilotSdkSession created = client.create(spec).get(
                    TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
            return new CreatedSession(created, plan.model(), plan.storedEffort(), handler, false);
        }
        try {
            debugLog(DebugDirectionEnum.SENT, "session.resume", config);
            CopilotSdkSession resumed = client.resume(targetId, spec).get(
                    TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
            return new CreatedSession(resumed, plan.model(), plan.storedEffort(), handler, false);
        }
        catch (ExecutionException resumeFailure) {
            boolean corrupted = isCorruptedSessionFailure(resumeFailure);
            if (corrupted) {
                targetId = UUID.randomUUID().toString();
            }
            else if (!isSessionNotFoundFailure(resumeFailure)) {
                LOG.log(Level.INFO, "Resume failed for " + targetId + ", creating instead", resumeFailure);
            }
            debugLog(DebugDirectionEnum.SENT, "session.create", config);
            CopilotSdkSession created = client.create(buildSessionSpec(targetId, plan.model(), plan.mcpServers(),
                                                                       handler, plan.validatedEffort()))
                    .get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
            return new CreatedSession(created, plan.model(), plan.storedEffort(), handler, corrupted);
        }
    }

    CompletableFuture<List<String>> listSessionsHook(CopilotSdkClient client) {
        return client.listSessionIds();
    }

    /**
     * Validates {@link #reasoningEffort} against {@code forModel}'s live-discovered supported list before it reaches
     * {@link #buildCreateConfig}/{@link #buildResumeConfig}. Package-private for direct unit testing. Mirrors the
     * reference validate/clear/INFO implementation, {@code OpenCodeAiProcessManager.applyInitialEffortOption}, narrowed
     * for Copilot: an unset value is left alone; a value not supported by the model (including an unknown model, which
     * is treated as "no support") is never sent, and — only when it was pinned in the session
     * ({@link #reasoningEffortFromSession}) — is cleared with exactly one INFO event; a global-sourced value is instead
     * omitted with a FINE log, leaving the global and the session untouched; a supported value is returned unchanged.
     */
    String resolveValidatedReasoningEffort(String forModel) {
        return resolveValidatedReasoningEffort(forModel, Runnable::run);
    }

    /**
     * As {@link #resolveValidatedReasoningEffort(String)}, handing the INFO, the clear callback and the cleared event
     * to {@code notices} instead of raising them here. The value itself is cleared at once.
     */
    private String resolveValidatedReasoningEffort(String forModel, Consumer<Runnable> notices) {
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
        reasoningEffort = null;
        Runnable cleared = onReasoningEffortCleared;
        notices.accept(() -> {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                                      "Reasoning effort \"" + stored + "\" is not available for model \"" + forModel + "\"; clearing it"));
            if (cleared != null) {
                try {
                    cleared.run();
                }
                catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "Copilot reasoning-effort clear callback failed", ex);
                }
            }
            listener.onAiProcessEvent(new GithubCopilotReasoningEffortClearedEvent());
        });
        return null;
    }

    private boolean storedSessionExists(CopilotSdkClient client, String targetSessionId)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return false;
        }
        List<String> sessions = listSessionsHook(client).get(
                TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
        return sessionListContains(sessions, targetSessionId);
    }

    private static String describeConfig(String model, Map<String, CopilotMcpServer> mcpServers,
                                         String reasoningEffort) {
        return "model=" + model + ", reasoningEffort=" + (reasoningEffort == null ? "" : reasoningEffort)
                + ", mcpServers=" + (mcpServers == null ? "[]" : mcpServers.keySet());
    }

    /**
     * The plugin's MCP server for the session spec, or none (with an INFO handed to {@code notices}) when it is not
     * available.
     */
    private Map<String, CopilotMcpServer> buildMcpServers(Consumer<Runnable> notices) {
        String endpoint = currentSession == null ? null
                          : McpServerRegistry.endpointUrlFor(AiTypeEnum.GitHubCoPilot,
                                                             currentSession.settings().ensureMcpRoutingId());
        if (endpoint == null || sessionId == null) {
            LOG.log(Level.WARNING, "MCP server unavailable; running Copilot without plugin tools");
            notices.accept(() -> listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                                                           StatusMessageUtil.formatMcpToolsUnavailable("GitHub Copilot"))));
            return Map.of();
        }
        CopilotMcpServer mcpServer = new CopilotMcpServer(endpoint, List.of("*"),
                                                          Math.toIntExact(GithubCopilotTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis()));
        return Map.of(StringConst.PLUGIN_ID, mcpServer);
    }

    /**
     * Maps a session-start failure to the same fatal-error events the old process-based flow reported after a nonzero
     * exit + stderr grep — now read directly off the failed future's message instead of joined stderr lines. Message
     * text confirmed live against the real CLI (not guessed): "Not authenticated..." and "...is not available."
     * respectively.
     */
    private void handleSessionStartFailure(Throwable e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        running = false;
        activeSdk = null;
        activeStamp = null;
        // Best-effort teardown of whatever start() set up before failing: a
        // started CopilotSdkClient owns a spawned `copilot --server` OS process,
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
                permHandler.close();
            }
            catch (Exception cancelEx) {
                LOG.log(Level.FINE, "Ignoring error cancelling pending permissions after failed start", cancelEx);
            }
        }
        CopilotSdkSession staleSession = copilotSession;
        copilotSession = null;
        GithubCopilotSessionEventBridge staleBridge = eventBridge;
        eventBridge = null;
        if (staleBridge != null) {
            staleBridge.detach();
        }
        if (staleSession != null) {
            try {
                staleSession.close();
            }
            catch (Exception closeEx) {
                LOG.log(Level.FINE, "Ignoring error closing Copilot session after failed start", closeEx);
            }
        }
        CopilotSdkClient staleClient = client;
        client = null;
        closeClientQuietly(staleClient);
        String msg = e.getMessage() != null ? e.getMessage() : describeFailure(e);
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
        String failure = StatusMessageUtil.formatSendFailed(msg);
        debugLog(DebugDirectionEnum.EVENT, "error", failure);
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, failure));
        LOG.log(Level.WARNING, "GitHub Copilot session start failed", e);
    }

    /**
     * Requests Copilot's server-side history compaction. This is an RPC operation, not a model turn, so callers must
     * not add a synthetic prompt: no response turn follows.
     */
    CompletableFuture<CopilotCompaction> compactHistory(String customInstructions) {
        CopilotSdkSession session = copilotSession;
        if (session == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Copilot session is not running"));
        }
        return session.compact(customInstructions);
    }

    /**
     * Best-effort abort of a manual compaction the plugin has already reported as timed out, so it does not keep
     * running server-side. The SDK exposes this as public API ({@code SessionHistoryApi#abortManualCompaction()}), so
     * it is safe to call. Guarded: abort needs a live session, and a failure here must never mask the timeout the
     * caller is reporting.
     */
    void abortManualCompaction() {
        CopilotSdkSession session = copilotSession;
        if (session == null) {
            return;
        }
        try {
            session.abortManualCompaction();
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "Could not abort manual compaction after timeout", e);
        }
    }

    /**
     * The runtime-error processor the session bridge's onError callback runs. A mid-turn {@code SessionErrorEvent} /
     * {@code ModelCallFailureEvent} (quota, rate limit, network) is a runtime FAILURE, not a process death: exactly one
     * FAILED, never EXITED — EXITED is reserved for the process actually dying, and this SDK-based backend owns no OS
     * process, so nothing here ever emits it — and {@code processing} is cleared so the next turn can start.
     */
    void handleRuntimeError(String msg) {
        handleRuntimeError(msg, generation.get());
    }

    /**
     * As {@link #handleRuntimeError(String)}, for an error raised by the session of generation {@code owner}; ignored
     * once a stop or restart has moved the generation on.
     */
    private void handleRuntimeError(String msg, long owner) {
        if (generation.get() != owner) {
            return;
        }
        boolean turnInFlight;
        synchronized (this) {
            if (generation.get() != owner) {
                return;
            }
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
            debugLog(DebugDirectionEnum.EVENT, "error", runtimeFailureMessage(msg));
            failWorkInFlight(runtimeFailureMessage(msg));
            return;
        }
        debugLog(DebugDirectionEnum.EVENT, "error", runtimeFailureMessage(msg));
        listener.onAiProcessEvent(runtimeFailureStatus(msg));
    }

    /**
     * Maps a runtime failure (a mid-turn {@code SessionErrorEvent} / {@code ModelCallFailureEvent}) to its status.
     * FAILED, not EXITED: EXITED is reserved for the process actually dying, so this SDK-based backend never emits
     * EXITED.
     */
    static StatusEvent runtimeFailureStatus(String msg) {
        return new StatusEvent(StatusEventTypeEnum.FAILED, runtimeFailureMessage(msg));
    }

    static String runtimeFailureMessage(String msg) {
        return "GitHub Copilot: " + msg;
    }

    /**
     * Why a {@link #sendPrompt} cannot start a turn right now, or {@code null} when it can. Checked in the same order
     * as the guards it replaces, so the INFO names the actual blocker.
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
        if (reestablishing) {
            // A Stop during the re-establish cleared processing; the re-establish itself is still running.
            return new Refusal("Wait for GitHub Copilot to finish reconnecting before sending another message", true);
        }
        if (isWorkInFlight()) {
            return new Refusal("Wait for the current compaction to finish before sending another message", true);
        }
        return null;
    }

    /**
     * A refused {@link #sendPrompt}: the message to show, and whether this refusal is also what has to release the UI
     * lock that {@code handleSubmit} took before calling in.
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
                debugLog(DebugDirectionEnum.EVENT, "turn-end", "");
                listener.onAiProcessEvent(new TurnCompleteEvent());
            }
            return;
        }

        // A model or effort change binds only when a session is opened. The background recycle normally
        // drops the stale session first, but a send can arrive before it has run; detach the session here so
        // this turn is re-established with the values now in force. It is closed off the EDT, below.
        CopilotSdkSession staleSession = null;
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
            reestablishing = true;
            if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
                sessionWorkingDir = workingDir;
            }
            CopilotSdkSession toClose = staleSession;
            long owner = generation.get();
            new Thread(() -> {
                try {
                    closeQuietly(toClose);
                    reestablishAndSend(owner, text, workingDir, projectDirs);
                }
                catch (Throwable unexpected) {
                    // Whatever escaped, the turn must not stay "reconnecting" for good.
                    abandonReestablish(owner, unexpected);
                }
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
        CopilotSdkSession session = copilotSession;
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "copilot prompt: {0}", text);
        }
        // session.send() only resolves once the message is queued (fire-and-forget) —
        // it does NOT mean the turn finished. Only handle the failure-to-queue case
        // here; the success path's `processing` reset happens on TurnCompleteEvent
        // in the turnAwareListener built in start().
        debugLog(DebugDirectionEnum.SENT, "send", text);
        session.send(text).whenComplete((messageId, err) -> {
            if (err != null) {
                synchronized (GithubCopilotProcessManager.this) {
                    processing = false;
                }
                if (!cancelledByUser) {
                    LOG.log(Level.WARNING, "GitHub Copilot send failed", err);
                    String failure = StatusMessageUtil.formatSendFailed(err.getMessage());
                    debugLog(DebugDirectionEnum.EVENT, "error", failure);
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, failure));
                }
            }
        });
    }

    /**
     * Re-establishes the CopilotSdkSession after a model change and then sends the queued prompt. Runs on a background
     * thread: openSession() blocks on RPC for up to two minutes per call, and every sendPrompt() caller is on the EDT.
     * The RPC deliberately happens OUTSIDE the monitor so a concurrent stop() (e.g. the user closing the tab
     * mid-recycle) is not blocked by it; only publishing the new session takes the lock.
     *
     * <p>
     * The generation is captured with the client. When it has moved on by the time the RPC returns (a stop, or a stop
     * and restart), the result belongs to a session that no longer exists: the session and any replacement client are
     * closed, and nothing else happens — no status, no change to {@code processing}, nothing adopted. A Stop pressed
     * while this ran keeps the new session for the next turn but does not send the prompt.
     *
     * <p>
     * Every way out of here, a failure included, leaves the generation it ran for consistent: the flags are cleared, a
     * session or client it opened but could not hand over is closed, and a failure is reported.
     */
    private void reestablishAndSend(long owner, String text, File workingDir, List<File> projectDirs) {
        CopilotSdkClient liveClient = null;
        CopilotSdk liveSdk = null;
        String liveStamp = null;
        String path = null;
        SessionPlan plan = null;
        StartedClient replacement = null;
        CreatedSession launch = null;
        Throwable failure = null;
        try {
            List<Runnable> notices = new ArrayList<>();
            synchronized (this) {
                if (generation.get() != owner) {
                    // Stopped before this began: the stop closed the turn.
                    return;
                }
                liveClient = client;
                liveSdk = activeSdk;
                liveStamp = activeStamp;
                path = executablePath;
                if (liveClient == null) {
                    throw new IllegalStateException("the Copilot client is not running");
                }
                // Planned under the monitor so the plan matches this generation; its notices go out after the
                // monitor is released.
                plan = planSession(model, notices::add);
            }
            deliverNotices(notices);
            launch = openSession(liveClient, plan);
        }
        catch (Throwable first) {
            if (first instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            failure = first;
            // The same one-shot rule as start(): retry on a new client only when the CLI changed and a fresh probe
            // selects another SDK.
            Selected second = pinnedSdk != null || liveSdk == null || plan == null
                    || first instanceof InterruptedException
                              ? null : reselect(liveSdk, liveStamp, path);
            if (second != null && generation.get() == owner) {
                try {
                    replacement = openWith(second.sdk(), second.stamp(), path, plan);
                    launch = replacement.created();
                    failure = null;
                }
                catch (Throwable retryFailure) {
                    retryFailure.addSuppressed(first);
                    failure = retryFailure;
                }
            }
            if (failure != null) {
                LOG.log(Level.WARNING, "Failed to re-establish Copilot session after model change", failure);
            }
        }
        // Whatever is not handed over below is closed in the finally, outside the monitor: closing waits on the SDK's
        // reader thread, which may need the monitor.
        CopilotSdkSession discardedSession = launch == null ? null : launch.session();
        CopilotSdkClient retiredClient = replacement == null ? null : replacement.client();
        boolean send = false;
        String failedStatus = null;
        try {
            synchronized (this) {
                if (generation.get() != owner) {
                    // Superseded: the stop already closed this generation's turn; whatever this produced is not ours.
                    return;
                }
                reestablishing = false;
                processing = false;
                boolean cancelled = cancelledByUser;
                if (launch != null && running && client == liveClient && copilotSession == null) {
                    try {
                        adopt(launch);
                        if (eventBridge != null) {
                            eventBridge.attach(copilotSession);
                        }
                        discardedSession = null;
                        if (replacement != null) {
                            retiredClient = client;
                            client = replacement.client();
                            activeSdk = replacement.sdk();
                            activeStamp = replacement.stamp();
                        }
                    }
                    catch (RuntimeException | LinkageError publishFailure) {
                        // Not usable after all: the session is closed below and the turn reported as failed.
                        copilotSession = null;
                        launchRecorded = false;
                        if (eventBridge != null) {
                            eventBridge.detach();
                        }
                        failure = publishFailure;
                    }
                }
                if (copilotSession != null) {
                    // After a Stop the session is kept for the next turn, but the prompt is not sent.
                    send = !cancelled;
                }
                else if (!cancelled) {
                    failedStatus = reestablishFailedStatus(failure);
                }
            }
            if (failedStatus != null) {
                debugLog(DebugDirectionEnum.EVENT, "error", failedStatus);
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, failedStatus));
            }
        }
        finally {
            closeQuietly(discardedSession);
            closeClientQuietly(retiredClient);
        }
        if (send) {
            sendAfterReestablish(owner, text, workingDir, projectDirs);
        }
    }

    /**
     * Re-enters the normal send path once the re-established session is live; it re-arms {@code processing} and runs
     * the usual send and failure handling. Checked again under the monitor: a Stop, stop or restart that came in after
     * the session was published (and so took the ordinary abort path) must not be overridden by this send.
     */
    private synchronized void sendAfterReestablish(long owner, String text, File workingDir, List<File> projectDirs) {
        if (generation.get() != owner || cancelledByUser) {
            return;
        }
        sendPrompt(text, workingDir, projectDirs);
    }

    /**
     * Ends a re-establish that failed outside its own handling: when it still belongs to the current generation and has
     * not finished, the flags are cleared and, unless the user stopped it, the turn is reported as failed.
     */
    private void abandonReestablish(long owner, Throwable failure) {
        LOG.log(Level.WARNING, "Re-establishing the Copilot session after a model change failed unexpectedly", failure);
        String failedStatus = null;
        synchronized (this) {
            if (generation.get() == owner && reestablishing) {
                reestablishing = false;
                processing = false;
                if (!cancelledByUser) {
                    failedStatus = reestablishFailedStatus(failure);
                }
            }
        }
        if (failedStatus != null) {
            debugLog(DebugDirectionEnum.EVENT, "error", failedStatus);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, failedStatus));
        }
    }

    private static String reestablishFailedStatus(Throwable failure) {
        String detail = failure != null ? describeFailure(failure) : "the session was stopped";
        return StatusMessageUtil.formatSendFailed("could not re-establish the session after the model change: " + detail);
    }

    /**
     * Raises notices collected while the monitor was held. One that fails is logged and does not stop the rest.
     */
    private static void deliverNotices(List<Runnable> notices) {
        for (Runnable notice : notices) {
            try {
                notice.run();
            }
            catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "A GitHub Copilot notice failed", failure);
            }
        }
    }

    private static void closeQuietly(CopilotSdkSession session) {
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
     * previously this had to kill the whole OS process since one-shot -p had no in-band signal. Mail interjects the
     * inter-AI mail notification into the running turn via immediate-mode send instead of killing anything — Mail was
     * always meant to interrupt and inject, never to kill (confirmed with Chris).
     */
    @Override
    public void interrupt(InterruptTypeEnum type) {
        CopilotSdkSession session = copilotSession;
        if (session == null) {
            // "Stop did nothing because nothing was running" and "Stop ran but
            // output kept coming" are indistinguishable after the fact — this
            // branch is the first of those. A silent no-op is what let Codex's
            // equivalent Mail drop go unnoticed for so long, so both types are
            // logged here, not just Cancel.
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "GitHub Copilot interrupt: no session (type={0}, session={1})",
                        new Object[]{type, sessionId});
            }
            if (type == InterruptTypeEnum.Cancel) {
                // A Stop while a model-change re-establish runs has no session to abort yet, but it must still end
                // the turn: the re-establish sees cancelledByUser and keeps the session without sending the prompt.
                cancelledByUser = true;
                GithubCopilotPermissionHandler handler = permissionHandler;
                if (handler != null) {
                    handler.cancelPendingPermissions();
                }
                synchronized (GithubCopilotProcessManager.this) {
                    processing = false;
                }
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
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
                debugLog(DebugDirectionEnum.SENT, "abort", "session.abort");
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
                    debugLog(DebugDirectionEnum.SENT, "send", "mail notice, mode=immediate");
                    session.sendImmediate("[inbox] You have a new message — check your inbox NOW.");
                }
                else if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO,
                            "GitHub Copilot interrupt: Mail IGNORED, no turn in flight — message will arrive via "
                            + "normal inbox flush (session={0})", sessionId);
                }
            }
        }
    }

    /**
     * Tears the session down, then closes the client outside this manager's monitor.
     *
     * <p>
     * The client is never closed while the monitor is held. {@code CopilotClient.close()} blocks on {@code stop()},
     * which needs replies (session destroy, runtime shutdown) that only the SDK's reader thread delivers, and that
     * thread hands every event to the turn-aware listener, which takes this monitor. Closing under the monitor while an
     * event is being delivered (a stop during a streaming turn) therefore stalls until the SDK's shutdown timeouts run
     * out. The event subscription is closed first, so no new event enters; an event already waiting for the monitor is
     * dropped once the monitor is free, because the generation has moved on. No caller holds the monitor here:
     * {@link #start} stops before it takes the monitor.
     */
    @Override
    public void stop() {
        CopilotSdkClient retired;
        synchronized (this) {
            retired = tearDown();
        }
        closeClientQuietly(retired);
    }

    /**
     * Releases everything a running session holds except the client, which it returns for the caller to close outside
     * the monitor.
     */
    private CopilotSdkClient tearDown() {
        debugLog(DebugDirectionEnum.EVENT, "stop", "");
        // Logged before the state is torn down, so the record says what was
        // actually in flight at the moment of the stop rather than the
        // cleared-out aftermath.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "GitHub Copilot stop: shutting session down (session={0}, turnInFlight={1}, sessionAlive={2})",
                    new Object[]{sessionId, processing, copilotSession != null});
        }
        generation.incrementAndGet();
        running = false;
        processing = false;
        reestablishing = false;
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
            permHandler.close();
        }
        // The subscription goes before the client: no further event can reach the listener once the close begins.
        GithubCopilotSessionEventBridge bridge = eventBridge;
        eventBridge = null;
        if (bridge != null) {
            bridge.detach();
        }
        CopilotSdkClient retired = client;
        client = null;
        activeSdk = null;
        activeStamp = null;
        copilotSession = null;
        launchRecorded = false;
        sessionId = null;
        copilotSessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigDir = null;
        return retired;
    }

    /**
     * Drops the live session after a model or effort change; the next send opens a new one. The session is closed
     * outside the monitor for the same reason {@link #stop} closes the client there.
     */
    public void recycleForModelChange() {
        CopilotSdkSession oldSession;
        synchronized (this) {
            if (!running || processing) {
                return;
            }
            oldSession = copilotSession;
            copilotSession = null;
        }
        closeQuietly(oldSession);
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
