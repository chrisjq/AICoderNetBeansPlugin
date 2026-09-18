package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiSettingsTest {

    @AfterEach
    void resetGlobalSettings() {
        PiPluginSettings.setExecutable("");
        PiPluginSettings.setModel("");
        PiPluginSettings.setThinkingLevel("");
        PiPluginSettings.setVerifiedVersion("");
        PiPluginSettings.setDiscoveredModels(new String[0]);
    }

    @Test
    void pluginSettingsPersistenceRoundTrip() {
        PiPluginSettings.setExecutable("/usr/local/bin/pi");
        assertEquals("/usr/local/bin/pi", PiPluginSettings.getExecutable());

        PiPluginSettings.setModel("github-copilot/gpt-5-mini");
        assertEquals("github-copilot/gpt-5-mini", PiPluginSettings.getModel());

        PiPluginSettings.setThinkingLevel("high");
        assertEquals("high", PiPluginSettings.getThinkingLevel());

        PiPluginSettings.setVerifiedVersion("0.86.0");
        assertEquals("0.86.0", PiPluginSettings.getVerifiedVersion());

        PiPluginSettings.setDiscoveredModels(new String[]{"github-copilot/gpt-5-mini", "anthropic/claude-sonnet-4-6"});
        assertEquals(2, PiPluginSettings.getKnownModels().length);
        assertEquals("github-copilot/gpt-5-mini", PiPluginSettings.getKnownModels()[0]);
        assertEquals("anthropic/claude-sonnet-4-6", PiPluginSettings.getKnownModels()[1]);
    }

    @Test
    void emptyMeansPiDefaultForModelAndThinkingLevel() {
        PiPluginSettings.setModel("");
        assertEquals("", PiPluginSettings.getModel());

        PiPluginSettings.setThinkingLevel("");
        assertEquals("", PiPluginSettings.getThinkingLevel());
    }

    @Test
    void executablePathValidity_emptyIsValid() {
        assertTrue(PiPluginSettings.isValidExecutablePath(""));
        assertTrue(PiPluginSettings.isValidExecutablePath(null));
    }

    @Test
    void executablePathValidity_relativePathIsValid() {
        assertTrue(PiPluginSettings.isValidExecutablePath("pi"));
    }

    @Test
    void executablePathValidity_absolutePathMustBeAFile() {
        assertFalse(PiPluginSettings.isValidExecutablePath("/definitely/does/not/exist/pi"));
    }

    @Test
    void sessionSettings_piSessionIdAndThinkingLevelRoundTrip() {
        PiSessionSettings settings = new PiSessionSettings();
        assertNull(settings.piSessionId());
        assertNull(settings.thinkingLevel());

        settings.setPiSessionId("abc-123");
        settings.setThinkingLevel("medium");
        assertEquals("abc-123", settings.piSessionId());
        assertEquals("medium", settings.thinkingLevel());
    }

    @Test
    void sessionSettings_populateJsonObjectOmitsUnsetFields() {
        PiSessionSettings settings = new PiSessionSettings();
        JsonObject cfg = new JsonObject();
        settings.populateJsonObject(cfg);
        assertFalse(cfg.has(PiSessionSettingsKeyEnum.PI_SESSION_ID.key()));
        assertFalse(cfg.has(PiSessionSettingsKeyEnum.THINKING_LEVEL.key()));
    }

    @Test
    void sessionSettings_populateJsonObjectIncludesSetFields() {
        PiSessionSettings settings = new PiSessionSettings();
        settings.setPiSessionId("session-1");
        settings.setThinkingLevel("low");
        JsonObject cfg = new JsonObject();
        settings.populateJsonObject(cfg);
        assertEquals("session-1", cfg.get(PiSessionSettingsKeyEnum.PI_SESSION_ID.key()).getAsString());
        assertEquals("low", cfg.get(PiSessionSettingsKeyEnum.THINKING_LEVEL.key()).getAsString());
    }

    @Test
    void settingsCreator_createReturnsFreshInstance() {
        PiSettingsCreator creator = new PiSettingsCreator();
        PiSessionSettings a = creator.create();
        PiSessionSettings b = creator.create();
        assertTrue(a != b);
    }

    @Test
    void settingsCreator_updateDeserializesPiSpecificFields() {
        PiSettingsCreator creator = new PiSettingsCreator();
        PiSessionSettings settings = creator.create();
        JsonObject cfg = new JsonObject();
        cfg.addProperty(PiSessionSettingsKeyEnum.PI_SESSION_ID.key(), "resumed-session");
        cfg.addProperty(PiSessionSettingsKeyEnum.THINKING_LEVEL.key(), "xhigh");

        creator.update(settings, cfg);

        assertEquals("resumed-session", settings.piSessionId());
        assertEquals("xhigh", settings.thinkingLevel());
    }

    @Test
    void settingsCreator_updateTolerantOfMissingKeys() {
        PiSettingsCreator creator = new PiSettingsCreator();
        PiSessionSettings settings = creator.create();
        settings.setPiSessionId("kept");
        JsonObject cfg = new JsonObject();

        creator.update(settings, cfg);

        assertEquals("kept", settings.piSessionId());
    }

    @Test
    void settingsCreator_fullRoundTripThroughJson() {
        PiSettingsCreator creator = new PiSettingsCreator();
        PiSessionSettings original = creator.create();
        original.setModel("github-copilot/gpt-5-mini");
        original.setPiSessionId("uuid-1");
        original.setThinkingLevel("off");

        JsonObject cfg = new JsonObject();
        original.populateJsonObject(cfg);

        PiSessionSettings loaded = creator.create();
        creator.update(loaded, cfg);

        assertEquals("github-copilot/gpt-5-mini", loaded.model());
        assertEquals("uuid-1", loaded.piSessionId());
        assertEquals("off", loaded.thinkingLevel());
    }
}
