package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import javax.swing.LayoutFocusTraversalPolicy;

/**
 * Keeps read-only prompt text out of the Tab order while leaving it focusable.
 *
 * <p>
 * {@link WrappingHtmlLabel} must be focusable so the user can click it and copy
 * the text with Ctrl+C — a JEditorPane will not accept a copy action it cannot
 * focus. Being focusable also puts it in the Tab order by default, which on an
 * action panel means Tab stops on the prompt before reaching the buttons that
 * answer it. Swing has no "focusable by click but not by Tab" flag, so the
 * container excludes it here instead.
 *
 * <p>
 * Install with {@code setFocusTraversalPolicyProvider(true)} rather than
 * {@code setFocusCycleRoot(true)}: the panel then governs how its own children
 * are traversed, but Tab still moves out of the panel normally instead of
 * cycling inside it.
 */
class SkipTextFocusTraversalPolicy extends LayoutFocusTraversalPolicy {

    @Override
    protected boolean accept(Component component) {
        if (component instanceof WrappingHtmlLabel) {
            return false;
        }
        return super.accept(component);
    }
}
