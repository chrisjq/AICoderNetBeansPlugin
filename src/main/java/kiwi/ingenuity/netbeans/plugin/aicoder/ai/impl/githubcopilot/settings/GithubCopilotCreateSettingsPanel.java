package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.GithubCopilotAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

public final class GithubCopilotCreateSettingsPanel extends ModelCreateSettingsPanel<GithubCopilotSessionSettings> {

    public GithubCopilotCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.GitHubCoPilot, catalog, GithubCopilotSessionSettings::model, GithubCopilotSessionSettings::setModel);
    }

    @Override
    public void startLoading() {
        GithubCopilotAiImplementation.triggerModelDiscovery();
    }
}
