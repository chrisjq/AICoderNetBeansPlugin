package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui.ClaudeAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui.ClaudeInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Thin adapter so the generic multi-AI system (AiSession, AiTopComponent, etc.)
 * can use the Claude implementation without any behavior change for Claude
 * users. All Claude-specific code stays in this package or the classes it owns.
 */
public class ClaudeAiImplementation extends AiImplementation {

    private static final Logger LOG = Logger.getLogger(ClaudeAiImplementation.class.getName());

    private static final Object MODEL_LOCK = new Object();
    private static final AiModelCatalog MODEL_CATALOG = new AiModelCatalog();

    private static final int MAX_MODEL_DISCOVERY_RETRIES = 20;
    private static int modelDiscoveryRetries = 0; // guarded by MODEL_LOCK
    private static volatile ClaudeUsageEvent cachedUsageEvent = null;
    private static volatile long lastUsageFetchAttemptMs = 0;
    /**
     * Learned minimum gap between usage-endpoint fetch attempts. Starts at
     * {@link #INITIAL_USAGE_INTERVAL_MS} — a conservative baseline rather than
     * 0, since direct testing against /api/oauth/usage showed the real limit
     * trips after as few as 3 requests within a few seconds, so waiting for the
     * first 429 before throttling at all would still let an initial burst
     * through. From there, every subsequent 429 grows the interval further by
     * {@link #OFFSET_MS} (see {@link #recordUsageFetch429IfApplicable()}) — a
     * plain additive ratchet rather than a one-shot measurement, since
     * Anthropic's Retry-After header on this endpoint is always "0" (it never
     * varies and retrying immediately keeps failing for tens of seconds), so
     * there is no reliable single-shot signal to derive an exact value from.
     * The interval never shrinks back down since there's no signal for when
     * that would be safe. onTurnComplete() fires this on every single turn, so
     * without a floor a fast multi-turn conversation hammers /api/oauth/usage
     * far faster than Anthropic allows.
     */
    private static final long INITIAL_USAGE_INTERVAL_MS = 30_000L;
    private static volatile long learnedUsageIntervalMs = INITIAL_USAGE_INTERVAL_MS;
    private static long OFFSET_MS = 10000L;

    public static AiModelCatalog modelCatalog() {
        return MODEL_CATALOG;
    }

    /**
     * Invoked by {@link ClaudeCredentialMonitor} when
     * ~/.claude/.credentials.json changes (the user ran {@code claude login}
     * after the plugin started). Resets model discovery state so the fresh
     * credentials are used to re-fetch models, and triggers usage fetch.
     */
    public static void onCredentialsChanged() {
        synchronized (MODEL_LOCK) {
            modelDiscoveryRetries = 0;
        }
        MODEL_CATALOG.invalidate();
        triggerModelDiscovery();
        fetchUsageAsync();
    }

    /**
     * Fires cached models to this session's dropdown immediately (if
     * available), then re-fetches from the API if the last successful fetch was
     * more than {@link #MODEL_REFRESH_INTERVAL_MS} ago. Only called once per
     * session (from {@link #registerLifecycleListeners}) and on credentials
     * change.
     */
    public static void triggerModelDiscovery() {
        AnthropicApiClient.refreshCredentialsState();
        if (!MODEL_CATALOG.beginRefresh()) {
            return;
        }
        synchronized (MODEL_LOCK) {
            modelDiscoveryRetries = 0;
        }
        submitModelFetch();
    }

    /**
     * Submits a model fetch to the rate-limit manager. Coalesced by the
     * {@code "models"} key so only one fetch runs at a time. On failure,
     * retries up to {@link #MAX_MODEL_DISCOVERY_RETRIES} times within the
     * current fetch cycle.
     */
    private static void submitModelFetch() {
        AnthropicApiClient.rateLimitManager().submitWhenClear("models", ClaudeAiImplementation::doFetchModels);
    }

