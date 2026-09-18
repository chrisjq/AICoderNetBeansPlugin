package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Creates and updates pi-specific AI session settings. Handles instantiation and configuration updates for the pi AI
 * implementation.
 */
public class PiSettingsCreator extends AiModelSessionSettingsCreator<PiSessionSettings> {

    @Override
    public PiSessionSettings create() {
        return new PiSessionSettings();
    }

    @Override
    public AiSessionCreateSettingsPanel<PiSessionSettings> createSettingsPanel() {
        return new PiCreateSettingsPanel(PiAiImplementation.modelCatalog());
    }

    @Override
    public void update(PiSessionSettings settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        String key = PiSessionSettingsKeyEnum.PI_SESSION_ID.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setPiSessionId(cfgObj.get(key).getAsString());
        }
        key = PiSessionSettingsKeyEnum.THINKING_LEVEL.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setThinkingLevel(cfgObj.get(key).getAsString());
        }
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
    }

}
