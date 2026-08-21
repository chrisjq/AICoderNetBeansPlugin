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
        String modeKey = OpenCodeSessionSettingsKeyEnum.MODE.key();
        String effortKey = OpenCodeSessionSettingsKeyEnum.EFFORT.key();
        String acpSessionIdKey = OpenCodeSessionSettingsKeyEnum.ACP_SESSION_ID.key();
        if (cfgObj.has(modeKey) && !cfgObj.get(modeKey).isJsonNull()) {
            settings.setMode(cfgObj.get(modeKey).getAsString());
        }
        if (cfgObj.has(effortKey) && !cfgObj.get(effortKey).isJsonNull()) {
            settings.setEffort(cfgObj.get(effortKey).getAsString());
        }
        if (cfgObj.has(acpSessionIdKey) && !cfgObj.get(acpSessionIdKey).isJsonNull()) {
            settings.setAcpSessionId(cfgObj.get(acpSessionIdKey).getAsString());
        }
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
    }

}
