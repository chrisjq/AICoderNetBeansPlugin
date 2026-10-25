package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The generic backend-debug-log switch is off unless a session or its config template turns it on.
 */
@Timeout(10)
class BackendDebugLogSettingTest {

    @Test
    void defaultIsOff() {
        AiSessionSettings settings = new AiSessionSettings();

        assertNull(settings.backendDebugLog());
        assertFalse(settings.effectiveBackendDebugLog());
    }

    @Test
    void jsonRoundTripKeepsAnExplicitValueAndOmitsAnUnsetOne() {
        AiSessionSettings on = new AiSessionSettings();
        on.setBackendDebugLog(true);
        JsonObject onJson = new JsonObject();
        on.populateJsonObject(onJson);

        AiSessionSettings loadedOn = new AiSessionSettings();
        creator().update(loadedOn, onJson);

        assertTrue(onJson.get(AiSessionSettingsKeyEnum.BACKEND_DEBUG_LOG.key()).getAsBoolean());
        assertEquals(Boolean.TRUE, loadedOn.backendDebugLog());
        assertTrue(loadedOn.effectiveBackendDebugLog());

        AiSessionSettings off = new AiSessionSettings();
        off.setBackendDebugLog(false);
        JsonObject offJson = new JsonObject();
        off.populateJsonObject(offJson);
        AiSessionSettings loadedOff = new AiSessionSettings();
        creator().update(loadedOff, offJson);

        assertFalse(offJson.get(AiSessionSettingsKeyEnum.BACKEND_DEBUG_LOG.key()).getAsBoolean());
        assertEquals(Boolean.FALSE, loadedOff.backendDebugLog());
        assertFalse(loadedOff.effectiveBackendDebugLog());

        JsonObject unset = new JsonObject();
        new AiSessionSettings().populateJsonObject(unset);
        assertFalse(unset.has(AiSessionSettingsKeyEnum.BACKEND_DEBUG_LOG.key()));
    }

    @Test
    void configTemplateRoundTripKeepsIt() {
        AiSessionSettings source = new AiSessionSettings();
        source.setBackendDebugLog(true);

        ConfigTemplate loaded = ConfigTemplate.fromJson(ConfigTemplate.create("debug", source).toJson());

        assertEquals(Boolean.TRUE, loaded.settings().backendDebugLog());
        assertTrue(loaded.settings().effectiveBackendDebugLog());
    }

    @Test
    void defaultTemplateLeavesItOff() {
        ConfigTemplate template = ConfigTemplate.createDefault(new AiSessionSettings());

        assertFalse(template.settings().effectiveBackendDebugLog());
        assertFalse(template.isDefault() && template.settings().effectiveBackendDebugLog());

        AiSessionSettings applied = new AiSessionSettings();
        applied.setBackendDebugLog(true);
        template.applyTo(applied);

        assertFalse(applied.effectiveBackendDebugLog());

        ConfigTemplate reloaded = ConfigTemplate.fromJson(template.toJson());
        assertFalse(reloaded.settings().effectiveBackendDebugLog());
    }

    private static AiSessionSettingsCreator<AiSessionSettings> creator() {
        return new AiSessionSettingsCreator<>() {
            @Override
            public AiSessionSettings create() {
                return new AiSessionSettings();
            }

            @Override
            public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
            }
        };
    }

}
