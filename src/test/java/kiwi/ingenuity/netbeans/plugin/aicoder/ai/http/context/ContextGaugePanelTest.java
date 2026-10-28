package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import kiwi.ingenuity.netbeans.plugin.aicoder.ui.UIConstants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ContextGaugePanelTest {

    @Test
    void minimumWidthMatchesInfoBarGaugeWidth() {
        assertEquals(UIConstants.INFO_BAR_CONTEXT_PROGRESS_WIDTH,
                     new ContextGaugePanel().bar().getMinimumSize().width);
    }

    @Test
    void showsNoUsageDataBeforeTheFirstUpdate() {
        assertEquals("No usage data", new ContextGaugePanel().bar().getString());
    }

    @Test
    void rendersUsedOverTotalWithThousandsSeparators() {
        ContextGaugePanel g = new ContextGaugePanel();
        g.update(1234, 8000);
        assertEquals("1,234 / 8,000", g.bar().getString());
        assertEquals(15, g.bar().getValue());
    }

    @Test
    void clampsAtOneHundredPercentWhenOverBudget() {
        ContextGaugePanel g = new ContextGaugePanel();
        g.update(99999, 8000);
        assertEquals(100, g.bar().getValue());
    }

    @Test
    void tooltipReportsRemainingAndPercentage() {
        ContextGaugePanel g = new ContextGaugePanel();
        g.update(2000, 8000);
        assertTrue(g.bar().getToolTipText().contains("6,000 remaining"));
        assertTrue(g.bar().getToolTipText().contains("25%"));
    }
}
