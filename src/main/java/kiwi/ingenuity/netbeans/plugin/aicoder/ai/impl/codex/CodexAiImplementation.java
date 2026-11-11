package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.TerminalLaunch;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexRateLimitEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.ui.CodexAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

public class CodexAiImplementation extends AiImplementation {

    private static final Logger LOG = Logger.getLogger(CodexAiImplementation.class.getName());
    private static final AiModelCatalog MODEL_CATALOG = new AiModelCatalog(AiTypeEnum.CODEX);
    private static volatile CodexRateLimitEvent cachedRateLimitEvent;
    /**
     * How long a failed or empty discovery stops the panels from re-running it (spec: no retry per panel build).
     */
    private static final long DISCOVERY_FAILURE_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(60L);
    /**
     * When the last discovery failed, from {@link System#nanoTime()} (monotonic); null when no backoff is recorded.
     */
    private static volatile Long lastDiscoveryFailureNanos;

    /**
     * Tests only. Stops {@link #triggerModelDiscovery} from launching the real CLI. It is public because the UI and
     * settings tests live in other packages and still show panels that start a discovery on display.
     */
    public static volatile boolean discoveryDisabledForTests;

    /**
     * Notes a failed or empty discovery, so the next trigger within the backoff window is skipped.
     */
    static void recordDiscoveryFailure() {
        lastDiscoveryFailureNanos = System.nanoTime();
    }

    /**
     * Ends the backoff after a successful discovery.
     */
    static void clearDiscoveryBackoff() {
        lastDiscoveryFailureNanos = null;
    }

    /**
     * Test seam: pretends the last recorded failure happened {@code millisAgo} milliseconds ago.
     */
    static void backdateDiscoveryFailureForTests(long millisAgo) {
        lastDiscoveryFailureNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(millisAgo);
    }

    static boolean discoveryBackingOff() {
        Long failed = lastDiscoveryFailureNanos;
        return failed != null && System.nanoTime() - failed < DISCOVERY_FAILURE_BACKOFF_NANOS;
    }

    public static AiModelCatalog modelCatalog() {
        return MODEL_CATALOG;
    }

    /**
     * Starts a best-effort refresh of the models available to the logged-in Codex CLI account.
     */
    public static void triggerModelDiscovery() {
        if (discoveryDisabledForTests || discoveryBackingOff() || !MODEL_CATALOG.beginRefresh()) {
            return;
        }
        String executable = CodexExecutableLocator.locate();
        if (executable == null) {
            MODEL_CATALOG.refreshFailed();
            return;
        }
        CodexModelDiscovery.discoverAsync(executable, CodexAiImplementation::applyDiscoveryResult);
    }

    /**
     * Applies one discovery outcome: an empty result starts the backoff and ends the refresh; a complete list is
     * published, persisted and clears the backoff.
     */
    static void applyDiscoveryResult(List<String> models) {
        if (models == null || models.isEmpty()) {
            recordDiscoveryFailure();
            MODEL_CATALOG.refreshFailed();
        }
        else {
            MODEL_CATALOG.publish(models);
            CodexPluginSettings.setDiscoveredModels(models);
            clearDiscoveryBackoff();
        }
    }

    public static void publishRateLimit(CodexRateLimitEvent event) {
        cachedRateLimitEvent = event;
        AiTypePropertyBus.getInstance().fire(AiTypeEnum.CODEX, event);
    }

    private final CodexAiProcessManager delegate;
    private volatile AiSessionHost sessionHost;

