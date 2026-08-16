package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextTrimStrategyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Proves the bug: opening the context panel on a fresh session (all-null fields)
 * and clicking OK must produce global defaults, not the first enum constant.
 */
class OpenAiContextSettingsPanelTest {

    @Test
    void loadThenApplyRoundTripsEveryValue() {
        OpenAiClientSessionSettings source = new OpenAiClientSessionSettings();
        source.setContextTokenThreshold(2500);
        source.setContextTrimStrategy(ContextTrimStrategyEnum.DROP);
        source.setContextMaxMessages(42);

        OpenAiContextSettingsPanel panel = new OpenAiContextSettingsPanel();
        panel.load(source);

        OpenAiClientSessionSettings dest = new OpenAiClientSessionSettings();
        panel.applyTo(dest);

        assertEquals(2500, dest.contextTokenThreshold());
        assertEquals(ContextTrimStrategyEnum.DROP, dest.contextTrimStrategy());
        assertEquals(42, dest.contextMaxMessages());
    }

    @Test
    void loadWithAllNullsAppliesGlobalDefaults() {
        OpenAiClientSessionSettings src = new OpenAiClientSessionSettings();
        // all six context fields are null — a freshly-created session

        OpenAiContextSettingsPanel panel = new OpenAiContextSettingsPanel();
        panel.load(src);

        OpenAiClientSessionSettings result = new OpenAiClientSessionSettings();
        panel.applyTo(result);

        assertEquals(ContextTriggerEnum.ESTIMATED_TOKENS, result.contextTrimTrigger(),
                "trigger must default to ESTIMATED_TOKENS, not MESSAGE_COUNT");
        assertEquals(ContextTrimStrategyEnum.DROP_MARKED, result.contextTrimStrategy(),
                "strategy must default to DROP_MARKED, not NONE");
        assertEquals(12000, result.contextTokenThreshold(),
                "threshold must match global default of 12000");
        assertEquals(70, result.contextTrimTargetPercent(),
                "trim target must match global default of 70");
        assertEquals(0, result.contextMaxMessages(),
                "max messages must match global default of 0");
        assertEquals(false, result.contextPersistOnClose(),
                "persist-on-close must match global default of false");
    }

    @Test
    void thresholdHintLabelReflectsSpinnerValueAndUpdatesOnChange() {
        OpenAiClientSessionSettings settings = new OpenAiClientSessionSettings();
        settings.setContextTokenThreshold(5000);

        OpenAiContextSettingsPanel panel = new OpenAiContextSettingsPanel();
        panel.load(settings);

        assertTrue(panel.thresholdHintText().contains("5,000"),
                "hint label must contain the threshold formatted with thousands separator");

        OpenAiClientSessionSettings updated = new OpenAiClientSessionSettings();
        updated.setContextTokenThreshold(20000);
        panel.load(updated);

        assertTrue(panel.thresholdHintText().contains("20,000"),
                "hint label must update when the spinner value changes");
    }

    @Test
    void addChangeListenerIsNotifiedWhenThresholdChanges() {
        OpenAiClientSessionSettings settings = new OpenAiClientSessionSettings();
        settings.setContextTokenThreshold(12000);

        OpenAiContextSettingsPanel panel = new OpenAiContextSettingsPanel();
        panel.load(settings);

        boolean[] fired = {false};
        panel.addChangeListener(e -> fired[0] = true);

        OpenAiClientSessionSettings updated = new OpenAiClientSessionSettings();
        updated.setContextTokenThreshold(8000);
        panel.load(updated);

        assertTrue(fired[0], "change listener must fire when the threshold spinner value changes");
    }
}
