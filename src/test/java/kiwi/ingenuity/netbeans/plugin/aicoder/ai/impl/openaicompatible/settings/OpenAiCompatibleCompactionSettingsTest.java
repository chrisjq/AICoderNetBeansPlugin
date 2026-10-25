package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleCompactionSettingsTest {

    private static OpenAiCompatibleSessionSettings roundTrip(OpenAiCompatibleSessionSettings original) {
        JsonObject json = new JsonObject();
        original.populateJsonObject(json);
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, json);
        return restored;
    }

    @Test
    @Timeout(5)
    void theThreeCompactionFieldsSurviveAJsonRoundTrip() {
        OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
        original.setCompactionStrategy(CompactionStrategyEnum.DROP);
        original.setAutoCompact(false);
        original.setPrune(false);

        OpenAiCompatibleSessionSettings restored = roundTrip(original);

        assertEquals(CompactionStrategyEnum.DROP, restored.compactionStrategy());
        assertEquals(false, restored.autoCompact());
        assertEquals(false, restored.prune());
    }

    @Test
    @Timeout(5)
    void unsetFieldsStayOutOfTheJsonAndTheEffectiveValuesAreTheDefaults() {
        OpenAiCompatibleSessionSettings unset = new OpenAiCompatibleSessionSettings();
        JsonObject json = new JsonObject();
        unset.populateJsonObject(json);

        assertFalse(json.has(OpenAiCompatibleSessionSettingsKeyEnum.COMPACTION_STRATEGY.key()));
        assertFalse(json.has(OpenAiCompatibleSessionSettingsKeyEnum.AUTO_COMPACT.key()));
        assertFalse(json.has(OpenAiCompatibleSessionSettingsKeyEnum.PRUNE.key()));

        OpenAiCompatibleSessionSettings restored = roundTrip(unset);
        assertNull(restored.compactionStrategy());
        assertNull(restored.autoCompact());
        assertNull(restored.prune());
        assertEquals(CompactionStrategyEnum.AUTO, restored.effectiveCompactionStrategy());
        assertTrue(restored.effectiveAutoCompact());
        assertTrue(restored.effectivePrune());
    }

    @Test
    @Timeout(5)
    void everyStrategyAndBothSwitchValuesRoundTrip() {
        for (CompactionStrategyEnum strategy : CompactionStrategyEnum.values()) {
            OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
            original.setCompactionStrategy(strategy);
            assertEquals(strategy, roundTrip(original).effectiveCompactionStrategy());
        }
        OpenAiCompatibleSessionSettings on = new OpenAiCompatibleSessionSettings();
        on.setAutoCompact(true);
        on.setPrune(true);
        OpenAiCompatibleSessionSettings restored = roundTrip(on);
        assertTrue(restored.effectiveAutoCompact());
        assertTrue(restored.effectivePrune());
    }

    @Test
    @Timeout(5)
    void anUnknownStrategyInTheFileIsIgnored() {
        JsonObject json = new JsonObject();
        json.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.COMPACTION_STRATEGY.key(), "TELEPORT");
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, json);

        assertNull(restored.compactionStrategy());
        assertEquals(CompactionStrategyEnum.AUTO, restored.effectiveCompactionStrategy());
    }
}
