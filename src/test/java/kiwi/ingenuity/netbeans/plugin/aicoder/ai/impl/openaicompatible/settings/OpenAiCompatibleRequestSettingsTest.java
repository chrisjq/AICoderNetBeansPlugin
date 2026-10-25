package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleRequestSettingsTest {

    private static OpenAiCompatibleSessionSettings roundTrip(OpenAiCompatibleSessionSettings original) {
        JsonObject json = new JsonObject();
        original.populateJsonObject(json);
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, json);
        return restored;
    }

    @Test
    @Timeout(5)
    void temperatureTopPAndMaxOutputTokensSurviveAJsonRoundTrip() {
        OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
        original.setTemperature(0.7);
        original.setTopP(0.9);
        original.setMaxOutputTokens(2048);

        OpenAiCompatibleSessionSettings restored = roundTrip(original);

        assertEquals(0.7, restored.temperature());
        assertEquals(0.9, restored.topP());
        assertEquals(2048, restored.maxOutputTokens());
    }

    @Test
    @Timeout(5)
    void unsetRequestFieldsAreNotWrittenAndComeBackNull() {
        OpenAiCompatibleSessionSettings unset = new OpenAiCompatibleSessionSettings();
        JsonObject json = new JsonObject();
        unset.populateJsonObject(json);

        assertFalse(json.has(OpenAiCompatibleSessionSettingsKeyEnum.TEMPERATURE.key()));
        assertFalse(json.has(OpenAiCompatibleSessionSettingsKeyEnum.TOP_P.key()));
        assertFalse(json.has(OpenAiCompatibleSessionSettingsKeyEnum.MAX_OUTPUT_TOKENS.key()));
        OpenAiCompatibleSessionSettings restored = roundTrip(unset);
        assertNull(restored.temperature());
        assertNull(restored.topP());
        assertNull(restored.maxOutputTokens());
    }

    @Test
    @Timeout(5)
    void aTemperatureOfZeroIsKeptBecauseItIsAValue() {
        OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
        original.setTemperature(0.0);

        assertEquals(0.0, roundTrip(original).temperature());
    }

    @Test
    @Timeout(5)
    void aNonNumericValueInTheFileIsIgnored() {
        JsonObject json = new JsonObject();
        json.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.TEMPERATURE.key(), "warm");
        json.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.TOP_P.key(), "most");
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, json);

        assertNull(restored.temperature());
        assertNull(restored.topP());
    }

    @Test
    @Timeout(5)
    void headerNamesAndPlainValuesRoundTripWhileNoSecretFieldExists() {
        OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
        original.setExtraHeaderNames(List.of("X-Plain", "X-Api-Key"));
        original.setExtraHeaderPlainValues(Map.of("X-Plain", "value"));

        OpenAiCompatibleSessionSettings restored = roundTrip(original);

        assertEquals(List.of("X-Plain", "X-Api-Key"), restored.extraHeaderNames());
        assertEquals(Map.of("X-Plain", "value"), restored.extraHeaderPlainValues());
    }
}
