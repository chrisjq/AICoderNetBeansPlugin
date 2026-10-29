package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers dialog bounds for the current IDE session while rejecting stale off-screen positions.
 */
public final class DialogBounds {

    private static final int USABLE_INTERSECTION = 40;
    private static final Map<String, Rectangle> REMEMBERED = new HashMap<>();

    private DialogBounds() {
    }

    public static void applyDefaultSize(Window window, Component parent, int minimumWidth, int minimumHeight) {
        if (window == null || GraphicsEnvironment.isHeadless()) {
            return;
        }
        Rectangle usable = null;
        GraphicsConfiguration configuration = parent == null ? null : parent.getGraphicsConfiguration();
        if (configuration != null) {
            usable = new Rectangle(configuration.getBounds());
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            usable.x += insets.left;
            usable.y += insets.top;
            usable.width -= insets.left + insets.right;
            usable.height -= insets.top + insets.bottom;
        }
        if (usable == null) {
            usable = new Rectangle(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
        }
        window.setSize(defaultSize(usable, minimumWidth, minimumHeight));
    }

    static Dimension defaultSize(Rectangle usable, int minimumWidth, int minimumHeight) {
        int width = Math.max(minimumWidth, usable.width * 70 / 100);
        int height = Math.max(minimumHeight, usable.height * 70 / 100);
        width = Math.min(width, usable.width);
        height = Math.min(height, usable.height);
        return new Dimension(width, height);
    }

    public static void remember(String key, Component component) {
        if (key == null || component == null) {
            return;
        }
        Rectangle bounds = component.getBounds();
        if (bounds.width > 0 && bounds.height > 0) {
            REMEMBERED.put(key, new Rectangle(bounds));
        }
    }

    public static boolean restore(String key, Window window, int minimumWidth, int minimumHeight) {
        if (window == null || GraphicsEnvironment.isHeadless()) {
            return false;
        }
        Rectangle remembered = REMEMBERED.get(key);
        if (remembered == null) {
            return false;
        }
        Rectangle validated = validated(remembered, minimumWidth, minimumHeight, availableScreenBounds());
        if (validated == null) {
            return false;
        }
        window.setBounds(validated);
        return true;
    }

    static Rectangle validated(Rectangle remembered, int minimumWidth, int minimumHeight,
                               Collection<Rectangle> screens) {
        if (remembered == null || screens == null) {
            return null;
        }
        Rectangle candidate = new Rectangle(remembered);
        candidate.width = Math.max(candidate.width, minimumWidth);
        candidate.height = Math.max(candidate.height, minimumHeight);
        for (Rectangle screen : screens) {
            if (screen != null) {
                Rectangle intersection = candidate.intersection(screen);
                if (intersection.width >= USABLE_INTERSECTION
                        && intersection.height >= USABLE_INTERSECTION) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Collection<Rectangle> availableScreenBounds() {
        Map<String, Rectangle> screens = new HashMap<>();
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (var device : environment.getScreenDevices()) {
            for (GraphicsConfiguration configuration : device.getConfigurations()) {
                addUsableBounds(screens, configuration);
            }
        }
        return screens.values();
    }

    private static void addUsableBounds(Map<String, Rectangle> screens, GraphicsConfiguration configuration) {
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        if (bounds.width > 0 && bounds.height > 0) {
            screens.put(bounds.toString(), bounds);
        }
    }
}
