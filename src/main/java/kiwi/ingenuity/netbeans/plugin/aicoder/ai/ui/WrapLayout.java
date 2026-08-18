package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * FlowLayout that reports the size of the wrapped result rather than a single
 * row. FlowLayout.layoutContainer already wraps to the container width, but its
 * preferredLayoutSize always measures one row, so the caller allocates too
 * little height and the wrapped rows are clipped vertically.
 */
class WrapLayout extends FlowLayout {

    /**
     * Creates a panel using this layout that revalidates itself when its width
     * changes. layoutSize measures against the width available during the
     * current pass, which while resizing is still the previous pass's value —
     * so shrinking the panel until the buttons need an extra row would leave
     * the reported height one pass behind and clip that row. A LayoutManager
     * cannot observe resizes itself, so the panel carries the listener. Prefer
     * this over constructing the panel and layout separately, which silently
     * omits it.
     */
    static JPanel wrappingRow(int align, int hgap, int vgap) {
        JPanel row = new JPanel(new WrapLayout(align, hgap, vgap));
        row.addComponentListener(new ComponentAdapter() {
            private int lastWidth = 0;

            @Override
            public void componentResized(ComponentEvent e) {
                int w = row.getWidth();
                if (w != lastWidth && w > 0) {
                    lastWidth = w;
                    SwingUtilities.invokeLater(row::revalidate);
                }
            }
        });
        return row;
    }

    /**
     * Width available to lay out in. The container's own width is 0 until it
     * has been laid out, so fall back to the nearest sized ancestor minus all
     * intermediate insets — same approach WrappingHtmlLabel uses.
     */
    private static int availableWidth(Container target) {
        int w = target.getWidth();
        if (w > 0) {
            return w;
        }
        Container ancestor = target.getParent();
        while (ancestor != null && ancestor.getWidth() <= 0) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            return Integer.MAX_VALUE;
        }
        w = ancestor.getWidth();
        Insets ins = ancestor.getInsets();
        if (ins != null) {
            w -= ins.left + ins.right;
        }
        for (Container c = target.getParent(); c != null && c != ancestor; c = c.getParent()) {
            ins = c.getInsets();
            if (ins != null) {
                w -= ins.left + ins.right;
            }
        }
        return w > 0 ? w : Integer.MAX_VALUE;
    }

    WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        // No hgap shave here. The widely-copied WrapLayout subtracts (hgap + 1)
        // because it derives minimum from preferredLayoutSize and needs it to
        // come out strictly smaller. This measures minimum independently from
        // each child's getMinimumSize(), so that rationale does not apply — and
        // the subtraction would drive width negative for a near-empty row.
        return layoutSize(target, false);
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
            int maxWidth = availableWidth(target) - horizontalInsetsAndGap;
            if (maxWidth <= 0) {
                maxWidth = Integer.MAX_VALUE;
            }

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            for (int i = 0; i < target.getComponentCount(); i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (rowWidth > 0 && rowWidth + hgap + d.width > maxWidth) {
                    addRow(dim, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth > 0) {
                    rowWidth += hgap;
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(dim, rowWidth, rowHeight);

            dim.width += horizontalInsetsAndGap;
            dim.height += insets.top + insets.bottom + (vgap * 2);
            return dim;
        }
    }

    private void addRow(Dimension dim, int rowWidth, int rowHeight) {
        dim.width = Math.max(dim.width, rowWidth);
        if (dim.height > 0) {
            dim.height += getVgap();
        }
        dim.height += rowHeight;
    }
}
