package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AskUserQuestionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SkipTextFocusTraversalPolicyTest {

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
     * accept() must return false for a WrappingHtmlLabel (keeps it out of the
     * Tab order while it remains focusable for click-to-copy) and true for a
     * visible, enabled, focusable JButton. The JButton must be in a realized
     * hierarchy so that LayoutFocusTraversalPolicy.super.accept() passes its
     * isDisplayable() check.
     *
     * Would FAIL without the instanceof WrappingHtmlLabel guard in accept().
     */
    @Test
    void accept_skipsWrappingHtmlLabel_passesJButton() throws Exception {
        onEdt(() -> {
            JFrame frame = new JFrame();
            try {
                SkipTextFocusTraversalPolicy policy = new SkipTextFocusTraversalPolicy();
                JPanel container = new JPanel();
                WrappingHtmlLabel label = new WrappingHtmlLabel("<b>prompt</b>");
                JButton btn = new JButton("OK");
                container.add(label);
                container.add(btn);
                frame.add(container);
                // pack() realizes the hierarchy → isDisplayable() true for all children,
                // which is required for LayoutFocusTraversalPolicy.super.accept() to pass.
                frame.pack();

                // accept is protected; directly callable from the same package.
                assertFalse(policy.accept(label),
                        "accept must return false for WrappingHtmlLabel (keep out of Tab order)");
                assertTrue(policy.accept(btn),
                        "accept must return true for a visible, enabled, focusable JButton");
            }
            finally {
                frame.dispose();
            }
            return null;
        });
    }

    /**
     * ConfirmPanel must set BOTH setFocusTraversalPolicyProvider(true) AND
     * install a SkipTextFocusTraversalPolicy. The policy alone does nothing if
     * the provider flag is absent — Tab would still traverse the parent's
     * policy scope instead.
     *
     * Would FAIL without the setFocusTraversalPolicyProvider(true) call in
     * ConfirmPanel.
     */
    @Test
    void confirmPanel_hasFocusTraversalPolicy() throws Exception {
        onEdt(() -> {
            ConfirmEvent event = new ConfirmEvent(
                    "Delete", "Delete /tmp/a.txt?", "/tmp/a.txt", null,
                    new CompletableFuture<>());
            ConfirmPanel panel = new ConfirmPanel(event);
            assertTrue(panel.isFocusTraversalPolicyProvider(),
                    "ConfirmPanel must call setFocusTraversalPolicyProvider(true)");
            assertInstanceOf(SkipTextFocusTraversalPolicy.class, panel.getFocusTraversalPolicy(),
                    "ConfirmPanel must install a SkipTextFocusTraversalPolicy");
            return null;
        });
    }

    /**
     * QuestionPanel must set BOTH setFocusTraversalPolicyProvider(true) AND
     * install a SkipTextFocusTraversalPolicy — same rationale as the
     * ConfirmPanel test above.
     *
     * Would FAIL without the setFocusTraversalPolicyProvider(true) call in
     * QuestionPanel.
     */
    @Test
    void questionPanel_hasFocusTraversalPolicy() throws Exception {
        onEdt(() -> {
            JsonArray questions = new JsonArray();
            JsonObject q = new JsonObject();
            q.addProperty("question", "Pick one:");
            JsonArray options = new JsonArray();
            JsonObject opt = new JsonObject();
            opt.addProperty("label", "A");
            options.add(opt);
            q.add("options", options);
            questions.add(q);

            AskUserQuestionEvent event = new AskUserQuestionEvent(
                    questions, new CompletableFuture<>());
            QuestionPanel panel = new QuestionPanel(event);
            assertTrue(panel.isFocusTraversalPolicyProvider(),
                    "QuestionPanel must call setFocusTraversalPolicyProvider(true)");
            assertInstanceOf(SkipTextFocusTraversalPolicy.class, panel.getFocusTraversalPolicy(),
                    "QuestionPanel must install a SkipTextFocusTraversalPolicy");
            return null;
        });
    }
}
