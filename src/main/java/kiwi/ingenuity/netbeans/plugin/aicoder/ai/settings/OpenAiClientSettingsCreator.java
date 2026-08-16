package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTrimStrategyEnum;

/**
 * Deserialises the shared context-broker keys. Sits between
 * AiModelSessionSettingsCreator and each OpenAI-compatible backend's creator,
 * so no backend duplicates this code.
 */
public abstract class OpenAiClientSettingsCreator<E extends OpenAiClientSessionSettings>
        extends AiModelSessionSettingsCreator<E> {

    /**
     * A hand-edited or downgraded settings file must not prevent a session from
     * loading, so an unrecognised value is dropped rather than thrown.
     */
    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public void update(E settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);

        String key = AiSessionSettingsKeyEnum.CONTEXT_TRIM_TRIGGER.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setContextTrimTrigger(
                    parseEnum(ContextTriggerEnum.class, cfgObj.get(key).getAsString()));
        }
        key = AiSessionSettingsKeyEnum.CONTEXT_TRIM_STRATEGY.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setContextTrimStrategy(
                    parseEnum(ContextTrimStrategyEnum.class, cfgObj.get(key).getAsString()));
        }
        key = AiSessionSettingsKeyEnum.CONTEXT_TOKEN_THRESHOLD.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setContextTokenThreshold(cfgObj.get(key).getAsInt());
        }
        key = AiSessionSettingsKeyEnum.CONTEXT_TRIM_TARGET_PERCENT.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setContextTrimTargetPercent(cfgObj.get(key).getAsInt());
        }
        key = AiSessionSettingsKeyEnum.CONTEXT_MAX_MESSAGES.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setContextMaxMessages(cfgObj.get(key).getAsInt());
        }
        key = AiSessionSettingsKeyEnum.CONTEXT_PERSIST_ON_CLOSE.key();
        if (cfgObj.has(key) && cfgObj.get(key).isJsonPrimitive()) {
            settings.setContextPersistOnClose(cfgObj.get(key).getAsBoolean());
        }
    }
}
