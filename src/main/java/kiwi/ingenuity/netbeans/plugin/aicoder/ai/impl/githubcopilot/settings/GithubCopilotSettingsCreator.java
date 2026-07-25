package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.GithubCopilotAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Creates and updates GitHub Copilot-specific AI session settings. Handles
 * instantiation and configuration updates for GitHub Copilot AI implementation.
 */
public class GithubCopilotSettingsCreator extends AiModelSessionSettingsCreator<GithubCopilotSessionSettings> {

    @Override
    public GithubCopilotSessionSettings create() {
        return new GithubCopilotSessionSettings();
    }

    @Override
    public AiSessionCreateSettingsPanel<GithubCopilotSessionSettings> createSettingsPanel() {
        return new GithubCopilotCreateSettingsPanel(GithubCopilotAiImplementation.modelCatalog());
    }

    @Override
    public void update(GithubCopilotSessionSettings settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        //specific settings
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
    }

}
