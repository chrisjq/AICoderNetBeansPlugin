package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTrimStrategyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class OpenAiClientSessionSettingsTest {

    @Test
    void contextValuesSerialiseIntoTheSessionJson() {
        OpenAiClientSessionSettings s = new OpenAiClientSessionSettings();
        s.setContextTrimTrigger(ContextTriggerEnum.REPORTED_TOKENS);
        s.setContextTrimStrategy(ContextTrimStrategyEnum.DROP);
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
        OpenAiClientSessionSettings s = new OpenAiClientSessionSettings();

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
        OpenAiClientSessionSettings s = new OpenAiClientSessionSettings();

        assertEquals(ContextTriggerEnum.ESTIMATED_TOKENS, s.effectiveContextTrimTrigger());
        assertEquals(ContextTrimStrategyEnum.DROP_MARKED, s.effectiveContextTrimStrategy());
        assertEquals(12000, s.effectiveContextTokenThreshold());
        assertEquals(70, s.effectiveContextTrimTargetPercent());
        assertEquals(0, s.effectiveContextMaxMessages());
        assertEquals(false, s.effectiveContextPersistOnClose());
    }

    @Test
    void effectiveAccessorsReturnSessionValueWhenFieldIsSet() {
        OpenAiClientSessionSettings s = new OpenAiClientSessionSettings();
        s.setContextTrimTrigger(ContextTriggerEnum.MESSAGE_COUNT);
        s.setContextTrimStrategy(ContextTrimStrategyEnum.NONE);
        s.setContextTokenThreshold(99000);
        s.setContextTrimTargetPercent(50);
        s.setContextMaxMessages(100);
        s.setContextPersistOnClose(true);

        assertEquals(ContextTriggerEnum.MESSAGE_COUNT, s.effectiveContextTrimTrigger());
        assertEquals(ContextTrimStrategyEnum.NONE, s.effectiveContextTrimStrategy());
        assertEquals(99000, s.effectiveContextTokenThreshold());
        assertEquals(50, s.effectiveContextTrimTargetPercent());
        assertEquals(100, s.effectiveContextMaxMessages());
        assertEquals(true, s.effectiveContextPersistOnClose());
    }
}
