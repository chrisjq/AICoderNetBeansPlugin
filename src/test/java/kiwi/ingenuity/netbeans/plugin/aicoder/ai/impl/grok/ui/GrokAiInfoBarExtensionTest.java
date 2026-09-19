package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui;

import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/**
 * Coverage for review findings 2 and 3 (WP-C follow-up), neither previously proven by a test: this class was
 * constructed/driven by nothing in the Grok test suite until now. {@link GrokAiInfoBarExtension#setSelectedModel},
 * {@link GrokAiInfoBarExtension#setSelectedReasoningEffort} and {@link GrokAiInfoBarExtension#onSessionSettingsChanged}
 * all defer their work via {@code SwingUtilities.invokeLater} when called off the EDT (as every test method here is,
 * running on the JUnit thread) — every assertion sits behind an {@code invokeAndWait(() -> {})} flush afterward, or it
 * would pass vacuously regardless of whether the fix actually works, matching {@code CodexAiImplementationTest}'s idiom
 * for the same hazard. No {@code Thread.sleep}.
 */
class GrokAiInfoBarExtensionTest {

    @Test
    void effortUnsupportedByTheCurrentModelNeverBecomesTheSelection() throws Exception {
        GrokAiInfoBarExtension provider = new GrokAiInfoBarExtension();

        provider.setSelectedModel("grok-4.5");
        SwingUtilities.invokeAndWait(() -> {
        });

        // xhigh is grok-4.6-only; grok-4.5 supports only low/medium/high.
        provider.setSelectedReasoningEffort("xhigh");
        SwingUtilities.invokeAndWait(() -> {
        });

        assertNull(provider.getSelectedReasoningEffort(),
                   "xhigh is unsupported by grok-4.5 and must fall back to \"(model default)\", not become the "
                   + "actual selection");
    }

    @Test
    void onSessionSettingsChangedRebuildsEffortOptionsForTheNewModelBeforeApplyingTheEffort() throws Exception {
        GrokAiInfoBarExtension provider = new GrokAiInfoBarExtension();
        provider.setSelectedModel("grok-4.5");
        SwingUtilities.invokeAndWait(() -> {
        });

        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.6");
        // xhigh is invalid for the OLD model (grok-4.5) but valid for the NEW one (grok-4.6): if the effort combo's
        // options were not rebuilt for the new model before applying the effort, this would silently fall back to
        // "(model default)" instead of actually selecting xhigh.
        settings.setReasoningEffort("xhigh");

        provider.onSessionSettingsChanged(settings);
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals("grok-4.6", provider.getSelectedModel());
        assertEquals("xhigh", provider.getSelectedReasoningEffort(),
                     "the effort combo must offer the NEW model's levels (grok-4.6 supports xhigh), not stale "
                     + "options left over from the old model (grok-4.5, which does not)");
    }
}
