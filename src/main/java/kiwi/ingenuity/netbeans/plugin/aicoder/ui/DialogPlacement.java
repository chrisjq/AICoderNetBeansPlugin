package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import javax.swing.Icon;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.openide.windows.WindowManager;

/**
 * Consistent placement for plugin dialogs and option panes.
 */
public final class DialogPlacement {

    private DialogPlacement() {
    }

    public static Component anchor(Component near) {
        if (near != null && near.isShowing()) {
            Window window = SwingUtilities.getWindowAncestor(near);
            if (window != null) {
                return window;
            }
        }
        Window main = WindowManager.getDefault().getMainWindow();
        return main != null ? main : near;
    }

    public static void centre(Window dialog, Component near) {
        if (dialog == null) {
            return;
        }
        Component target = anchor(near);
        dialog.setLocationRelativeTo(target);
        GraphicsConfiguration gc = target == null ? dialog.getGraphicsConfiguration() : target.getGraphicsConfiguration();
        if (gc == null && !GraphicsEnvironment.isHeadless()) {
            gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        }
        if (gc == null) {
            return;
        }
        Rectangle bounds = gc.getBounds();
        Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int left = bounds.x + insets.left;
        int top = bounds.y + insets.top;
        int right = bounds.x + bounds.width - insets.right;
        int bottom = bounds.y + bounds.height - insets.bottom;
        int usableWidth = Math.max(1, right - left);
        int usableHeight = Math.max(1, bottom - top);
        if (dialog.getWidth() > usableWidth || dialog.getHeight() > usableHeight) {
            dialog.setSize(Math.min(dialog.getWidth(), usableWidth), Math.min(dialog.getHeight(), usableHeight));
        }
        int x = Math.max(left, Math.min(dialog.getX(), right - dialog.getWidth()));
        int y = Math.max(top, Math.min(dialog.getY(), bottom - dialog.getHeight()));
        dialog.setLocation(x, y);
    }

    public static void showMessageDialog(Component parent, Object message, String title, int messageType) {
        JOptionPane.showMessageDialog(anchor(parent), message, title, messageType);
    }

    public static int showConfirmDialog(Component parent, Object message, String title, int optionType) {
        return JOptionPane.showConfirmDialog(anchor(parent), message, title, optionType);
    }

    public static int showConfirmDialog(Component parent, Object message, String title, int optionType, int messageType) {
        return JOptionPane.showConfirmDialog(anchor(parent), message, title, optionType, messageType);
    }

    public static int showOptionDialog(Component parent, Object message, String title, int optionType,
                                       int messageType, Icon icon, Object[] options, Object initialValue) {
        return JOptionPane.showOptionDialog(anchor(parent), message, title, optionType, messageType,
                                            icon, options, initialValue);
    }

    public static String showInputDialog(Component parent, Object message, String title, int messageType) {
        return JOptionPane.showInputDialog(anchor(parent), message, title, messageType);
    }

    public static Object showInputDialog(Component parent, Object message, String title, int messageType,
                                         Icon icon, Object[] selectionValues, Object initialSelectionValue) {
        return JOptionPane.showInputDialog(anchor(parent), message, title, messageType, icon,
                                           selectionValues, initialSelectionValue);
    }
}
