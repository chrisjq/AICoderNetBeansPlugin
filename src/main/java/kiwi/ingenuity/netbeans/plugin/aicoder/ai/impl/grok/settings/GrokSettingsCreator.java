package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Creates and updates Grok-specific AI session settings. Handles instantiation
 * and configuration updates for Grok AI implementation.
 */
public class GrokSettingsCreator extends AiModelSessionSettingsCreator<GrokSessionSettings> {

    @Override
    public GrokSessionSettings create() {
        return new GrokSessionSettings();
    }

    @Override
    public AiSessionCreateSettingsPanel<GrokSessionSettings> createSettingsPanel() {
        return new GrokCreateSettingsPanel(GrokAiImplementation.modelCatalog());
    }

    @Override
    public void update(GrokSessionSettings settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        //Update specific settings
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
    }

}
