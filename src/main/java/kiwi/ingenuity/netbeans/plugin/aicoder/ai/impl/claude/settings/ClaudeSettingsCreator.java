package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Creates and updates Claude-specific AI session settings. Handles
 * instantiation and configuration updates for Claude AI implementation.
 */
public class ClaudeSettingsCreator extends AiModelSessionSettingsCreator<ClaudeSessionSettings> {

    @Override
    public ClaudeSessionSettings create() {
        return new ClaudeSessionSettings();
    }

    @Override
    public AiSessionCreateSettingsPanel<ClaudeSessionSettings> createSettingsPanel() {
        return new ClaudeCreateSettingsPanel(ClaudeAiImplementation.modelCatalog());
    }

    @Override
    public void update(ClaudeSessionSettings settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        //Specific settings
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
    }

}
