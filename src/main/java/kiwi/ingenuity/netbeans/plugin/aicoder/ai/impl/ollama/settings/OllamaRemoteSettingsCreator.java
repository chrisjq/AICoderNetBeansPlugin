package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

public class OllamaRemoteSettingsCreator extends AiModelSessionSettingsCreator<OllamaRemoteSessionSettings> {

    @Override
    public OllamaRemoteSessionSettings create() {
        return new OllamaRemoteSessionSettings();
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
    }
}
