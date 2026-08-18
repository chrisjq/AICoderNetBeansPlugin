package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WrapLayoutTest {

    private static <T> T onEdt(java.util.concurrent.Callable<T> fn) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(fn.call());
            }
            catch (Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) {
            throw err.get();
        }
        return result.get();
    }

    private static Component fixedSizeComponent(int w, int h) {
        JLabel c = new JLabel();
        Dimension d = new Dimension(w, h);
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        return c;
    }

    /**
     * Regression guard for the -(hgap + 1) shave that the widely-copied
     * WrapLayout applied to derive minimumLayoutSize from preferredLayoutSize.
     * With hgap == 0 that subtraction drove the width negative for any panel
     * whose preferred width is 0. This version measures minimum independently
     * from each child's getMinimumSize(), so both empty and zero-width-child
     * panels must report width >= 0.
     *
     * Would have FAILED with the old code: old min.width = 0 - (0+1) = -1.
     */
    @Test
    void minimumLayoutSize_nonNegative() throws Exception {
        Dimension empty = onEdt(() -> {
            JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 0));
            return ((WrapLayout) p.getLayout()).minimumLayoutSize(p);
        });
        assertTrue(empty.width >= 0, "empty panel: min width must be >= 0, got " + empty.width);
        assertTrue(empty.height >= 0, "empty panel: min height must be >= 0, got " + empty.height);

        Dimension oneChild = onEdt(() -> {
            JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 0));
            p.add(fixedSizeComponent(0, 10)); // zero preferred/minimum width → old code returned -1
            return ((WrapLayout) p.getLayout()).minimumLayoutSize(p);
        });
        assertTrue(oneChild.width >= 0, "zero-width child: min width must be >= 0, got " + oneChild.width);
        assertTrue(oneChild.height >= 0, "zero-width child: min height must be >= 0, got " + oneChild.height);
    }

    /**
     * When children's combined width exceeds the available width,
     * preferredLayoutSize must report multi-row height, not the height of a
     * single row.
     *
     * Would have been correct with the old code (this aspect wasn't broken),
     * but guards against future regressions in the wrapping logic itself.
     */
    @Test
    void preferredLayoutSize_narrowPanel_reportsMultipleRowsHeight() throws Exception {
        Dimension d = onEdt(() -> {
            WrapLayout layout = new WrapLayout(FlowLayout.LEFT, 0, 0);
            JPanel p = new JPanel(layout);
            p.setSize(80, 400); // panel width 80 → only one 50px child fits per row
            for (int i = 0; i < 4; i++) {
                p.add(fixedSizeComponent(50, 20));
            }
            return layout.preferredLayoutSize(p);
        });
        assertTrue(d.height > 20, "expected multi-row height for 4×50px children in 80px panel, got " + d.height);
    }

    /**
     * When the panel is wide enough to hold all children in a single row,
     * preferredLayoutSize must report that one row's height.
     *
     * Guards against always-wrapping regressions.
     */
    @Test
    void preferredLayoutSize_widePanel_singleRowHeight() throws Exception {
        Dimension d = onEdt(() -> {
            WrapLayout layout = new WrapLayout(FlowLayout.LEFT, 0, 0);
            JPanel p = new JPanel(layout);
            p.setSize(300, 400); // 300 > 4×50=200, so all children fit in one row
            for (int i = 0; i < 4; i++) {
                p.add(fixedSizeComponent(50, 20));
            }
            return layout.preferredLayoutSize(p);
        });
        assertEquals(20, d.height, "expected single-row height of 20px when all children fit, got " + d.height);
    }

    /**
     * An invisible child must not contribute to the preferred width or height.
     */
    @Test
    void preferredLayoutSize_invisibleChildIgnored() throws Exception {
        Dimension withVisible = onEdt(() -> {
            WrapLayout layout = new WrapLayout(FlowLayout.LEFT, 0, 0);
            JPanel p = new JPanel(layout);
            p.setSize(300, 200);
            p.add(fixedSizeComponent(50, 20));
            return layout.preferredLayoutSize(p);
        });
        Dimension withHidden = onEdt(() -> {
            WrapLayout layout = new WrapLayout(FlowLayout.LEFT, 0, 0);
            JPanel p = new JPanel(layout);
            p.setSize(300, 200);
            p.add(fixedSizeComponent(50, 20));
            Component hidden = fixedSizeComponent(9999, 9999);
            hidden.setVisible(false);
            p.add(hidden);
            return layout.preferredLayoutSize(p);
        });
        assertEquals(withVisible.width, withHidden.width, "invisible child must not affect preferred width");
        assertEquals(withVisible.height, withHidden.height, "invisible child must not affect preferred height");
    }

    /**
     * A parentless panel with getWidth() == 0 exercises the Integer.MAX_VALUE
     * fallback path in availableWidth(). The call must not throw and must
     * return a sane size.
     */
    @Test
    void preferredLayoutSize_parentlessZeroWidthPanel_doesNotThrow() throws Exception {
        Dimension d = onEdt(() -> {
            WrapLayout layout = new WrapLayout(FlowLayout.LEFT, 0, 0);
            JPanel p = new JPanel(layout);
            // No parent, no setSize → getWidth() == 0, ancestor walk finds nothing
            p.add(fixedSizeComponent(50, 20));
            return layout.preferredLayoutSize(p);
        });
        assertTrue(d.width >= 0 && d.height >= 0, "must return a non-negative size, got " + d);
    }

    /**
     * The static factory wrappingRow() must attach a ComponentListener for
     * revalidation on resize — the fix is the listener, and the realistic
     * regression is constructing panel + layout separately, silently dropping
     * it.
     *
     * Would FAIL without the addComponentListener() call inside wrappingRow().
     */
    @Test
    void wrappingRow_hasWrapLayoutAndComponentListener() throws Exception {
        onEdt(() -> {
            JPanel row = WrapLayout.wrappingRow(FlowLayout.LEFT, 4, 0);
            assertInstanceOf(WrapLayout.class, row.getLayout(),
                    "wrappingRow must produce a panel with a WrapLayout");
            assertTrue(row.getComponentListeners().length >= 1,
                    "wrappingRow must register a ComponentListener for resize revalidation");
            return null;
        });
    }
}
