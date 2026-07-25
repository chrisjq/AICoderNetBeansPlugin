package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

public final class GrokCreateSettingsPanel extends ModelCreateSettingsPanel<GrokSessionSettings> {

    public GrokCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.GROK, catalog, GrokSessionSettings::model, GrokSessionSettings::setModel);
    }

    @Override
    public void startLoading() {
        GrokAiImplementation.triggerModelDiscovery();
    }
}
