package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class OpenAiCompatibleSessionImageInputModeTest {

    private static OpenAiCompatibleSessionSettings saveAndReload(OpenAiCompatibleSessionSettings original) {
        JsonObject cfg = new JsonObject();
        original.populateJsonObject(cfg);
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, cfg);
        return restored;
    }

    @Test
    void unsetModeIsNotWrittenAndStaysUnset() {
        JsonObject cfg = new JsonObject();
        new OpenAiCompatibleSessionSettings().populateJsonObject(cfg);

        assertFalse(cfg.has("imageInputMode"));
        OpenAiCompatibleSessionSettings restored = saveAndReload(new OpenAiCompatibleSessionSettings());
        assertNull(restored.imageInputMode());
        assertEquals(ImageInputModeEnum.AUTO, restored.effectiveImageInputMode());
    }

    @Test
    void everyModeSurvivesSaveAndReload() {
        for (ImageInputModeEnum mode : ImageInputModeEnum.values()) {
            OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
            original.setImageInputMode(mode);

            JsonObject cfg = new JsonObject();
            original.populateJsonObject(cfg);
            assertEquals(mode.name(), cfg.get("imageInputMode").getAsString());

            OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
            new OpenAiCompatibleSettingsCreator().update(restored, cfg);
            assertEquals(mode, restored.imageInputMode());
            assertEquals(mode, restored.effectiveImageInputMode());
        }
    }

    @Test
    void unknownOrNullTextParsesToNullAndFallsBackToAuto() {
        assertNull(ImageInputModeEnum.parse(null));
        assertNull(ImageInputModeEnum.parse(""));
        assertNull(ImageInputModeEnum.parse("sometimes"));
        assertEquals(ImageInputModeEnum.AUTO, ImageInputModeEnum.parse("AUTO"));
        assertEquals(ImageInputModeEnum.ON, ImageInputModeEnum.parse("ON"));
        assertEquals(ImageInputModeEnum.OFF, ImageInputModeEnum.parse("OFF"));

        JsonObject cfg = new JsonObject();
        cfg.addProperty("imageInputMode", "sometimes");
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, cfg);
        assertNull(restored.imageInputMode(), "an unknown hand-edited value is ignored");
        assertEquals(ImageInputModeEnum.AUTO, restored.effectiveImageInputMode());
    }
}
