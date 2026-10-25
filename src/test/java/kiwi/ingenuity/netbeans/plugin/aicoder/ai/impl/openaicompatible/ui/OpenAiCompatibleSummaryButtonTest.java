package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleSummaryButtonTest {

    private static final class Snapshot {

        boolean enabled;
        String tip;
    }

    /**
     * Applies the summary on the EDT and reads the button state back there, so the result reflects the update.
     */
    private static Snapshot after(OpenAiCompatibleInfoBarExtension ext, String summary, boolean trimmed) throws Exception {
        Snapshot s = new Snapshot();
        SwingUtilities.invokeAndWait(() -> {
            ext.setSummary(summary, trimmed);
            s.enabled = ext.isSummaryEnabled();
            s.tip = ext.summaryToolTip();
        });
        return s;
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aSummaryEnablesTheButtonWithTheSummaryTooltip() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        OpenAiCompatibleInfoBarExtension ext = new OpenAiCompatibleInfoBarExtension();

        Snapshot s = after(ext, "## Goal\nship it", false);

        assertTrue(s.enabled);
        assertEquals("Show the summary of the earlier conversation", s.tip);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void noSummaryDisablesTheButtonWithNoSummaryTooltip() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        OpenAiCompatibleInfoBarExtension ext = new OpenAiCompatibleInfoBarExtension();

        Snapshot s = after(ext, null, false);

        assertFalse(s.enabled);
        assertEquals("No summary yet", s.tip);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void trimmingInsteadOfSummarisingDisablesTheButtonWithTheTrimmedTooltip() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        OpenAiCompatibleInfoBarExtension ext = new OpenAiCompatibleInfoBarExtension();

        Snapshot s = after(ext, null, true);

        assertFalse(s.enabled);
        assertEquals("Context was trimmed, not summarised", s.tip);
    }
}
