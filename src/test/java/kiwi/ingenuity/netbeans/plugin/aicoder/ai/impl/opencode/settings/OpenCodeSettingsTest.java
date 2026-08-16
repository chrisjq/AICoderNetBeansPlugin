package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OpenCodeSettingsTest {

    @Test
    void populateJsonObjectIncludesModel() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings(
                null, null, null, null, null, null,
                "opencode/big-pickle", null, null);
        JsonObject cfg = new JsonObject();

        settings.populateJsonObject(cfg);

        assertEquals("opencode/big-pickle", cfg.get("model").getAsString());
    }

    @Test
    void additionalInfoIncludesModelWhenPresent() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings(
                null, null, null, null, null, null,
                "opencode/big-pickle", null, null);

        assertTrue(settings.getAdditionalInfo().contains("model: opencode/big-pickle"));
    }

    @Test
    void modeIsNullBeforeSet() {
        assertNull(new OpenCodeSessionSettings().mode());
    }

    @Test
    void modeSetAndGetRoundTrips() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("plan");
        assertEquals("plan", settings.mode());
    }

    @Test
    void populateJsonObjectIncludesMode() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("plan");
        JsonObject cfg = new JsonObject();
        settings.populateJsonObject(cfg);
        assertEquals("plan", cfg.get("mode").getAsString());
    }

    @Test
    void populateJsonObjectOmitsModeKeyWhenNull() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        JsonObject cfg = new JsonObject();
        settings.populateJsonObject(cfg);
        assertFalse(cfg.has("mode"));
    }

    @Test
    void settingsCreatorUpdateDeserializesModeFromJson() {
        OpenCodeSettingsCreator creator = new OpenCodeSettingsCreator();
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        JsonObject cfgObj = new JsonObject();
        cfgObj.addProperty("mode", "plan");
        creator.update(settings, cfgObj);
        assertEquals("plan", settings.mode());
    }

    @Test
    void settingsCreatorUpdateToleratesJsonWithoutModeKey() {
        OpenCodeSettingsCreator creator = new OpenCodeSettingsCreator();
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        creator.update(settings, new JsonObject());
        assertEquals("build", settings.mode(), "existing mode must be preserved when JSON omits mode key");
    }

    @Test
    void acpSessionIdRoundTripsThroughSettingsCreator() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setAcpSessionId("acp-abc-123");
        JsonObject cfg = new JsonObject();
        settings.populateJsonObject(cfg);

        OpenCodeSessionSettings loaded = new OpenCodeSessionSettings();
        new OpenCodeSettingsCreator().update(loaded, cfg);

        assertEquals("acp-abc-123", loaded.acpSessionId());
    }

    @Test
    void settingsCreatorUpdateToleratesJsonWithoutAcpSessionId() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        new OpenCodeSettingsCreator().update(settings, new JsonObject());
        assertNull(settings.acpSessionId(), "absent acpSessionId in JSON must deserialise to null");
    }
}