    public CodexAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.CODEX, listener, prompter);
        this.delegate = new CodexAiProcessManager(listener);
    }

    @Override
    protected CodexAiProcessManager delegate() {
        return delegate;
    }

    @Override
    public Optional<String> terminalExecutableName() {
        return Optional.of("codex");
    }

    @Override
    public Optional<TerminalLaunch> terminalLaunch() {
        String executablePath = CodexExecutableLocator.locate();
        return executablePath == null ? Optional.empty()
               : terminalExecutableName().map(name -> new TerminalLaunch(name, executablePath, true));
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = resolveStartupModel(model);
        String execPath = CodexExecutableLocator.locate();
        if (execPath != null) {
            start(execPath, effectiveModel);
            return;
        }
        String chosen;
        try {
            chosen = prompter.promptForExecutable("Locate codex executable", "codex").get();
        }
        catch (Exception ex) {
            chosen = null;
        }
        if (chosen != null) {
            CodexPluginSettings.setExecutable(chosen);
            start(chosen, effectiveModel);
        }
        else {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                                                      StatusMessageUtil.formatExecutableNotFound(null)));
        }
    }

    /**
     * Resolves the model to start a session with: an explicit argument first, then the session's own choice, then the
     * global default. Mirrors OpenCodeAiImplementation.resolveStartupModel — that method exists because an earlier
     * version fell straight to the global default here, silently running every session on it regardless of what the
     * user picked per session. AiTopComponent always calls {@code startWithDiscovery(null)}, so session settings must
     * be checked before the global default from the start, not discovered as a bug later.
     */
    String resolveStartupModel(String model) {
        String sessionModel = currentSession != null
                && currentSession.settings() instanceof CodexSessionSettings s
                && s.model() != null && !s.model().isBlank()
                              ? s.model() : null;
        String effectiveModel = (model != null && !model.isBlank())
                                ? model
                                : sessionModel != null ? sessionModel : CodexPluginSettings.getModel();
        if (PluginSettings.isDebugJson()) {
            String source = (model != null && !model.isBlank()) ? "explicit argument"
                            : sessionModel != null ? "session setting" : "global default";
            LOG.log(Level.INFO, "Codex requested model=\"{0}\" at session start (source: {1})",
                    new Object[]{effectiveModel, source});
        }
        return effectiveModel;
    }

    @Override
    public void setModel(String model) {
        // Session-scoped change — deliberately does not write the global default.
        // The global default (Tools → Options) is owned solely by the settings panel.
        delegate().setModel(model);
        if (currentSession != null && currentSession.settings() instanceof CodexSessionSettings settings) {
            settings.setModel(CodexPluginSettings.toStoredModel(model));
        }
        // Unlike OpenCode, there is no live config-option call to also push this
        // into an already-running thread — Codex's model is only settable at
        // thread/start or thread/resume time (ThreadStartParams.model).
        // A model change while a thread is already live takes effect on the next
        // spawn. (Reasoning effort is different: it is a per-turn
        // TurnStartParams.effort override, sent with the next turn.)
    }

    @Override
    public void resumeSession(String sessionId) {
        // AiTopComponent.loadHistory() passes the PLUGIN session id, which is
        // meaningless to Codex — the agent mints its own thread id, persisted in
        // CodexSessionSettings.threadId(). Ignore the argument and always resume
        // from the stored thread id (or not at all).
        String stored = currentSession != null
                && currentSession.settings() instanceof CodexSessionSettings
                        ? ((CodexSessionSettings) currentSession.settings()).threadId()
                        : null;
        if (stored != null && !stored.isBlank()) {
            delegate().resumeSession(stored);
        }
        // else: do nothing — a fresh thread/start is correct
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        // thread/resume is attempted first with fallback to thread/start, so a
        // stored thread is always safe to load — a stale id never blocks the user.
        return true;
    }

    @Override
    protected void afterStart() {
        Path configPath = getSessionConfigPath();
        if (configPath != null) {
            delegate.setSessionConfigDir(configPath);
        }
        if (currentSession != null && currentSession.settings() instanceof CodexSessionSettings) {
            String storedThreadId = ((CodexSessionSettings) currentSession.settings()).threadId();
            if (storedThreadId != null) {
                delegate.resumeSession(storedThreadId);
            }
        }
    }

    /**
     * Seeds the combo from the SESSION's own model (not {@code CodexPluginSettings.getModel()}'s global default) — the
     * trap this project already hit once for OpenCode's info bar. The {@code AvailableModelsEvent} from
     * {@code AiTypePropertyBus} refreshes the shared model list, and each info bar keeps its own selection. A blank
     * session model displays the global default. Model changes flow back through {@code setModel()} (session-scoped,
     * per its own doc) and {@code host.updateSessionSettings()}; see {@link CodexAiInfoBarExtension}'s own javadoc.
     */
    @Override
    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        String initialModel = session != null && session.settings() instanceof CodexSessionSettings s
                              ? s.model() : null;
        CodexAiInfoBarExtension ext = new CodexAiInfoBarExtension(initialModel);
        List<String> cachedModels = List.of(CodexPluginSettings.getKnownModels());
        if (!cachedModels.isEmpty()) {
            ext.onPropertyEvent(new AvailableModelsEvent(cachedModels));
        }
        ext.addModelChangeListener(e -> {
            String selected = ext.getSelectedModel();
            setModel(selected);
            if (host != null && currentSession != null) {
                host.updateSessionSettings(currentSession.settings());
            }
        });
        ext.addEffortChangeListener(e -> {
            if (host != null && currentSession != null
                    && currentSession.settings() instanceof CodexSessionSettings s) {
                s.setEffort(ext.getSelectedEffort());
                host.updateSessionSettings(s);
            }
        });
        ext.addCompactListener(this::compact);
        CodexRateLimitEvent cached = cachedRateLimitEvent;
        if (cached != null) {
            ext.onPropertyEvent(cached);
        }
        return ext;
    }

    /**
     * The Compact button's handler. The bar disables the button while busy, so the guards here only cover the cases the
     * bar cannot know about: no conversation yet (Codex spawns lazily on the first prompt) and a compaction the core
     * started elsewhere.
     * <p>
     * Known limitation: a conversation restored after an IDE restart is only resumed ({@code thread/resume}) on its
     * first prompt, so until then {@code threadId()} is null and Compact refuses with "send a message first" even
     * though there is history to compact. Compacting it would mean spawning and resuming here first; deliberately left
     * as is.
     */
    void compact() {
        if (delegate.threadId() == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                                      "Nothing to compact yet — send a message first"));
            return;
        }
        if (delegate.isProcessing()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                                      "Wait for Codex to finish before compacting"));
            return;
        }
        if (delegate.isStoppedTurnWindingDown()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                                      "Wait for Codex to finish the turn you stopped, then compact again"));
            return;
        }
        if (!delegate.compact()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO, "Compaction already in progress"));
        }
    }

    @Override
    public void onStarted(AiSessionHost host) {
        this.sessionHost = host;
        delegate.setOnSessionEstablished(() -> {
            AiSessionHost h = sessionHost;
            AiSession s = currentSession;
            if (h != null && s != null) {
                h.updateSessionSettings(s.settings());
            }
        });
    }
}
