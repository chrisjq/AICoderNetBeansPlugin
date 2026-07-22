package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import java.util.Arrays;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaCapabilityHintEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui.OllamaAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;

public class OllamaAiImplementation extends AiImplementation {

    private final OllamaAiProcessManager processManager;

    public OllamaAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        this(AiTypeEnum.OLLAMA_LOCAL, listener, prompter);
    }

    protected OllamaAiImplementation(AiTypeEnum type, AiProcessEventListener listener,
            ExecutablePrompter prompter) {
        super(type, listener, prompter);
        this.processManager = createProcessManager(listener);
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

    protected void persistModel(String model) {
        OllamaPluginSettings.setModel(model);
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
        persistModel(model);
        delegate().setModel(model);
        if (currentSession != null && currentSession.settings() instanceof OllamaSessionSettings settings) {
            settings.setModel(model);
        }
    }

    @Override
    public OllamaAiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        OllamaAiInfoBarExtension ext = new OllamaAiInfoBarExtension();
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
        triggerModelDiscovery();
        triggerCapabilityDiscovery(initialModel);
        return ext;
    }

    private void triggerModelDiscovery() {
        String baseUrl = currentSession != null && currentSession.settings() instanceof OllamaSessionSettings settings
                && settings.baseUrl() != null && !settings.baseUrl().isBlank()
                ? settings.baseUrl()
                : defaultBaseUrl();
        OllamaModelDiscovery.discoverAsync(baseUrl,
                models -> {
                    storeDiscoveredModels(models);
                    AiTypePropertyBus.getInstance().fire(type,
                            new OllamaModelsEvent(Arrays.asList(models)));
                },
                hint -> {
                    if (hint != null) {
                        AiTypePropertyBus.getInstance().fire(type,
                                new OllamaCapabilityHintEvent(hint));
                    }
                });
    }

    private void triggerCapabilityDiscovery(String model) {
        String baseUrl = currentSession != null && currentSession.settings() instanceof OllamaSessionSettings settings
                && settings.baseUrl() != null && !settings.baseUrl().isBlank()
                ? settings.baseUrl()
                : defaultBaseUrl();
        OllamaModelDiscovery.probeCapabilityAsync(baseUrl, model,
                hint -> AiTypePropertyBus.getInstance().fire(type,
                        new OllamaCapabilityHintEvent(hint)));
    }

    @Override
    public void onStarted(AiSessionHost session) {
    }

    @Override
    protected void afterStart() {
    }
}
