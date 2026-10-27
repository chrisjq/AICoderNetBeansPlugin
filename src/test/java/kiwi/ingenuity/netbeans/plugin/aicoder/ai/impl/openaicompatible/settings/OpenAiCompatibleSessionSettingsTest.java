package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleSessionSettingsTest {

    @AfterEach
    void resetPreferences() {
        OpenAiCompatiblePluginSettings.useForTest(null);
    }

    @Test
    @Timeout(5)
    void jsonRoundTripKeepsEveryCompatibleField() {
        OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
        original.setServerType(ServerTypeEnum.VLLM);
        original.setBaseUrl("http://vllm");
        original.setModel("model");
        original.setContextWindowOverride(32768);
        original.setToolCallingMode(ToolCallingModeEnum.SCHEMA);
        original.setSaveContextOnClose(true);
        original.setApiKeySet(true);
        original.setExtraHeaderNames(List.of("X-Plain", "X-Secret"));
        original.setExtraHeaderPlainValues(Map.of("X-Plain", "value"));

        JsonObject json = new JsonObject();
        original.populateJsonObject(json);
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, json);

        assertEquals(ServerTypeEnum.VLLM, restored.serverType());
        assertEquals("http://vllm", restored.baseUrl());
        assertEquals("model", restored.model());
        assertEquals(32768, restored.contextWindowOverride());
        assertEquals(ToolCallingModeEnum.SCHEMA, restored.toolCallingMode());
        assertEquals(true, restored.saveContextOnClose());
        assertEquals(true, restored.apiKeySet());
        assertEquals(List.of("X-Plain", "X-Secret"), restored.extraHeaderNames());
        assertEquals(Map.of("X-Plain", "value"), restored.extraHeaderPlainValues());
    }

    @Test
    void saveContextOnCloseDefaultsToOnButHonoursExplicitFalse() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        assertTrue(settings.effectiveSaveContextOnClose(), "unset must preserve context");
        settings.setSaveContextOnClose(false);
        assertFalse(settings.effectiveSaveContextOnClose());
        settings.setSaveContextOnClose(true);
        assertTrue(settings.effectiveSaveContextOnClose());
    }

    @Test
    @Timeout(5)
    void apiKeyIsOnlyABooleanFlag() {
        for (java.lang.reflect.Field field : OpenAiCompatibleSessionSettings.class.getDeclaredFields()) {
            assertFalse(field.getName().toLowerCase(java.util.Locale.ROOT).equals("apikey"));
        }
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setApiKeySet(true);
        JsonObject json = new JsonObject();
        settings.populateJsonObject(json);
        assertEquals(true, json.get(OpenAiCompatibleSessionSettingsKeyEnum.API_KEY_SET.key()).getAsBoolean());
    }

    @Test
    @Timeout(5)
    void unsetValuesReadPluginDefaultsAndExplicitDefaultIsPersisted() throws Exception {
        Preferences prefs = Preferences.userRoot().node("openai-compatible-settings-" + java.util.UUID.randomUUID());
        OpenAiCompatiblePluginSettings.useForTest(prefs);
        OpenAiCompatiblePluginSettings.setBaseUrl("http://default");
        OpenAiCompatiblePluginSettings.setServerType(ServerTypeEnum.OLLAMA);
        OpenAiCompatiblePluginSettings.setModel("default-model");

        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        assertEquals("http://default", settings.effectiveBaseUrl());
        assertEquals(ServerTypeEnum.OLLAMA, settings.effectiveServerType());
        assertEquals("default-model", settings.effectiveModel());

        settings.setBaseUrl("http://default");
        JsonObject json = new JsonObject();
        settings.populateJsonObject(json);
        assertEquals("http://default", json.get(OpenAiCompatibleSessionSettingsKeyEnum.BASE_URL.key()).getAsString());
        prefs.removeNode();
    }
}
