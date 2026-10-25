package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTrimStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaClientSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class OllamaClientSessionSettingsTest {

    @Test
    void contextValuesSerialiseIntoTheSessionJson() {
        OllamaClientSessionSettings s = new OllamaClientSessionSettings();
        s.setContextTrimTrigger(OllamaContextTriggerEnum.REPORTED_TOKENS);
        s.setContextTrimStrategy(OllamaContextTrimStrategyEnum.DROP);
        s.setContextTokenThreshold(1234);
        s.setContextTrimTargetPercent(55);
        s.setContextMaxMessages(9);
        s.setContextPersistOnClose(true);

        JsonObject json = new JsonObject();
        s.populateJsonObject(json);

        assertEquals("REPORTED_TOKENS", json.get("contextTrimTrigger").getAsString());
        assertEquals("DROP", json.get("contextTrimStrategy").getAsString());
        assertEquals(1234, json.get("contextTokenThreshold").getAsInt());
        assertEquals(55, json.get("contextTrimTargetPercent").getAsInt());
        assertEquals(9, json.get("contextMaxMessages").getAsInt());
        assertEquals(true, json.get("contextPersistOnClose").getAsBoolean());
    }

    @Test
    void unsetValuesAreNullSoGlobalDefaultsCanApply() {
        OllamaClientSessionSettings s = new OllamaClientSessionSettings();

        JsonObject json = new JsonObject();
        s.populateJsonObject(json);

        assertFalse(json.has("contextTrimTrigger"),
                    "unset context values must be omitted from JSON so globals can apply");
        assertFalse(json.has("contextTrimStrategy"));
        assertFalse(json.has("contextTokenThreshold"));
        assertFalse(json.has("contextTrimTargetPercent"));
        assertFalse(json.has("contextMaxMessages"));
        assertFalse(json.has("contextPersistOnClose"));
    }

    @Test
    void effectiveAccessorsReturnGlobalDefaultsWhenFieldsAreNull() {
        OllamaClientSessionSettings s = new OllamaClientSessionSettings();

        assertEquals(OllamaContextTriggerEnum.ESTIMATED_TOKENS, s.effectiveContextTrimTrigger());
        assertEquals(OllamaContextTrimStrategyEnum.DROP_MARKED, s.effectiveContextTrimStrategy());
        assertEquals(0, s.effectiveContextTokenThreshold());
        assertEquals(70, s.effectiveContextTrimTargetPercent());
        assertEquals(0, s.effectiveContextMaxMessages());
        assertEquals(false, s.effectiveContextPersistOnClose());
    }

    @Test
    void effectiveAccessorsReturnSessionValueWhenFieldIsSet() {
        OllamaClientSessionSettings s = new OllamaClientSessionSettings();
        s.setContextTrimTrigger(OllamaContextTriggerEnum.MESSAGE_COUNT);
        s.setContextTrimStrategy(OllamaContextTrimStrategyEnum.NONE);
        s.setContextTokenThreshold(99000);
        s.setContextTrimTargetPercent(50);
        s.setContextMaxMessages(100);
        s.setContextPersistOnClose(true);

        assertEquals(OllamaContextTriggerEnum.MESSAGE_COUNT, s.effectiveContextTrimTrigger());
        assertEquals(OllamaContextTrimStrategyEnum.NONE, s.effectiveContextTrimStrategy());
        assertEquals(99000, s.effectiveContextTokenThreshold());
        assertEquals(50, s.effectiveContextTrimTargetPercent());
        assertEquals(100, s.effectiveContextMaxMessages());
        assertEquals(true, s.effectiveContextPersistOnClose());
    }
}
