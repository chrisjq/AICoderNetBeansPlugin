package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.OpenAiClientSettingsCreator;

public class OllamaSettingsCreator extends OpenAiClientSettingsCreator<OllamaSessionSettings> {

    @Override
    public OllamaSessionSettings create() {
        return new OllamaSessionSettings();
    }

    @Override
    public AiSessionCreateSettingsPanel<OllamaSessionSettings> createSettingsPanel() {
        return new OllamaCreateSettingsPanel(OllamaAiImplementation.modelCatalog());
    }

    @Override
    public void update(OllamaSessionSettings settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        String key = OllamaSessionSettingsKeyEnum.BASE_URL.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setBaseUrl(cfgObj.get(key).getAsString());
        }
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
        if (settings instanceof OllamaSessionSettings ollama) {
            ollama.setBaseUrl(OllamaPluginSettings.getBaseUrl());
        }
    }
}
