package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the shared-code fix (Boss review, 2026-09-19): several {@code AiSessionHost.
 * updateSessionSettings}/{@code suppressNextTurn} callers reach {@link AiInfoBar#setAutoAccept}/
 * {@link AiInfoBar#setSaveHistory}/{@link AiInfoBar#setStatusMessage}/{@link AiInfoBar#setProcessing} from a background
 * thread — Grok's clear-invalid-effort callback (its own turn thread), OpenCode's and Codex's session-established
 * callbacks (their own ACP/handshake threads), and (for the status/processing pair)
 * {@code AiTopComponent.suppressNextTurn}, which at least one backend's compact-request handling already reaches off
 * the EDT. Mutating Swing components off the EDT is undefined behaviour. All four setters now self-dispatch to the EDT
 * when called off it, mirroring every other Swing-mutating setter in this codebase (e.g. the various
 * {@code *AiInfoBarExtension} classes). Every test method here runs on the JUnit thread, which is not the EDT — exactly
 * the condition that exposes the bug — matching {@code CodexAiImplementationTest}'s/
 * {@code GrokAiInfoBarExtensionTest}'s idiom of flushing with {@code SwingUtilities.invokeAndWait(() -> {})} before
 * asserting.
 */
class AiInfoBarTest {

    private static JCheckBox checkBoxWithText(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JCheckBox cb && text.equals(cb.getText())) {
                return cb;
            }
            if (c instanceof Container nested) {
                JCheckBox found = checkBoxWithText(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JButton buttonWithText(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b && text.equals(b.getText())) {
                return b;
            }
            if (c instanceof Container nested) {
                JButton found = buttonWithText(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Identified by its right-alignment (set once, in the constructor) rather than by text, since its text is exactly
     * what these tests are mutating.
     */
    private static JLabel rightAlignedLabel(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel label && label.getHorizontalAlignment() == SwingConstants.RIGHT) {
                return label;
            }
            if (c instanceof Container nested) {
                JLabel found = rightAlignedLabel(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void setAutoAcceptFromOffTheEdtEventuallyUpdatesTheCheckboxOnTheEdt() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JCheckBox autoAcceptCheck = checkBoxWithText(infoBar, "Auto-Accept");
        assertNotNull(autoAcceptCheck, "test setup: could not find the Auto-Accept checkbox");
        assertFalse(SwingUtilities.isEventDispatchThread(), "test setup: this test must run off the EDT");

        infoBar.setAutoAccept(true);
        // The call above returns immediately, having only queued the mutation — flush the EDT before asserting, or
        // this would pass vacuously regardless of whether the fix actually works.
        SwingUtilities.invokeAndWait(() -> {
        });

        assertTrue(autoAcceptCheck.isSelected(), "the checkbox must be updated once the EDT catches up");
    }

    @Test
    void setSaveHistoryFromOffTheEdtEventuallyUpdatesTheCheckboxOnTheEdt() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JCheckBox saveHistoryCheck = checkBoxWithText(infoBar, "Save");
        assertNotNull(saveHistoryCheck, "test setup: could not find the Save checkbox");

        infoBar.setSaveHistory(true);
        SwingUtilities.invokeAndWait(() -> {
        });

        assertTrue(saveHistoryCheck.isSelected(), "the checkbox must be updated once the EDT catches up");
    }

    @Test
    void setAutoAcceptOnTheEdtAppliesImmediatelyWithNoDeferral() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JCheckBox autoAcceptCheck = checkBoxWithText(infoBar, "Auto-Accept");
        assertNotNull(autoAcceptCheck);

        SwingUtilities.invokeAndWait(() -> {
            infoBar.setAutoAccept(true);
            assertTrue(autoAcceptCheck.isSelected(),
                       "an EDT caller must see the update applied synchronously, with no invokeLater round trip — "
                       + "existing EDT callers' behaviour must not change");
        });
    }

    @Test
    void setStatusMessageFromOffTheEdtEventuallyUpdatesTheLabelOnTheEdt() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JLabel statusLabel = rightAlignedLabel(infoBar);
        assertNotNull(statusLabel, "test setup: could not find the status label");
        assertFalse(SwingUtilities.isEventDispatchThread(), "test setup: this test must run off the EDT");

        infoBar.setStatusMessage("Busy");
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals("Busy", statusLabel.getText(), "the label must be updated once the EDT catches up");
    }

    @Test
    void setStatusMessageOnTheEdtAppliesImmediatelyWithNoDeferral() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JLabel statusLabel = rightAlignedLabel(infoBar);
        assertNotNull(statusLabel);

        SwingUtilities.invokeAndWait(() -> {
            infoBar.setStatusMessage("Busy");
            assertEquals("Busy", statusLabel.getText(),
                         "an EDT caller must see the update applied synchronously, with no invokeLater round trip");
        });
    }

    @Test
    void setProcessingFromOffTheEdtEventuallyShowsTheStopButtonOnTheEdt() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JButton stopButton = buttonWithText(infoBar, "■ Stop");
        assertNotNull(stopButton, "test setup: could not find the Stop button");
        assertFalse(stopButton.isVisible(), "test setup: the Stop button starts hidden");
        assertFalse(SwingUtilities.isEventDispatchThread(), "test setup: this test must run off the EDT");

        infoBar.setProcessing(true);
        SwingUtilities.invokeAndWait(() -> {
        });

        assertTrue(stopButton.isVisible(), "the Stop button must be shown once the EDT catches up");
    }

    @Test
    void setProcessingOnTheEdtAppliesImmediatelyWithNoDeferral() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JButton stopButton = buttonWithText(infoBar, "■ Stop");
        assertNotNull(stopButton);

        SwingUtilities.invokeAndWait(() -> {
            infoBar.setProcessing(true);
            assertTrue(stopButton.isVisible(),
                       "an EDT caller must see the update applied synchronously, with no invokeLater round trip");
        });
    }

    // ---- deferral discriminators (Boss review 2026-09-19): the flush-then-assert
    // tests above prove "the value eventually applies" and "no over-deferral", but
    // neither discriminates guarded from pre-fix code — pre-fix, the setter ran
    // synchronously on the JUnit thread and produced the same end state. Each test
    // below fails if ITS setter's EDT guard is removed: with the guard deleted the
    // component would be mutated inline on the calling thread, so the
    // "not yet applied" assertion fires. Determinism comes from the event queue's
    // FIFO order: a blocking EDT task is queued first, then the setter's own queued
    // task, so the setter cannot have run while the EDT is still inside the blocker.
    private static void assertDeferredOffEdtApplication(Runnable offEdtCall,
                                                        Runnable assertNotYetApplied, Runnable assertApplied) throws Exception {
        assertFalse(SwingUtilities.isEventDispatchThread(), "test setup: this test must run off the EDT");
        CountDownLatch edtStarted = new CountDownLatch(1);
        CountDownLatch holdEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtStarted.countDown();
            try {
                holdEdt.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtStarted.await(10, TimeUnit.SECONDS),
                   "EDT must reach the blocking task before the setter is called");
        try {
            offEdtCall.run();
            assertNotYetApplied.run();
        }
        finally {
            holdEdt.countDown();
        }
        SwingUtilities.invokeAndWait(() -> {
        });
        assertApplied.run();
    }

    @Test
    void setAutoAcceptOffEdtIsDeferredNotAppliedOnCallingThread() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JCheckBox autoAcceptCheck = checkBoxWithText(infoBar, "Auto-Accept");
        assertNotNull(autoAcceptCheck, "test setup: could not find the Auto-Accept checkbox");
        assertFalse(autoAcceptCheck.isSelected(), "test setup: Auto-Accept starts unchecked");

        assertDeferredOffEdtApplication(
                () -> infoBar.setAutoAccept(true),
                () -> assertFalse(autoAcceptCheck.isSelected(),
                                  "off-EDT setAutoAccept must not apply synchronously on the calling thread "
                                  + "— without the guard this assert would already see the checkbox selected"),
                () -> assertTrue(autoAcceptCheck.isSelected(), "the checkbox must be selected once the EDT catches up"));
    }

    @Test
    void setSaveHistoryOffEdtIsDeferredNotAppliedOnCallingThread() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JCheckBox saveHistoryCheck = checkBoxWithText(infoBar, "Save");
        assertNotNull(saveHistoryCheck, "test setup: could not find the Save checkbox");
        assertFalse(saveHistoryCheck.isSelected(), "test setup: Save starts unchecked");

        assertDeferredOffEdtApplication(
                () -> infoBar.setSaveHistory(true),
                () -> assertFalse(saveHistoryCheck.isSelected(),
                                  "off-EDT setSaveHistory must not apply synchronously on the calling thread "
                                  + "— without the guard this assert would already see the checkbox selected"),
                () -> assertTrue(saveHistoryCheck.isSelected(), "the checkbox must be selected once the EDT catches up"));
    }

    @Test
    void setStatusMessageOffEdtIsDeferredNotAppliedOnCallingThread() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JLabel statusLabel = rightAlignedLabel(infoBar);
        assertNotNull(statusLabel, "test setup: could not find the status label");
        String original = statusLabel.getText();

        assertDeferredOffEdtApplication(
                () -> infoBar.setStatusMessage("Busy"),
                () -> assertEquals(original, statusLabel.getText(),
                                   "off-EDT setStatusMessage must not apply synchronously on the calling thread "
                                   + "— without the guard this assert would already see the label updated"),
                () -> assertEquals("Busy", statusLabel.getText(),
                                   "the label must be updated once the EDT catches up"));
    }

    @Test
    void setProcessingOffEdtIsDeferredNotAppliedOnCallingThread() throws Exception {
        AiInfoBar infoBar = new AiInfoBar();
        JButton stopButton = buttonWithText(infoBar, "■ Stop");
        assertNotNull(stopButton, "test setup: could not find the Stop button");
        assertFalse(stopButton.isVisible(), "test setup: the Stop button starts hidden");

        assertDeferredOffEdtApplication(
                () -> infoBar.setProcessing(true),
                () -> assertFalse(stopButton.isVisible(),
                                  "off-EDT setProcessing must not apply synchronously on the calling thread "
                                  + "— without the guard this assert would already see the Stop button visible"),
                () -> assertTrue(stopButton.isVisible(),
                                 "the Stop button must be shown once the EDT catches up"));
    }
}
