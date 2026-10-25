package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTrimStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaClientSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaContextSettingsPanel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Proves the bug: opening the context panel on a fresh session (all-null fields) and clicking OK must produce global
 * defaults, not the first enum constant.
 */
class OllamaContextSettingsPanelTest {

    @Test
    void loadThenApplyRoundTripsEveryValue() {
        OllamaClientSessionSettings source = new OllamaClientSessionSettings();
        source.setContextTokenThreshold(2500);
        source.setContextTrimStrategy(OllamaContextTrimStrategyEnum.DROP);
        source.setContextMaxMessages(42);

        OllamaContextSettingsPanel panel = new OllamaContextSettingsPanel();
        panel.load(source);

        OllamaClientSessionSettings dest = new OllamaClientSessionSettings();
        panel.applyTo(dest);

        assertEquals(2500, dest.contextTokenThreshold());
        assertEquals(OllamaContextTrimStrategyEnum.DROP, dest.contextTrimStrategy());
        assertEquals(42, dest.contextMaxMessages());
    }

    @Test
    void loadWithAllNullsAppliesGlobalDefaults() {
        OllamaClientSessionSettings src = new OllamaClientSessionSettings();
        // all six context fields are null — a freshly-created session

        OllamaContextSettingsPanel panel = new OllamaContextSettingsPanel();
        panel.load(src);

        OllamaClientSessionSettings result = new OllamaClientSessionSettings();
        panel.applyTo(result);

        assertEquals(OllamaContextTriggerEnum.ESTIMATED_TOKENS, result.contextTrimTrigger(),
                     "trigger must default to ESTIMATED_TOKENS, not MESSAGE_COUNT");
        assertEquals(OllamaContextTrimStrategyEnum.DROP_MARKED, result.contextTrimStrategy(),
                     "strategy must default to DROP_MARKED, not NONE");
        assertEquals(0, result.contextTokenThreshold(),
                     "threshold must default to derive mode");
        assertEquals(70, result.contextTrimTargetPercent(),
                     "trim target must match global default of 70");
        assertEquals(0, result.contextMaxMessages(),
                     "max messages must match global default of 0");
        assertEquals(false, result.contextPersistOnClose(),
                     "persist-on-close must match global default of false");
    }

    @Test
    void thresholdHintLabelReflectsSpinnerValueAndUpdatesOnChange() {
        OllamaClientSessionSettings settings = new OllamaClientSessionSettings();
        settings.setContextTokenThreshold(5000);

        OllamaContextSettingsPanel panel = new OllamaContextSettingsPanel();
        panel.load(settings);

        assertTrue(panel.thresholdHintText().contains("5,000"),
                   "hint label must contain the threshold formatted with thousands separator");

        OllamaClientSessionSettings updated = new OllamaClientSessionSettings();
        updated.setContextTokenThreshold(20000);
        panel.load(updated);

        assertTrue(panel.thresholdHintText().contains("20,000"),
                   "hint label must update when the spinner value changes");
    }

    @Test
    void deriveModeIsVisibleAndSurvivesRoundTrip() {
        OllamaClientSessionSettings settings = new OllamaClientSessionSettings();
        settings.setContextTokenThreshold(0);

        OllamaContextSettingsPanel panel = new OllamaContextSettingsPanel();
        panel.load(settings);

        assertTrue(panel.thresholdHintText().contains("80%"));

        OllamaClientSessionSettings result = new OllamaClientSessionSettings();
        panel.applyTo(result);
        assertEquals(0, result.contextTokenThreshold());
    }

    @Test
    void addChangeListenerIsNotifiedWhenThresholdChanges() {
        OllamaClientSessionSettings settings = new OllamaClientSessionSettings();
        settings.setContextTokenThreshold(12000);

        OllamaContextSettingsPanel panel = new OllamaContextSettingsPanel();
        panel.load(settings);

        boolean[] fired = {false};
        panel.addChangeListener(e -> fired[0] = true);

        OllamaClientSessionSettings updated = new OllamaClientSessionSettings();
        updated.setContextTokenThreshold(8000);
        panel.load(updated);

        assertTrue(fired[0], "change listener must fire when the threshold spinner value changes");
    }
}
