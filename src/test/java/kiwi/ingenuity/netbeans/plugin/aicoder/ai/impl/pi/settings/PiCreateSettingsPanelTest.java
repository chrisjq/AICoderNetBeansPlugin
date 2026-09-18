package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PiCreateSettingsPanelTest {

    @BeforeEach
    @AfterEach
    void resetGlobalAndRememberedState() {
        PiPluginSettings.setThinkingLevel("");
        PiCreateSettingsPanel.lastSelectedThinkingLevel = null;
    }

    @Test
    void globalDefaultThinkingLevelPropagatesWhenNothingRememberedOrSet() {
        PiPluginSettings.setThinkingLevel("high");
        PiCreateSettingsPanel panel = new PiCreateSettingsPanel(new AiModelCatalog());
        PiSessionSettings empty = new PiSessionSettings();
        panel.load(empty);

        PiSessionSettings result = new PiSessionSettings();
        panel.applyTo(result);

        assertEquals("high", result.thinkingLevel(), "the global default thinking level should propagate to a blank session");
    }

    @Test
    void defaultLabelMapsToNullStoredThinkingLevel() {
        PiCreateSettingsPanel panel = new PiCreateSettingsPanel(new AiModelCatalog());
        PiSessionSettings empty = new PiSessionSettings();
        panel.load(empty);

        PiSessionSettings result = new PiSessionSettings();
        panel.applyTo(result);

        assertNull(result.thinkingLevel(), "\"(pi default)\" must store null, not an empty/placeholder string");
    }

    @Test
    void rememberedSelectionSurvivesAcrossPanelsWithoutAStoredSetting() {
        PiCreateSettingsPanel.lastSelectedThinkingLevel = "medium";
        PiCreateSettingsPanel panel = new PiCreateSettingsPanel(new AiModelCatalog());
        PiSessionSettings empty = new PiSessionSettings();
        panel.load(empty);

        PiSessionSettings result = new PiSessionSettings();
        panel.applyTo(result);

        assertEquals("medium", result.thinkingLevel());
    }

    @Test
    void storedSettingWinsOverRememberedSelectionAndGlobalDefault() {
        PiPluginSettings.setThinkingLevel("low");
        PiCreateSettingsPanel.lastSelectedThinkingLevel = "medium";
        PiCreateSettingsPanel panel = new PiCreateSettingsPanel(new AiModelCatalog());
        PiSessionSettings stored = new PiSessionSettings();
        stored.setThinkingLevel("xhigh");
        panel.load(stored);

        PiSessionSettings result = new PiSessionSettings();
        panel.applyTo(result);

        assertEquals("xhigh", result.thinkingLevel());
    }

    @Test
    void loadingAStoredThinkingLevelDoesNotPolluteTheRememberedSelection() {
        PiCreateSettingsPanel panel = new PiCreateSettingsPanel(new AiModelCatalog());
        PiSessionSettings stored = new PiSessionSettings();
        stored.setThinkingLevel("xhigh");

        panel.load(stored);

        assertNull(PiCreateSettingsPanel.lastSelectedThinkingLevel,
                   "merely loading a session's stored thinking level must not overwrite the remembered selection");
    }

    @Test
    void modelSelectionRoundTripsThroughLoadAndApplyTo() {
        PiCreateSettingsPanel panel = new PiCreateSettingsPanel(new AiModelCatalog());
        PiSessionSettings settings = new PiSessionSettings();
        settings.setModel("github-copilot/gpt-5-mini");
        panel.load(settings);

        PiSessionSettings result = new PiSessionSettings();
        panel.applyTo(result);

        assertEquals("github-copilot/gpt-5-mini", result.model());
    }
}
