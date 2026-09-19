package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Creates and updates Grok-specific AI session settings. Handles instantiation and configuration updates for Grok AI
 * implementation.
 */
public class GrokSettingsCreator extends AiModelSessionSettingsCreator<GrokSessionSettings> {

    @Override
    public GrokSessionSettings create() {
        return new GrokSessionSettings();
    }

    @Override
    public AiSessionCreateSettingsPanel<GrokSessionSettings> createSettingsPanel() {
        return new GrokCreateSettingsPanel(GrokAiImplementation.modelCatalog());
    }

    @Override
    public void update(GrokSessionSettings settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        String key = GrokSessionSettingsKeyEnum.REASONING_EFFORT.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setReasoningEffort(cfgObj.get(key).getAsString());
        }
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
    }

}
