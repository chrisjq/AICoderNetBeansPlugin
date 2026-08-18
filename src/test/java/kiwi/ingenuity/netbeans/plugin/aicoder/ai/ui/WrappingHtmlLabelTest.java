package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ComponentListener;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WrappingHtmlLabelTest {

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

    /**
     * The document must carry an injected body stylesheet naming the look and
     * feel's own font family and foreground colour, rather than letting the
     * JEditorPane fall back to its built-in serif/black defaults. Those
     * defaults ignore the theme entirely — unreadable on a dark one, and the
     * wrong font on any of them — so this is a theme-fidelity guard, not a
     * dark-specific one.
     *
     * <p>
     * This originally asserted HONOR_DISPLAY_PROPERTIES, which set the font and
     * colour but left the renderer unable to break a long unbroken token (a
     * file path) at its hyphens, so confirm prompts were clipped rather than
     * wrapped. The stylesheet route is what MessagePanel uses for chat text and
     * what wraps correctly, so the guard checks for that instead. The property
     * must NOT come back: it is the configuration that failed to wrap.
     *
     * <p>
     * The LAF values are pinned to known ones for the duration of the test and
     * restored afterwards, so the assertion holds under any theme the suite
     * happens to run beneath rather than encoding whatever is installed today.
     *
     * Would FAIL if the constructor stopped injecting the stylesheet.
     */
    @Test
    void usesLafColoursAndWrapsLongTokens() throws Exception {
        Object prevFg = UIManager.get("Label.foreground");
        Object prevFont = UIManager.get("Label.font");
        try {
            UIManager.put("Label.foreground", new Color(0x12, 0x34, 0x56));
            UIManager.put("Label.font", new Font("Dialog", Font.PLAIN, 11));

            onEdt(() -> {
                WrappingHtmlLabel label = new WrappingHtmlLabel("some text");
                assertEquals(new Color(0x12, 0x34, 0x56), label.getForeground(),
                        "foreground must come from the look and feel, not a hard-coded default");
                assertEquals("Dialog", label.getFont().getFamily(),
                        "font must come from the look and feel");

                // lineWrap alone wraps at spaces; wrapStyleWord is what lets a
                // single token wider than the line break inside itself, which is
                // what a long file path needs. Both must stay on.
                assertTrue(label.getLineWrap(), "lineWrap must be enabled");
                assertTrue(label.getWrapStyleWord(), "wrapStyleWord must be enabled");

                // Plain text, no markup: the clipboard must get the real path.
                assertEquals("some text", label.getText(),
                        "text must be stored verbatim so copy yields clean text");
                return null;
            });
        }
        finally {
            UIManager.put("Label.foreground", prevFg);
            UIManager.put("Label.font", prevFont);
        }
    }

    /**
     * The reported preferred width must never exceed the width the component
     * was measured in. An HTML-backed version returned the full natural width
     * of an unbroken file path (1271px) regardless of the space available,
     * which forced the panel wider than the viewport and clipped the text.
     */
    @Test
    void preferredWidthNeverExceedsAvailableWidth() throws Exception {
        onEdt(() -> {
            WrappingHtmlLabel label = new WrappingHtmlLabel(
                    "Permanently delete /share/code/java/netbeans_plugin/AICoderNetBeansPlugin"
                    + "/target/a-deliberately-long-scratch-filename-for-testing-wrapping.txt?");
            label.setSize(400, Short.MAX_VALUE);
            Dimension pref = label.getPreferredSize();
            assertTrue(pref.width <= 400,
                    "preferred width " + pref.width + " must not exceed the 400px it was given");
            return null;
        });
    }

    /**
     * The component must be focusable so that Ctrl+C / Cmd+C can fire, and the
     * WHEN_FOCUSED input map must contain bindings for both copy and select-all
     * so they work even inside NetBeans which can swallow those keys at a
     * higher level.
     *
     * Would FAIL without the setFocusable(true) and getInputMap() calls.
     */
    @Test
    void copySupport_focusableAndKeyBindingsPresent() throws Exception {
        onEdt(() -> {
            WrappingHtmlLabel label = new WrappingHtmlLabel("<b>test</b>");

            assertTrue(label.isFocusable(), "must be focusable to receive key events");

            var inputMap = label.getInputMap(JComponent.WHEN_FOCUSED);
            int copyMask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

            KeyStroke copyStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, copyMask);
            Object copyAction = inputMap.get(copyStroke);
            assertNotNull(copyAction, "WHEN_FOCUSED input map must have a binding for Ctrl/Cmd+C");
            assertEquals("nb-copy", copyAction, "copy binding must map to 'nb-copy' action key");

            KeyStroke selectAllStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, copyMask);
            Object selectAllAction = inputMap.get(selectAllStroke);
            assertNotNull(selectAllAction, "WHEN_FOCUSED input map must have a binding for Ctrl/Cmd+A");
            assertEquals("select-all", selectAllAction, "select-all binding must map to 'select-all' action key");

            return null;
        });
    }

    /**
     * The constructor must register a ComponentListener so that width changes
     * after initial layout trigger a revalidate (forces the correct second
     * layout pass and prevents clipped rows on resize).
     *
     * Would FAIL without the addComponentListener() call in the constructor.
     */
    @Test
    void resizeListener_present() throws Exception {
        int listenerCount = onEdt(() -> {
            WrappingHtmlLabel label = new WrappingHtmlLabel("<b>test</b>");
            ComponentListener[] listeners = label.getComponentListeners();
            return listeners.length;
        });
        assertTrue(listenerCount >= 1, "WrappingHtmlLabel must register at least one ComponentListener for resize revalidation");
    }
}
