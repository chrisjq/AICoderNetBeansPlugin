package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import java.util.Arrays;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

public final class GrokCreateSettingsPanel extends ModelCreateSettingsPanel<GrokSessionSettings> {

    public GrokCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.GROK, catalog, GrokSessionSettings::model, GrokSessionSettings::setModel);
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(GrokPluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return GrokPluginSettings.getModel();
    }

    @Override
    public void startLoading() {
        GrokAiImplementation.triggerModelDiscovery();
    }
}
