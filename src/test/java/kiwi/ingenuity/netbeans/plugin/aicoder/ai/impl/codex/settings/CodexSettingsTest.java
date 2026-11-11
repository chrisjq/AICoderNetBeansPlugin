package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import com.google.gson.JsonObject;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CodexSettingsTest {

    @Test
    void threadIdIsNullBeforeSet() {
        assertNull(new CodexSessionSettings().threadId());
    }

    @Test
    void threadIdSetAndGetRoundTrips() {
        CodexSessionSettings s = new CodexSessionSettings();
        s.setThreadId("01a01885-5fba-7932-a9bc-da38712890b6");
        assertEquals("01a01885-5fba-7932-a9bc-da38712890b6", s.threadId());
    }

    @Test
    void populateJsonObjectIncludesThreadIdWhenSet() {
        CodexSessionSettings s = new CodexSessionSettings();
        s.setThreadId("th-1");
        JsonObject cfg = new JsonObject();
        s.populateJsonObject(cfg);
        assertEquals("th-1", cfg.get("threadId").getAsString());
    }

    @Test
    void populateJsonObjectOmitsThreadIdKeyWhenNull() {
        CodexSessionSettings s = new CodexSessionSettings();
        JsonObject cfg = new JsonObject();
        s.populateJsonObject(cfg);
        assertFalse(cfg.has("threadId"));
    }

    @Test
    void populateJsonObjectIncludesModel() {
        CodexSessionSettings s = new CodexSessionSettings();
        s.setModel("gpt-5.6-terra");
        JsonObject cfg = new JsonObject();
        s.populateJsonObject(cfg);
        assertEquals("gpt-5.6-terra", cfg.get("model").getAsString());
    }

    @Test
    void settingsCreatorUpdateDeserializesThreadIdFromJson() {
        CodexSettingsCreator creator = new CodexSettingsCreator();
        CodexSessionSettings s = creator.create();
        JsonObject cfg = new JsonObject();
        cfg.addProperty("threadId", "th-round-trip");
        creator.update(s, cfg);
        assertEquals("th-round-trip", s.threadId());
    }

    @Test
    void settingsCreatorUpdateToleratesJsonWithoutThreadId() {
        CodexSettingsCreator creator = new CodexSettingsCreator();
        CodexSessionSettings s = creator.create();
        assertDoesNotThrow(() -> creator.update(s, new JsonObject()));
        assertNull(s.threadId());
    }

    @Test
    void settingsCreatorUpdateDeserializesModelFromJson() {
        CodexSettingsCreator creator = new CodexSettingsCreator();
        CodexSessionSettings s = creator.create();
        JsonObject cfg = new JsonObject();
        cfg.addProperty("model", "gpt-5.6-luna");
        creator.update(s, cfg);
        assertEquals("gpt-5.6-luna", s.model());
    }

    // ---- reasoning effort ----
    @Test
    void effortIsNullBeforeSet() {
        assertNull(new CodexSessionSettings().effort());
    }

    @Test
    void effortSetAndGetRoundTrips() {
        CodexSessionSettings s = new CodexSessionSettings();
        s.setEffort("high");
        assertEquals("high", s.effort());
    }

    @Test
    void populateJsonObjectIncludesEffortWhenSet() {
        CodexSessionSettings s = new CodexSessionSettings();
        s.setEffort("high");
        JsonObject cfg = new JsonObject();
        s.populateJsonObject(cfg);
        assertEquals("high", cfg.get("effort").getAsString());
    }

    @Test
    void populateJsonObjectOmitsEffortKeyWhenNull() {
        CodexSessionSettings s = new CodexSessionSettings();
        JsonObject cfg = new JsonObject();
        s.populateJsonObject(cfg);
        assertFalse(cfg.has("effort"), "unset effort must not be persisted");
    }

    @Test
    void settingsCreatorUpdateDeserializesEffortFromJson() {
        CodexSettingsCreator creator = new CodexSettingsCreator();
        CodexSessionSettings s = creator.create();
        JsonObject cfg = new JsonObject();
        cfg.addProperty("effort", "medium");
        creator.update(s, cfg);
        assertEquals("medium", s.effort());
    }

    @Test
    void settingsCreatorApplyDefaultSettingsFromGlobalCopiesEffort() {
        String before = CodexPluginSettings.getEffort();
        try {
            CodexPluginSettings.setEffort("high");
            CodexSessionSettings s = new CodexSessionSettings();
            s.setEffort("low");
            new CodexSettingsCreator().applyDefaultSettingsFromGlobal(s);
            assertEquals("high", s.effort(), "the global default must seed a fresh session");
        }
        finally {
            CodexPluginSettings.setEffort(before);
        }
    }

    @Test
    void createSettingsPanelReturnsCodexCreateSettingsPanel() {
        CodexSettingsCreator creator = new CodexSettingsCreator();
        assertNotNull(creator.createSettingsPanel());
    }

    @Test
    void autoIsStoredAsItsOwnValueAndBlankIsNotSet() {
        assertEquals("", CodexPluginSettings.DEFAULT_MODEL);
        assertEquals(CodexPluginSettings.AUTO_MODEL, CodexPluginSettings.toStoredModel("AUTO"));
        assertEquals(CodexPluginSettings.AUTO_MODEL, CodexPluginSettings.toStoredModel("Auto"));
        assertEquals(CodexPluginSettings.DEFAULT_MODEL, CodexPluginSettings.toStoredModel("   "));
        assertEquals(CodexPluginSettings.AUTO_MODEL, CodexPluginSettings.toDisplayModel(""));
    }

    @Test
    void setModelAutoRoundTripsThroughThePreferences() {
        String before = CodexPluginSettings.getModel();
        try {
            CodexPluginSettings.setModel("Auto");
            assertEquals(CodexPluginSettings.AUTO_MODEL, CodexPluginSettings.getModel());
        }
        finally {
            CodexPluginSettings.setModel(before);
        }
    }

    @Test
    void discoveredModelsRoundTripThroughThePreferences() {
        List<String> before = CodexPluginSettings.getDiscoveredModels();
        try {
            CodexPluginSettings.setDiscoveredModels(List.of("gpt-a", "gpt-b"));
            assertEquals(List.of("gpt-a", "gpt-b"), CodexPluginSettings.getDiscoveredModels());
        }
        finally {
            CodexPluginSettings.setDiscoveredModels(before);
        }
    }

    @Test
    void getModelDefaultsToDefaultModelConstant() {
        assertEquals(CodexPluginSettings.DEFAULT_MODEL, CodexPluginSettings.getModel());
    }
}
