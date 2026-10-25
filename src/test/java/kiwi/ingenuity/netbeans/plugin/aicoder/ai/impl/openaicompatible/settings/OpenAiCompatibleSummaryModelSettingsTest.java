package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleSummaryModelSettingsTest {

    private static OpenAiCompatibleSessionSettings roundTrip(OpenAiCompatibleSessionSettings original) {
        JsonObject json = new JsonObject();
        original.populateJsonObject(json);
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, json);
        return restored;
    }

    @Test
    @Timeout(5)
    void theSummaryModelSurvivesAJsonRoundTrip() {
        OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
        original.setSummaryModel("small-summariser");

        assertEquals("small-summariser", roundTrip(original).summaryModel());
    }

    @Test
    @Timeout(5)
    void anUnsetSummaryModelIsNotWrittenAndComesBackNull() {
        OpenAiCompatibleSessionSettings unset = new OpenAiCompatibleSessionSettings();
        JsonObject json = new JsonObject();
        unset.populateJsonObject(json);

        assertFalse(json.has(OpenAiCompatibleSessionSettingsKeyEnum.SUMMARY_MODEL.key()));
        assertNull(roundTrip(unset).summaryModel());
    }

    @Test
    @Timeout(5)
    void theEffectiveSummaryModelFallsBackToTheChatModel() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        assertEquals("chat-model", settings.effectiveSummaryModel("chat-model"));

        settings.setSummaryModel("");
        assertEquals("chat-model", settings.effectiveSummaryModel("chat-model"));

        settings.setSummaryModel("   ");
        assertEquals("chat-model", settings.effectiveSummaryModel("chat-model"));

        settings.setSummaryModel("  small  ");
        assertEquals("small", settings.effectiveSummaryModel("chat-model"));
    }

    @Test
    @Timeout(5)
    void aMissingChatModelStaysNullWhenNoSummaryModelIsSet() {
        assertNull(new OpenAiCompatibleSessionSettings().effectiveSummaryModel(null));
    }
}
