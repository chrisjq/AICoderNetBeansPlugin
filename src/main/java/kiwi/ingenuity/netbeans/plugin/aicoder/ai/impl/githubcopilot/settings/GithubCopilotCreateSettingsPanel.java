package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

import java.util.Arrays;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.GithubCopilotAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

public final class GithubCopilotCreateSettingsPanel extends ModelCreateSettingsPanel<GithubCopilotSessionSettings> {

    public GithubCopilotCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.GitHubCoPilot, catalog, GithubCopilotSessionSettings::model, GithubCopilotSessionSettings::setModel);
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(GithubCopilotPluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return GithubCopilotPluginSettings.getModel();
    }

    @Override
    public void startLoading() {
        GithubCopilotAiImplementation.triggerModelDiscovery();
    }
}
