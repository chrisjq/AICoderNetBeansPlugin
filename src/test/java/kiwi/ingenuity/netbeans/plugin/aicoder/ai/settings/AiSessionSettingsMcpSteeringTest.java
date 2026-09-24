package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiSessionSettingsMcpSteeringTest {

    @Test
    void nullOverridesFallBackAndExplicitValuesWin() {
        AiSessionSettings settings = new AiSessionSettings();
        assertNull(settings.mcpSteering());
        assertTrue(settings.effectiveMcpSteering());

        settings.setMcpSteering(false);

        assertFalse(settings.effectiveMcpSteering());

    }

    @Test
    void steeringValuesRoundTripThroughJsonCreator() {
        AiSessionSettings original = new AiSessionSettings();
        original.setMcpSteering(true);

        JsonObject json = new JsonObject();
        original.populateJsonObject(json);

        AiSessionSettings loaded = new AiSessionSettings();
        new AiSessionSettingsCreator<AiSessionSettings>() {
            @Override
            public AiSessionSettings create() {
                return new AiSessionSettings();
            }

            @Override
            public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
            }
        }.update(loaded, json);

        assertEquals(true, loaded.mcpSteering());

    }
}