    private static void doFetchModels() {
        try {
            List<String> modelList = new AnthropicApiClient().fetchModels();
            if (modelList != null && !modelList.isEmpty()) {
                synchronized (MODEL_LOCK) {
                    modelDiscoveryRetries = 0;
                }
                ClaudePluginSettings.setDiscoveredModels(modelList.toArray(String[]::new));
                if (MODEL_CATALOG.publish(modelList)) {
                    AiTypePropertyBus.getInstance().fire(AiTypeEnum.CLAUDE, new ClaudeModelsEvent(modelList));
                }
            }
            else {
                MODEL_CATALOG.refreshFailed();
            }
        }
        catch (Exception e) {
            int attempt;
            synchronized (MODEL_LOCK) {
                attempt = ++modelDiscoveryRetries;
            }
            LOG.log(Level.WARNING, "Model discovery failed (attempt {0}/{1}): {2}",
                    new Object[]{attempt, MAX_MODEL_DISCOVERY_RETRIES, e.getMessage()});
            if (attempt < MAX_MODEL_DISCOVERY_RETRIES) {
                submitModelFetch();
            }
            else {
                MODEL_CATALOG.refreshFailed();
            }
        }
    }

    private static void fetchUsageAsync() {
        long now = System.currentTimeMillis();
        if (shouldThrottleUsageFetch(now, lastUsageFetchAttemptMs, learnedUsageIntervalMs)) {
            return;
        }
        lastUsageFetchAttemptMs = now;
        AnthropicApiClient.rateLimitManager().submitWhenClear("usage", () -> {
            try {
                AnthropicApiClient.UsageData data = new AnthropicApiClient().fetchUsage();
                ClaudeUsageEvent event = new ClaudeUsageEvent(data.fiveHourPct(), data.sevenDayPct());
                cachedUsageEvent = event;
                AiTypePropertyBus.getInstance().fire(AiTypeEnum.CLAUDE, event);
            }
            catch (Exception e) {
                recordUsageFetch429IfApplicable();
                LOG.log(Level.WARNING, "Usage fetch failed: {0}", e.getMessage());
            }
        });
    }

    /**
     * True when the last usage-fetch attempt was recent enough that a new one
     * would almost certainly retrigger the same server-side rate limit already
     * learned about — skips the network call entirely rather than hitting the
     * endpoint and eating another 429/backoff cycle. Package- visible (not
     * private) purely so it's unit-testable as a pure function.
     */
    static boolean shouldThrottleUsageFetch(long now, long lastAttemptMs, long learnedIntervalMs) {
        return learnedIntervalMs > 0 && now - lastAttemptMs < learnedIntervalMs;
    }

    /**
     * Called on a failed usage fetch. Only grows the throttle when the failure
     * was actually the rate limiter tripping (not some other network/parse
     * error) — {@code RateLimitManager.isRateLimited()} is true immediately
     * after {@code AnthropicApiClient.get()} calls {@code setRateLimit()} on a
     * 429, so this reliably distinguishes a rate-limit failure from any other
     * exception without needing to parse the exception message. Each 429 adds
     * another {@link #OFFSET_MS} step — a fresh attempt only happens after
     * waiting at least the current learned interval (see
     * {@link #shouldThrottleUsageFetch}), so a 429 here means that interval
     * still wasn't long enough and needs to grow further.
     */
    private static void recordUsageFetch429IfApplicable() {
        if (AnthropicApiClient.rateLimitManager().isRateLimited()) {
            learnedUsageIntervalMs += OFFSET_MS;
            LOG.log(Level.INFO, "Usage fetch rate limited again — growing throttle interval to {0}ms", learnedUsageIntervalMs);
        }
    }

    private final ClaudeAiProcessManager delegate;

