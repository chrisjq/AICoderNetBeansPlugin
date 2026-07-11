package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;

/**
 * Intermediate settings creator for model-based AI implementations. Extends
 * base session settings handling to include model-specific configuration.
 * Subclasses handle implementation-specific models (Claude, Grok, GitHub
 * Copilot).
 */
public abstract class AiModelSessionSettingsCreator<E extends AiModelSessionSettings> extends AiSessionSettingsCreator<E> {

    @Override
    public void update(E settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        String modelKey = AiModelSessionSettingsKeyEnum.MODEL.key();
        if (cfgObj.has(modelKey) && cfgObj.get(modelKey).isJsonPrimitive()) {
            settings.setModel(cfgObj.get(modelKey).getAsString());
        }
    }
}
