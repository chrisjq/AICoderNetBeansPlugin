package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaCapabilityHintEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui.OllamaAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui.OllamaInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

public class OllamaAiImplementation extends AiImplementation implements OllamaInfoBarListener {

    private static final AiModelCatalog MODEL_CATALOG = new AiModelCatalog();

    public static AiModelCatalog modelCatalog() {
        return MODEL_CATALOG;
    }

    public static void triggerModelDiscovery(String customBaseUrl) {
        String baseUrl = (customBaseUrl != null && !customBaseUrl.isBlank())
                         ? customBaseUrl
                         : OllamaPluginSettings.getBaseUrl();
        if (!MODEL_CATALOG.beginRefresh()) {
            return;
        }
        OllamaModelDiscovery.discoverAsync(baseUrl,
                                           models -> {
                                               List<String> list = Arrays.asList(models);
                                               OllamaPluginSettings.setDiscoveredModels(models);
                                               if (MODEL_CATALOG.publish(list)) {
                                                   AiTypePropertyBus.getInstance().fire(AiTypeEnum.OLLAMA_LOCAL, new OllamaModelsEvent(list));
                                               }
                                           },
                                           hint -> {
                                               if (hint != null) {
                                                   AiTypePropertyBus.getInstance().fire(AiTypeEnum.OLLAMA_LOCAL,
                                                                                        new OllamaCapabilityHintEvent(hint));
                                               }
                                           });
    }
    private final OllamaAiProcessManager processManager;
    /**
     * Retained so {@link #clearInvalidPersistedReasoningEffort} can persist a clear even when it fires from deep in the
     * process manager's send path (a background turn thread, well after {@link #createInfoBarExtension} or
     * {@link #onStarted} last ran) — mirrors {@code GrokAiImplementation}/{@code GithubCopilotAiImplementation}'s
     * identical {@code sessionHost} field, kept for the same reason.
     */
    private volatile AiSessionHost sessionHost;

