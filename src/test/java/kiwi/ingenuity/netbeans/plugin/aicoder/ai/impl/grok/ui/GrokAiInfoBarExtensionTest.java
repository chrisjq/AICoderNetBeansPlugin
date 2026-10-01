package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui;

import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Every call into the bar below runs on the EDT via {@code invokeAndWait}, matching how the core actually
 * delivers to it in production.
 */
class GrokAiInfoBarExtensionTest {

    @Test
    void effortUnsupportedByTheCurrentModelNeverBecomesTheSelection() throws Exception {
        GrokAiInfoBarExtension provider = new GrokAiInfoBarExtension();

        SwingUtilities.invokeAndWait(() -> provider.setSelectedModel("grok-4.5"));

        // xhigh is grok-4.6-only; grok-4.5 supports only low/medium/high.
        SwingUtilities.invokeAndWait(() -> provider.setSelectedReasoningEffort("xhigh"));

        assertNull(provider.getSelectedReasoningEffort(),
                "xhigh is unsupported by grok-4.5 and must fall back to \"(model default)\", not become the "
                + "actual selection");
    }

    @Test
    void onSessionSettingsChangedRebuildsEffortOptionsForTheNewModelBeforeApplyingTheEffort() throws Exception {
        GrokAiInfoBarExtension provider = new GrokAiInfoBarExtension();
        SwingUtilities.invokeAndWait(() -> provider.setSelectedModel("grok-4.5"));

        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.6");
        // xhigh is invalid for the OLD model (grok-4.5) but valid for the NEW one (grok-4.6): if the effort combo's
        // options were not rebuilt for the new model before applying the effort, this would silently fall back to
        // "(model default)" instead of actually selecting xhigh.
        settings.setReasoningEffort("xhigh");

        SwingUtilities.invokeAndWait(() -> provider.onSessionSettingsChanged(settings));

        assertEquals("grok-4.6", provider.getSelectedModel());
        assertEquals("xhigh", provider.getSelectedReasoningEffort(),
                "the effort combo must offer the NEW model's levels (grok-4.6 supports xhigh), not stale "
                + "options left over from the old model (grok-4.5, which does not)");
    }

    @Test
    void onBusyChangedDisablesAndEnablesCombos() throws Exception {
        GrokAiInfoBarExtension provider = new GrokAiInfoBarExtension();

        SwingUtilities.invokeAndWait(() -> provider.onBusyChanged(true));

        assertFalse(provider.getModelCombo().isEnabled(), "model combo must be disabled while busy");
        assertFalse(provider.getReasoningEffortCombo().isEnabled(), "reasoning-effort combo must be disabled while busy");

        SwingUtilities.invokeAndWait(() -> provider.onBusyChanged(false));

        assertTrue(provider.getModelCombo().isEnabled(), "model combo must be enabled when ready");
        assertTrue(provider.getReasoningEffortCombo().isEnabled(), "reasoning-effort combo must be enabled when ready");
    }
}
