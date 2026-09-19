package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ClaudeSettingsTest {

    @AfterEach
    void resetGlobalSettings() {
        ClaudePluginSettings.setExecutable("");
        ClaudePluginSettings.setModel("");
        ClaudePluginSettings.setEffort("");
        ClaudePluginSettings.setDiscoveredModels(new String[0]);
    }

    @Test
    void pluginSettingsPersistenceRoundTrip() {
        ClaudePluginSettings.setExecutable("/usr/local/bin/claude");
        assertEquals("/usr/local/bin/claude", ClaudePluginSettings.getExecutable());

        ClaudePluginSettings.setModel("claude-sonnet-4-6");
        assertEquals("claude-sonnet-4-6", ClaudePluginSettings.getModel());

        ClaudePluginSettings.setEffort("high");
        assertEquals("high", ClaudePluginSettings.getEffort());

        ClaudePluginSettings.setDiscoveredModels(new String[]{"claude-opus-4-8", "claude-sonnet-4-6"});
        assertEquals(2, ClaudePluginSettings.getKnownModels().length);
        assertEquals("claude-opus-4-8", ClaudePluginSettings.getKnownModels()[0]);
        assertEquals("claude-sonnet-4-6", ClaudePluginSettings.getKnownModels()[1]);
    }

    @Test
    void emptyMeansClaudeOwnDefaultForModelAndEffort() {
        ClaudePluginSettings.setModel("");
        assertEquals("", ClaudePluginSettings.getModel());

        ClaudePluginSettings.setEffort("");
        assertEquals("", ClaudePluginSettings.getEffort());
    }

    @Test
    void sessionSettings_effortRoundTrip() {
        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        assertNull(settings.effort());

        settings.setEffort("medium");
        assertEquals("medium", settings.effort());
    }

    @Test
    void sessionSettings_populateJsonObjectOmitsUnsetFields() {
        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        JsonObject cfg = new JsonObject();
        settings.populateJsonObject(cfg);
        assertFalse(cfg.has(ClaudeSessionSettingsKeyEnum.EFFORT.key()));
    }

    @Test
    void sessionSettings_populateJsonObjectIncludesSetFields() {
        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        settings.setEffort("low");
        JsonObject cfg = new JsonObject();
        settings.populateJsonObject(cfg);
        assertEquals("low", cfg.get(ClaudeSessionSettingsKeyEnum.EFFORT.key()).getAsString());
    }

    @Test
    void settingsCreator_createReturnsFreshInstance() {
        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        ClaudeSessionSettings a = creator.create();
        ClaudeSessionSettings b = creator.create();
        assertTrue(a != b);
    }

    @Test
    void settingsCreator_updateDeserializesEffortField() {
        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        ClaudeSessionSettings settings = creator.create();
        JsonObject cfg = new JsonObject();
        cfg.addProperty(ClaudeSessionSettingsKeyEnum.EFFORT.key(), "xhigh");

        creator.update(settings, cfg);

        assertEquals("xhigh", settings.effort());
    }

    @Test
    void settingsCreator_updateTolerantOfMissingKeys() {
        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        ClaudeSessionSettings settings = creator.create();
        settings.setEffort("kept");
        JsonObject cfg = new JsonObject();

        creator.update(settings, cfg);

        assertEquals("kept", settings.effort());
    }

    @Test
    void settingsCreator_fullRoundTripThroughJson() {
        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        ClaudeSessionSettings original = creator.create();
        original.setModel("claude-opus-4-8");
        original.setEffort("high");

        JsonObject cfg = new JsonObject();
        original.populateJsonObject(cfg);

        ClaudeSessionSettings loaded = creator.create();
        creator.update(loaded, cfg);

        assertEquals("claude-opus-4-8", loaded.model());
        assertEquals("high", loaded.effort());
    }
}
