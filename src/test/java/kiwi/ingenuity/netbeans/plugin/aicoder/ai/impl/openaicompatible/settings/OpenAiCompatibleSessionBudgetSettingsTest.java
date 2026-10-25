package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class OpenAiCompatibleSessionBudgetSettingsTest {

    private static OpenAiCompatibleSessionSettings saveAndReload(OpenAiCompatibleSessionSettings original) {
        JsonObject cfg = new JsonObject();
        original.populateJsonObject(cfg);
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, cfg);
        return restored;
    }

    @Test
    void roundTripKeepsEveryBudgetAndReasoningValue() {
        OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
        original.setShowReasoning(true);
        original.setReservePercent(20);
        original.setReserveMinimum(512);
        original.setReserveMaxSharePercent(40);
        original.setReserveAbsolute(4096);
        original.setKeepRecentPercent(30);
        original.setKeepRecentMinimum(1000);
        original.setKeepRecentAbsolute(8000);
        original.setToolResultSharePercent(10);
        original.setPruneThresholdChars(3000);
        original.setPruneCharsKept(150);

        OpenAiCompatibleSessionSettings restored = saveAndReload(original);

        assertEquals(Boolean.TRUE, restored.showReasoning());
        assertEquals(20, restored.reservePercent());
        assertEquals(512, restored.reserveMinimum());
        assertEquals(40, restored.reserveMaxSharePercent());
        assertEquals(4096, restored.reserveAbsolute());
        assertEquals(30, restored.keepRecentPercent());
        assertEquals(1000, restored.keepRecentMinimum());
        assertEquals(8000, restored.keepRecentAbsolute());
        assertEquals(10, restored.toolResultSharePercent());
        assertEquals(3000, restored.pruneThresholdChars());
        assertEquals(150, restored.pruneCharsKept());
    }

    @Test
    void unsetValuesAreNotWrittenAndStayUnset() {
        JsonObject cfg = new JsonObject();
        new OpenAiCompatibleSessionSettings().populateJsonObject(cfg);

        assertFalse(cfg.has("reservePercent"));
        assertFalse(cfg.has("showReasoning"));
        assertEquals(null, saveAndReload(new OpenAiCompatibleSessionSettings()).reservePercent());
    }

    @Test
    void unsetBudgetValuesFallBackToTheDefaults() {
        OpenAiCompatibleSessionSettings unset = new OpenAiCompatibleSessionSettings();

        assertEquals(15, unset.effectiveReservePercent());
        assertEquals(1024, unset.effectiveReserveMinimum());
        assertEquals(50, unset.effectiveReserveMaxSharePercent());
        assertEquals(0, unset.effectiveReserveAbsolute());
        assertEquals(25, unset.effectiveKeepRecentPercent());
        assertEquals(2048, unset.effectiveKeepRecentMinimum());
        assertEquals(0, unset.effectiveKeepRecentAbsolute());
        assertEquals(25, unset.effectiveToolResultSharePercent());
        assertEquals(4000, unset.effectivePruneThresholdChars());
        assertEquals(200, unset.effectivePruneCharsKept());
    }

    @Test
    void percentagesAreClampedToTheirRanges() {
        OpenAiCompatibleSessionSettings s = new OpenAiCompatibleSessionSettings();
        s.setReservePercent(150);
        assertEquals(100, s.effectiveReservePercent());
        s.setReservePercent(-5);
        assertEquals(0, s.effectiveReservePercent());
        s.setReserveMaxSharePercent(0);
        assertEquals(1, s.effectiveReserveMaxSharePercent());
        s.setKeepRecentPercent(-1);
        assertEquals(0, s.effectiveKeepRecentPercent());
        s.setToolResultSharePercent(0);
        assertEquals(1, s.effectiveToolResultSharePercent(), "the tool-result share is in (0, 100]");
        s.setToolResultSharePercent(250);
        assertEquals(100, s.effectiveToolResultSharePercent());
    }

    @Test
    void absolutesAndMinimumsAreNeverNegative() {
        OpenAiCompatibleSessionSettings s = new OpenAiCompatibleSessionSettings();
        s.setReserveAbsolute(-10);
        s.setKeepRecentAbsolute(-10);
        s.setReserveMinimum(-1);
        s.setKeepRecentMinimum(-1);

        assertEquals(0, s.effectiveReserveAbsolute());
        assertEquals(0, s.effectiveKeepRecentAbsolute());
        assertEquals(0, s.effectiveReserveMinimum());
        assertEquals(0, s.effectiveKeepRecentMinimum());
    }

    @Test
    void charsKeptIsKeptBelowThePruneThreshold() {
        OpenAiCompatibleSessionSettings s = new OpenAiCompatibleSessionSettings();
        s.setPruneThresholdChars(100);
        s.setPruneCharsKept(500);
        assertEquals(99, s.effectivePruneCharsKept(), "chars kept must stay below the threshold");

        s.setPruneCharsKept(-3);
        assertEquals(0, s.effectivePruneCharsKept());
    }

    @Test
    void showReasoningFallsBackToThePluginDefaultWhenUnset() {
        boolean saved = OpenAiCompatiblePluginSettings.isShowReasoning();
        try {
            OpenAiCompatiblePluginSettings.setShowReasoning(false);
            assertFalse(new OpenAiCompatibleSessionSettings().effectiveShowReasoning());

            OpenAiCompatiblePluginSettings.setShowReasoning(true);
            assertTrue(new OpenAiCompatibleSessionSettings().effectiveShowReasoning());

            OpenAiCompatibleSessionSettings explicitOff = new OpenAiCompatibleSessionSettings();
            explicitOff.setShowReasoning(false);
            assertFalse(explicitOff.effectiveShowReasoning(), "a session value wins over the plugin default");
        }
        finally {
            OpenAiCompatiblePluginSettings.setShowReasoning(saved);
        }
    }
}
