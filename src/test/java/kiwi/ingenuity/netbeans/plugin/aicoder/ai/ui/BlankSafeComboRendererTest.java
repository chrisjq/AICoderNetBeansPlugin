package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.JList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the UI task (Chris, 2026-09-19): the info bar's effort/thinking combos must never show a blank row.
 * Presentation only — {@link BlankSafeComboRenderer} never touches any combo's stored/selected value, only what the
 * renderer draws for it.
 */
class BlankSafeComboRendererTest {

    private final BlankSafeComboRenderer renderer = new BlankSafeComboRenderer();
    private final JList<Object> list = new JList<>();

    @Test
    void nullItemRendersAsThePlaceholder() {
        Component c = renderer.getListCellRendererComponent(list, null, 0, false, false);
        assertEquals(BlankSafeComboRenderer.DEFAULT_OPTION, ((JLabel) c).getText());
    }

    @Test
    void emptyStringItemRendersAsThePlaceholder() {
        Component c = renderer.getListCellRendererComponent(list, "", 0, false, false);
        assertEquals(BlankSafeComboRenderer.DEFAULT_OPTION, ((JLabel) c).getText());
    }

    @Test
    void whitespaceOnlyItemRendersAsThePlaceholder() {
        Component c = renderer.getListCellRendererComponent(list, "   ", 0, false, false);
        assertEquals(BlankSafeComboRenderer.DEFAULT_OPTION, ((JLabel) c).getText());
    }

    @Test
    void normalTextRendersUnchanged() {
        Component c = renderer.getListCellRendererComponent(list, "high", 0, false, false);
        assertEquals("high", ((JLabel) c).getText());
    }

    @Test
    void aNonBlankItemPassesThroughUnchanged() {
        // This renderer substitutes ONLY for null/blank items — any other non-blank item text passes straight
        // through, per the task's "otherwise renders the item text unchanged" requirement.
        Component c = renderer.getListCellRendererComponent(list, "some non-blank text", 0, false, false);
        assertEquals("some non-blank text", ((JLabel) c).getText());
    }
}
