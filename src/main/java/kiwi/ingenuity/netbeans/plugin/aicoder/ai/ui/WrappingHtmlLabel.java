package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

/**
 * Read-only prompt text that wraps to the width of its nearest sized ancestor.
 *
 * <p>
 * Backed by a JTextArea rather than an HTML pane. The HTML route wrapped at
 * spaces but would not break a long unbroken token — a file path — so confirm
 * prompts were clipped at the right edge instead of continuing on the next
 * line. JTextArea with {@code lineWrap} and {@code wrapStyleWord} breaks at
 * word boundaries where it can and falls back to breaking inside a token when a
 * single token is wider than the line, which is exactly what a long path needs.
 *
 * <p>
 * It also inherits the look and feel's font and colours directly, needing no
 * stylesheet, and its text selects and copies as plain text with no markup.
 */
class WrappingHtmlLabel extends JTextArea {

    WrappingHtmlLabel(String text) {
        super(text);
        setLineWrap(true);
        // Prefer word boundaries, but break inside a token when one alone is
        // wider than the line. Without this a long path would still overflow.
        setWrapStyleWord(true);
        setEditable(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        // Focusable so the text can be selected and copied. A non-editable text
        // component may not take focus on click in some look-and-feels, and
        // NetBeans can swallow Ctrl+C before it arrives, so both are wired below.
        setFocusable(true);

        // Family from the look and feel, size from the plugin's chat font setting
        // so prompts match the surrounding conversation text rather than the
        // IDE's default label size.
        int chatSize = PluginSettings.getChatFontSize();
        Font labelFont = UIManager.getFont("Label.font");
        setFont(labelFont != null
                ? labelFont.deriveFont((float) chatSize)
                : new Font("SansSerif", Font.PLAIN, chatSize));
        Color labelFg = UIManager.getColor("Label.foreground");
        if (labelFg != null) {
            setForeground(labelFg);
        }

        int copyMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, copyMask), "nb-copy");
        getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_A, copyMask), "select-all");
        getActionMap().put("nb-copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copy();
            }
        });
        getActionMap().put("select-all", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectAll();
            }
        });

        // Once laid out, getPreferredSize() measures at getWidth() — which during
        // a resize is still the PREVIOUS pass's width, because a parent computes
        // its children's preferred sizes before calling their setBounds. The
        // stale height then gets baked in and nothing schedules a second pass
        // (invalidate alone does not request one). Revalidating whenever our own
        // width actually changes forces that second, converging pass.
        addComponentListener(new ComponentAdapter() {
            private int lastWidth = 0;

            @Override
            public void componentResized(ComponentEvent e) {
                int w = getWidth();
                if (w != lastWidth && w > 0) {
                    lastWidth = w;
                    SwingUtilities.invokeLater(WrappingHtmlLabel.this::revalidate);
                }
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        // BoxLayout queries preferred size before bounds are set, so getWidth()
        // is 0 on the first pass. Walk up to the first ancestor with a known
        // width, subtract every intermediate inset, then measure the wrapped
        // height at that width.
        int w = getWidth();
        if (w <= 0) {
            Container ancestor = getParent();
            while (ancestor != null && ancestor.getWidth() <= 0) {
                ancestor = ancestor.getParent();
            }
            if (ancestor != null) {
                w = ancestor.getWidth();
                Insets ins = ancestor.getInsets();
                if (ins != null) {
                    w -= ins.left + ins.right;
                }
                for (Container c = getParent(); c != null && c != ancestor; c = c.getParent()) {
                    ins = c.getInsets();
                    if (ins != null) {
                        w -= ins.left + ins.right;
                    }
                }
            }
        }
        if (w > 0) {
            setSize(w, Short.MAX_VALUE);
        }
        Dimension pref = super.getPreferredSize();
        // Never ask for more width than we were given, or the panel is forced
        // wider than the viewport and the text is clipped rather than wrapped.
        if (w > 0 && pref.width > w) {
            pref = new Dimension(w, pref.height);
        }
        return pref;
    }
}