    public ClaudeAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.CLAUDE, listener, prompter);
        this.delegate = new ClaudeAiProcessManager(listener);
    }

    @Override
    protected ClaudeAiProcessManager delegate() {
        return delegate;
    }

    public String getCurrentModel() {
        if (currentSession != null && currentSession.settings() instanceof AiModelSessionSettings mc && mc.model() != null) {
            return mc.model();
        }
        return ClaudePluginSettings.getModel();
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = (model != null && !model.isBlank()) ? model : getCurrentModel();
        String execPath = ClaudeExecutableLocator.locate();
        if (execPath != null) {
            // Executable was found: start exactly once. start() reports success or
            // failure via events. We must NOT fall through to a second start() here —
            // a failure unrelated to the executable (e.g. MCP port bind) would
            // otherwise trigger a duplicate MCP server start on the same port.
            // start() can block on MCP registration, so run it off the EDT.
            start(execPath, effectiveModel);
            return;
        }

        String chosen;
        try {
            chosen = prompter.promptForExecutable("Locate claude executable", "claude").get();
        }
        catch (Exception ex) {
            chosen = null;
        }
        if (chosen != null) {
            ClaudePluginSettings.setExecutable(chosen);
            start(chosen, effectiveModel);
        }
        else {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, StatusMessageUtil.formatExecutableNotFound(null)));
        }
    }

    private void applySessionPaths() {
        Path configPath = getSessionConfigPath();
        if (configPath != null) {
            delegate.setSessionConfigDir(configPath);
        }
    }

    /**
     * Run after every {@code delegate.start()}. Applies session paths, then —
     * if this session already exists in Claude's on-disk store — switches the
     * freshly started manager to RESUME it. start() always defaults to
     * create-via {@code --session-id}, which the Claude CLI rejects for an id
     * that already exists, so the process exits immediately. That is why an
     * in-place restart of a dead session "sends but dies again": every other
     * start path (componentOpened) reaches resumeSession() via loadHistory(),
     * but the resend-into-dead-session path did not. resumeSession() flips the
     * next turn to {@code --resume}, so a restart behaves like reopening the
     * tab.
     */
    @Override
    protected void afterStart() {
        applySessionPaths();
        if (currentSession != null && isStoredSessionValid(currentSession.id())) {
            delegate.resumeSession(currentSession.id());
        }
    }

    @Override
    public void setModel(String model) {
        ClaudePluginSettings.setModel(model);
        delegate.setModel(model);
        delegate.recycleForModelChange();
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        Path projectsDir = Path.of(System.getProperty("user.home"), ".claude", "projects");
        if (!Files.isDirectory(projectsDir)) {
            return false;
        }
        String target = sessionId + ".jsonl";
        try (Stream<Path> dirs = Files.list(projectsDir)) {
            return dirs.filter(Files::isDirectory)
                    .anyMatch(dir -> Files.exists(dir.resolve(target)));
        }
        catch (IOException e) {
            return false;
        }
    }

    public List<String> getDefaultModels() {
        return Arrays.asList(ClaudePluginSettings.KNOWN_MODELS);
    }

    @Override
    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        ClaudeAiInfoBarExtension provider = new ClaudeAiInfoBarExtension();
        Consumer<List<String>> catalogListener = provider::setAvailableModels;
        MODEL_CATALOG.addListener(catalogListener);
        provider.setDisposeAction(() -> MODEL_CATALOG.removeListener(catalogListener));
        provider.addListener(new ClaudeInfoBarListener() {
            @Override
            public void onCompactRequested() {
                compact(host);
            }

            @Override
            public void onModelChanged(String model) {
            }
        });
        provider.addModelChangeListener(e -> {
            String model = provider.getSelectedModel();
            if (model == null) {
                return;
            }
            setModel(model);
            AiSessionSettings cfg = host.getSessionSettings();
            String currentModel = cfg instanceof AiModelSessionSettings mc ? mc.model() : null;
            if (!model.equals(currentModel)) {
                if (cfg instanceof AiModelSessionSettings modelCfg) {
                    modelCfg.setModel(model);
                }
                delegate.setCurrentSession(currentSession);
                host.updateSessionSettings(cfg);
            }
        });
        String initialModel = session.settings() instanceof AiModelSessionSettings modelCfg && modelCfg.model() != null
                ? modelCfg.model() : ClaudePluginSettings.getModel();
        provider.setSelectedModel(initialModel);
        if (initialModel != null && session.settings() instanceof AiModelSessionSettings modelSettings && modelSettings.model() == null) {
            modelSettings.setModel(initialModel);
            host.updateSessionSettings(modelSettings);
        }
        return provider;
    }

    private void compact(AiSessionHost host) {
        if (!isRunning() || isProcessing()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Wait for Claude to finish before compacting"));
            return;
        }
        sendPrompt("/compact", host.resolveWorkDir(), List.of());
        if (isProcessing()) {
            host.suppressNextTurn("Compacting conversation...", null);
        }
    }

    @Override
    public void onTabActivated() {
        fetchUsageAsync();
    }

    @Override
    public void registerLifecycleListeners(SessionLifecycleSource source) {
        source.addListener(new SessionLifecycleListener() {
            @Override
            public void onSessionStarted() {
                fetchUsageAsync();
            }

            @Override
            public void onTurnComplete() {
                fetchUsageAsync();
            }
        });
        triggerModelDiscovery();
        triggerUsageReplay();
    }

    private void triggerUsageReplay() {
        ClaudeUsageEvent cached = cachedUsageEvent;
        if (cached != null) {
            AiTypePropertyBus.getInstance().fire(AiTypeEnum.CLAUDE, cached);
        }
    }

    @Override
    public void onStarted(AiSessionHost session) {
    }
}
