package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui.GrokAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Thin adapter so the generic multi-AI system (AiSession, AiTopComponent, etc.) can use the Grok (xAI) implementation.
 * Drives the {@code grok} CLI (https://docs.x.ai/build/cli) in headless mode via {@link GrokAiProcessManager}, reusing
 * the shared MCP tool server for IDE introspection, edits, builds, git, etc. — the same architecture as
 * {@code ClaudeAiImplementation}.
 */
public class GrokAiImplementation extends AiImplementation {

    // The discovered model list is shared across all Grok sessions for the IDE
    // run (like Claude/Copilot): discover once via `grok models`, cache, and
    // broadcast to every open session's dropdown via AiTypePropertyBus.
    private static final AiModelCatalog MODEL_CATALOG = new AiModelCatalog();

    public static AiModelCatalog modelCatalog() {
        return MODEL_CATALOG;
    }

    /**
     * Discover the Grok model list once per IDE run via {@code grok models}, then broadcast it to every open Grok
     * session's info bar via {@link AiTypePropertyBus} — mirroring the Claude/Copilot flow. A session opened after
     * discovery already completed replays the cached list immediately.
     */
    public static void triggerModelDiscovery() {
        if (!MODEL_CATALOG.beginRefresh()) {
            return;
        }
        GrokModelDiscovery.discoverAsync(GrokExecutableLocator.locate(), list -> {
                                     if (list != null && !list.isEmpty()) {
                                         GrokPluginSettings.setDiscoveredModels(list.toArray(String[]::new));
                                     }
                                     if (MODEL_CATALOG.publish(list)) {
                                         AiTypePropertyBus.getInstance().fire(AiTypeEnum.GROK, new GrokModelsEvent(list));
                                     }
                                 });
    }

    private final GrokAiProcessManager delegate;
    /**
     * Retained so {@link #clearInvalidPersistedReasoningEffort} can persist a clear even when it fires from deep in the
     * process manager's send path (a background thread, well after {@link #createInfoBarExtension} returned) — mirrors
     * {@code GithubCopilotAiImplementation}'s identical {@code sessionHost} field, kept for the same reason (a
     * write-back path needed outside {@code createInfoBarExtension}'s own call).
     */
    private volatile AiSessionHost sessionHost;

    public GrokAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.GROK, listener, prompter);
        this.delegate = new GrokAiProcessManager(listener);
        this.delegate.setOnReasoningEffortCleared(this::clearInvalidPersistedReasoningEffort);
    }

    @Override
    protected GrokAiProcessManager delegate() {
        return delegate;
    }

    public String getCurrentModel() {
        if (currentSession != null && currentSession.settings() instanceof AiModelSessionSettings mc && mc.model() != null) {
            return mc.model();
        }
        return GrokPluginSettings.getModel();
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = (model != null && !model.isBlank()) ? model : getCurrentModel();
        String execPath = GrokExecutableLocator.locate();
        if (execPath != null) {
            start(execPath, effectiveModel);
            return;
        }

        String chosen;
        try {
            chosen = prompter.promptForExecutable("Locate grok executable", "grok").get();
        }
        catch (Exception ex) {
            chosen = null;
        }
        if (chosen != null) {
            GrokPluginSettings.setExecutable(chosen);
            start(chosen, effectiveModel);
        }
        else {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, StatusMessageUtil.formatExecutableNotFound(null)));
        }
    }

    @Override
    public void setModel(String model) {
        // Session-scoped change — deliberately does not write the global default.
        // The global default (Tools → Options) is owned solely by the settings panel.
        if (currentSession != null && currentSession.settings() instanceof AiModelSessionSettings mc) {
            mc.setModel(model);
        }
        delegate.setModel(model);
    }

    public List<String> getDefaultModels() {
        return Arrays.asList(GrokPluginSettings.KNOWN_MODELS);
    }

    /**
     * The effective reasoning effort (session wins over global default) plus which scope it came from — spec §1 rule 3a
     * treats the two differently when the value turns out to be unsupported by the model: only a session-sourced value
     * is ever cleared, never the global default. {@code value()} is {@code null} when neither scope has one set.
     */
    private record EffectiveReasoningEffort(String value, boolean fromSession) {

    }

    private EffectiveReasoningEffort effectiveReasoningEffort() {
        if (currentSession != null && currentSession.settings() instanceof GrokSessionSettings gs
                && gs.reasoningEffort() != null && !gs.reasoningEffort().isBlank()) {
            return new EffectiveReasoningEffort(gs.reasoningEffort(), true);
        }
        String global = GrokPluginSettings.getReasoningEffort();
        return new EffectiveReasoningEffort((global != null && !global.isBlank()) ? global : null, false);
    }

    /**
     * Called back by {@link GrokAiProcessManager#buildReasoningEffortArgs} only when it clears an unsupported
     * SESSION-sourced value from its own in-memory field (never for a global-sourced one — spec §1 rule 3a: the global
     * default is never modified automatically), so the PERSISTED session setting is also cleared — otherwise the next
     * session start re-reads the same stale value from disk and re-triggers the same INFO event forever (review
     * finding). Package-private for direct unit testing.
     */
    void clearInvalidPersistedReasoningEffort() {
        if (currentSession != null && currentSession.settings() instanceof GrokSessionSettings gs) {
            gs.setReasoningEffort(null);
            AiSessionHost host = sessionHost;
            if (host != null) {
                host.updateSessionSettings(gs);
            }
        }
    }

    @Override
    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        this.sessionHost = host;
        GrokAiInfoBarExtension provider = new GrokAiInfoBarExtension();
        Consumer<List<String>> catalogListener = provider::setAvailableModels;
        MODEL_CATALOG.addListener(catalogListener);
        provider.setDisposeAction(() -> MODEL_CATALOG.removeListener(catalogListener));
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
                              ? modelCfg.model() : GrokPluginSettings.getModel();
        provider.setSelectedModel(initialModel);
        if (initialModel != null && session.settings() instanceof AiModelSessionSettings modelSettings && modelSettings.model() == null) {
            modelSettings.setModel(initialModel);
            host.updateSessionSettings(modelSettings);
        }
        // Deliberately reads `session`/GrokPluginSettings, not effectiveReasoningEffort() (which reads
        // currentSession): createInfoBarExtension can run before currentSession is set on this instance (confirmed
        // by CodexAiImplementationTest's createInfoBarExtension_modelChangeListenerHandlesNullHostAndSession),
        // exactly like initialModel above uses session.settings() rather than currentSession for the same reason.
        String initialEffort = session.settings() instanceof GrokSessionSettings initialGrokCfg && initialGrokCfg.reasoningEffort() != null
                && !initialGrokCfg.reasoningEffort().isBlank()
                               ? initialGrokCfg.reasoningEffort() : GrokPluginSettings.getReasoningEffort();
        provider.setSelectedReasoningEffort((initialEffort != null && !initialEffort.isBlank()) ? initialEffort : null);
        provider.addReasoningEffortChangeListener(e -> {
            String effort = provider.getSelectedReasoningEffort();
            // Always session-scoped: a live pick here is persisted straight into the session's own settings below,
            // never the global default (that stays the Options tab's job) — so this is never the global-sourced case
            // configureReasoningEffort's fromSession=false branch exists for.
            delegate.configureReasoningEffort(effort, true);
            if (currentSession != null && currentSession.settings() instanceof GrokSessionSettings grokCfg) {
                grokCfg.setReasoningEffort(effort);
                host.updateSessionSettings(grokCfg);
            }
        });

        // Discover the real available model list (best-effort; falls back
        // silently to the hardcoded list). Discovery runs once per IDE run and
        // the result is broadcast to EVERY open Grok session's dropdown.
        triggerModelDiscovery();
        return provider;
    }

    @Override
    public void onStarted(AiSessionHost session) {
        // Retained even though createInfoBarExtension already sets this: onStarted always runs, so this covers a
        // session started without an info bar ever having been built for it — mirrors
        // GithubCopilotAiImplementation.onStarted's identical fallback assignment.
        this.sessionHost = session;
    }

    /**
     * Run after every {@code delegate.start()}. grok's {@code -s} flag (create a new headless session) is rejected by
     * the CLI if the id already exists on disk ({@code Error: Session ID <id> is already in
     * use.}, empirically confirmed) — but {@code start()} always defaults to create mode. If this session id already
     * exists in grok's on-disk store, switch the freshly started manager to resume it instead, so an in-place restart
     * or reopen of an existing session (e.g. on IDE restart, or reopening the chat tab) behaves like a resume rather
     * than failing outright on the next message.
     */
    @Override
    protected void afterStart() {
        EffectiveReasoningEffort effort = effectiveReasoningEffort();
        delegate.configureReasoningEffort(effort.value(), effort.fromSession());
        if (currentSession != null && isStoredSessionValid(currentSession.id())) {
            delegate.resumeSession(currentSession.id());
        }
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        return GrokUsageSignalsReader.sessionExists(sessionId);
    }
}
