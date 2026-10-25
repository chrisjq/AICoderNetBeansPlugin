package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Dimension;
import javax.swing.JLabel;

/**
 * Shared dimensions for label-and-field forms embedded in session dialogs.
 */
public final class FormLayoutUtil {

    private FormLayoutUtil() {
    }

    /**
     * Creates a first-line-aligned label with the common form-column width.
     *
     * @param text caption shown to the user
     *
     * @return a consistently sized form label
     */
    public static JLabel label(String text) {
        JLabel label = new JLabel(text);
        applyLabelColumnWidth(label);
        return label;
    }

    /**
     * Applies the shared form-column width to an existing label.
     *
     * @param label label used in a host form
     */
    public static void applyLabelColumnWidth(JLabel label) {
        label.setVerticalAlignment(JLabel.TOP);
        label.setVerticalTextPosition(JLabel.TOP);
        Dimension preferred = label.getPreferredSize();
        int sharedWidth = Math.max(preferred.width, label.getFontMetrics(label.getFont()).charWidth('n') * 24);
        Dimension columnSize = new Dimension(sharedWidth, preferred.height);
        label.setMinimumSize(columnSize);
        label.setPreferredSize(columnSize);
    }
}
