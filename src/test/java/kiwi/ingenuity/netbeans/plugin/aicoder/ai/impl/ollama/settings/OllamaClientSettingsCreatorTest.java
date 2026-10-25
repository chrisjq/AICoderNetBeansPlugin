package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTrimStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSettingsCreator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class OllamaClientSettingsCreatorTest {

    @Test
    void contextKeysRoundTripThroughSaveAndLoad() {
        OllamaSessionSettings original = new OllamaSessionSettings();
        original.setContextTrimTrigger(OllamaContextTriggerEnum.REPORTED_TOKENS);
        original.setContextTrimStrategy(OllamaContextTrimStrategyEnum.DROP);
        original.setContextTokenThreshold(4321);
        original.setContextTrimTargetPercent(60);
        original.setContextMaxMessages(7);
        original.setContextPersistOnClose(true);

        JsonObject cfg = new JsonObject();
        original.populateJsonObject(cfg);

        OllamaSessionSettings loaded = new OllamaSessionSettings();
        new OllamaSettingsCreator().update(loaded, cfg);

        assertEquals(OllamaContextTriggerEnum.REPORTED_TOKENS, loaded.contextTrimTrigger());
        assertEquals(OllamaContextTrimStrategyEnum.DROP, loaded.contextTrimStrategy());
        assertEquals(4321, loaded.contextTokenThreshold());
        assertEquals(60, loaded.contextTrimTargetPercent());
        assertEquals(7, loaded.contextMaxMessages());
        assertEquals(true, loaded.contextPersistOnClose());
    }

    @Test
    void unknownEnumTextIsIgnoredRatherThanThrowing() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("contextTrimStrategy", "NOT_A_REAL_STRATEGY");

        OllamaSessionSettings loaded = new OllamaSessionSettings();
        new OllamaSettingsCreator().update(loaded, cfg);

        assertEquals(null, loaded.contextTrimStrategy(),
                     "a settings file edited by hand must not break session loading");
    }
}
