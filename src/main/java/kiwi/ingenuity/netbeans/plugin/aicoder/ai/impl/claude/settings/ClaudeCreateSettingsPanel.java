package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

public final class ClaudeCreateSettingsPanel extends ModelCreateSettingsPanel<ClaudeSessionSettings> {

    public ClaudeCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.CLAUDE, catalog, ClaudeSessionSettings::model, ClaudeSessionSettings::setModel);
    }

    @Override
    public void startLoading() {
        ClaudeAiImplementation.triggerModelDiscovery();
    }
}