    public OllamaAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        this(AiTypeEnum.OLLAMA_LOCAL, listener, prompter);
    }

    protected OllamaAiImplementation(AiTypeEnum type, AiProcessEventListener listener,
                                     ExecutablePrompter prompter) {
        super(type, listener, prompter);
        this.processManager = createProcessManager(listener);
        this.processManager.setOnReasoningEffortCleared(this::clearInvalidPersistedReasoningEffort);
    }

    /**
     * Wired to {@link OllamaAiProcessManager#setOnReasoningEffortCleared}: fires only for a SESSION-sourced value (spec
     * §1 rule 3a — {@link OllamaAiProcessManager#applyThinkingCapabilityValidation} never invokes this for a
     * global-sourced one), so no scope resolution is needed here — just clear whatever the session currently has
     * pinned. Package-private for direct unit testing, mirroring {@code GrokAiImplementation}'s identical method.
     */
    void clearInvalidPersistedReasoningEffort() {
        if (currentSession != null && currentSession.settings() instanceof OllamaSessionSettings gs) {
            gs.setReasoningEffort(null);
            AiSessionHost host = sessionHost;
            if (host != null) {
                host.updateSessionSettings(gs);
            }
        }
    }

    /**
     * Base URL for the current session (its own override, if set) or the global default — shared by discovery triggers
     * and the info bar's live capability lookups, so there is exactly one place this precedence lives.
     */
    private String resolveBaseUrl() {
        return currentSession != null && currentSession.settings() instanceof OllamaSessionSettings settings
                && settings.baseUrl() != null && !settings.baseUrl().isBlank()
               ? settings.baseUrl()
               : defaultBaseUrl();
    }

    protected OllamaAiProcessManager createProcessManager(AiProcessEventListener listener) {
        return new OllamaAiProcessManager(listener);
    }

    protected String defaultModel() {
        return OllamaPluginSettings.getModel();
    }

    protected String defaultBaseUrl() {
        return OllamaPluginSettings.getBaseUrl();
    }

    protected void storeDiscoveredModels(String[] models) {
        OllamaPluginSettings.setDiscoveredModels(models);
    }

    @Override
    protected OllamaAiProcessManager delegate() {
        return processManager;
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = model != null && !model.isBlank()
                                ? model
                                : currentSession != null && currentSession.settings() instanceof OllamaSessionSettings os
                && os.model() != null
                                  ? os.model()
                                  : defaultModel();
        start(null, effectiveModel);
    }

    @Override
    public void setModel(String model) {
        // Session-scoped change — deliberately does not write the global default.
        // The global default (Tools → Options) is owned solely by the settings panel.
        delegate().setModel(model);
        if (currentSession != null && currentSession.settings() instanceof OllamaSessionSettings settings) {
            settings.setModel(model);
        }
    }

    @Override
    public OllamaAiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        this.sessionHost = host;
        OllamaAiInfoBarExtension ext = new OllamaAiInfoBarExtension();
        ext.setBaseUrl(session.settings() instanceof OllamaSessionSettings baseUrlSettings
                && baseUrlSettings.baseUrl() != null && !baseUrlSettings.baseUrl().isBlank()
                       ? baseUrlSettings.baseUrl()
                       : defaultBaseUrl());
        ext.setProcessingSupplier(delegate()::isProcessing);
        ext.setSummarisingSupplier(delegate()::isSummarising);
        ext.addListener(this);
        Consumer<List<String>> catalogListener = models -> ext.setAvailableModels(models.toArray(String[]::new));
        MODEL_CATALOG.addListener(catalogListener);
        ext.setDisposeAction(() -> MODEL_CATALOG.removeListener(catalogListener));
        String initialModel = session.settings() instanceof OllamaSessionSettings settings
                && settings.model() != null
                              ? settings.model()
                              : defaultModel();
        ext.setSelectedModel(initialModel);
        ext.addModelChangeListener(e -> {
            String selected = ext.getSelectedModel();
            if (selected == null || selected.isBlank()) {
                return;
            }
            setModel(selected);
            AiSessionSettings cfg = host.getSessionSettings();
            if (cfg instanceof OllamaSessionSettings ollama && !selected.equals(ollama.model())) {
                ollama.setModel(selected);
                host.updateSessionSettings(ollama);
            }
            triggerCapabilityDiscovery(selected);
        });
        // Display only, like Grok's and Copilot's info bars: falls back to the global default so the combo shows
        // what will actually be used, but the fallback is never written back into the session's own settings —
        // "inherits the global" and "pinned to this session" must stay distinguishable.
        String sessionReasoningEffort = session.settings() instanceof OllamaSessionSettings ollamaSettings
                                        ? ollamaSettings.reasoningEffort()
                                        : null;
        String initialReasoningEffort = sessionReasoningEffort != null && !sessionReasoningEffort.isBlank()
                                        ? sessionReasoningEffort
                                        : OllamaPluginSettings.getReasoningEffort();
        ext.setSelectedReasoningEffort(initialReasoningEffort);
        ext.addReasoningEffortChangeListener(e -> {
            String selected = ext.getSelectedReasoningEffort();
            AiSessionSettings cfg = host.getSessionSettings();
            if (cfg instanceof OllamaSessionSettings ollama) {
                ollama.setReasoningEffort(selected);
                host.updateSessionSettings(ollama);
            }
        });
        triggerModelDiscovery();
        triggerCapabilityDiscovery(initialModel);
        return ext;
    }

    private void triggerModelDiscovery() {
        triggerModelDiscovery(resolveBaseUrl());
    }

    private void triggerCapabilityDiscovery(String model) {
        OllamaModelDiscovery.probeCapabilityAsync(resolveBaseUrl(), model,
                                                  hint -> AiTypePropertyBus.getInstance().fire(type,
                                                                                               new OllamaCapabilityHintEvent(hint)));
    }

    @Override
    public void onStarted(AiSessionHost session) {
        // Retained even though createInfoBarExtension already sets this: onStarted always runs, so this covers a
        // session started without an info bar ever having been built for it — mirrors
        // GithubCopilotAiImplementation.onStarted's identical fallback assignment.
        this.sessionHost = session;
    }

    @Override
    protected void afterStart() {
    }

    @Override
    public void onClearRequested() {
        delegate().clearContext();
    }

    @Override
    public void onCompactRequested() {
        delegate().compactContext();
    }
}
