package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

import java.util.Arrays;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

public final class ClaudeCreateSettingsPanel extends ModelCreateSettingsPanel<ClaudeSessionSettings> {

    public ClaudeCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.CLAUDE, catalog, ClaudeSessionSettings::model, ClaudeSessionSettings::setModel);
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(ClaudePluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return ClaudePluginSettings.getModel();
    }

    @Override
    public void startLoading() {
        ClaudeAiImplementation.triggerModelDiscovery();
    }
}
