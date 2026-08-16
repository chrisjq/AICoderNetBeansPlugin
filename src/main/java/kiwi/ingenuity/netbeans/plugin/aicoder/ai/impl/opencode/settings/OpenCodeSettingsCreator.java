package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Creates and updates OpenCode-specific AI session settings. Handles
 * instantiation and configuration updates for OpenCode AI implementation.
 */
public class OpenCodeSettingsCreator extends AiModelSessionSettingsCreator<OpenCodeSessionSettings> {

    @Override
    public OpenCodeSessionSettings create() {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        s.setMode(OpenCodePluginSettings.getMode());
        return s;
    }

    @Override
    public AiSessionCreateSettingsPanel<OpenCodeSessionSettings> createSettingsPanel() {
        return new OpenCodeCreateSettingsPanel(OpenCodeAiImplementation.modelCatalog());
    }

    @Override
    public void update(OpenCodeSessionSettings settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        if (cfgObj.has("mode") && !cfgObj.get("mode").isJsonNull()) {
            settings.setMode(cfgObj.get("mode").getAsString());
        }
        if (cfgObj.has("effort") && !cfgObj.get("effort").isJsonNull()) {
            settings.setEffort(cfgObj.get("effort").getAsString());
        }
        if (cfgObj.has("acpSessionId") && !cfgObj.get("acpSessionId").isJsonNull()) {
            settings.setAcpSessionId(cfgObj.get("acpSessionId").getAsString());
        }
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
    }

}
