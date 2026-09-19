package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrokCreateSettingsPanelTest {

    @BeforeEach
    @AfterEach
    void resetGlobalState() {
        GrokPluginSettings.setReasoningEffort("");
    }

    @Test
    void defaultLabelMapsToNullStoredReasoningEffort() {
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(new AiModelCatalog());
        GrokSessionSettings empty = new GrokSessionSettings();
        panel.load(empty);

        GrokSessionSettings result = new GrokSessionSettings();
        panel.applyTo(result);

        assertNull(result.reasoningEffort(), "\"(model default)\" must store null, not an empty/placeholder string");
    }

    @Test
    void globalDefaultReasoningEffortPropagatesWhenSessionHasNone() {
        GrokPluginSettings.setReasoningEffort("medium");
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(new AiModelCatalog());
        GrokSessionSettings empty = new GrokSessionSettings();
        panel.load(empty);

        GrokSessionSettings result = new GrokSessionSettings();
        panel.applyTo(result);

        assertEquals("medium", result.reasoningEffort());
    }

    @Test
    void storedReasoningEffortRoundTripsThroughLoadAndApplyTo() {
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(new AiModelCatalog());
        GrokSessionSettings stored = new GrokSessionSettings();
        stored.setReasoningEffort("xhigh");
        panel.load(stored);

        GrokSessionSettings result = new GrokSessionSettings();
        panel.applyTo(result);

        assertEquals("xhigh", result.reasoningEffort());
    }

    @Test
    void modelSelectionRoundTripsThroughLoadAndApplyTo() {
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(new AiModelCatalog());
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.6");
        panel.load(settings);

        GrokSessionSettings result = new GrokSessionSettings();
        panel.applyTo(result);

        assertEquals("grok-4.6", result.model());
    }
}
