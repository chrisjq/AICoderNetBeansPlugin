package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The status line's optional tooltip: a full sentence when a backend sends one, the label text otherwise.
 */
class AiInfoBarStatusTooltipTest {

    private static AiInfoBar onEdt() throws Exception {
        AtomicReference<AiInfoBar> bar = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> bar.set(new AiInfoBar()));
        return bar.get();
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    @Test
    @Timeout(10)
    void aTooltipSentenceIsShownInsteadOfTheLabelText() throws Exception {
        AiInfoBar bar = onEdt();

        bar.setStatusMessage("Approval…", "Read is waiting for you to accept or reject it.");
        flush();

        assertEquals("Read is waiting for you to accept or reject it.", bar.statusToolTip());
    }

    @Test
    @Timeout(10)
    void withoutATooltipTheLabelTextIsTheTooltipAsBefore() throws Exception {
        AiInfoBar bar = onEdt();

        bar.setStatusMessage("Thinking… (12 tokens)");
        flush();
        assertEquals("Thinking… (12 tokens)", bar.statusToolTip());

        bar.setStatusMessage("Generating 3 s", null);
        flush();
        assertEquals("Generating 3 s", bar.statusToolTip());

        bar.setStatusMessage("Generating 3 s", " ");
        flush();
        assertEquals("Generating 3 s", bar.statusToolTip(), "a blank tooltip falls back to the label");
    }

    @Test
    @Timeout(10)
    void aClearedStatusHasNoTooltipEvenWhenOneIsSent() throws Exception {
        AiInfoBar bar = onEdt();

        bar.setStatusMessage("", "stale sentence");
        flush();

        assertNull(bar.statusToolTip());
    }

    @Test
    @Timeout(5)
    void statusEventKeepsItsOldConstructorsWithNoTooltip() {
        StatusEvent twoArgs = new StatusEvent(StatusEventTypeEnum.INFO, "Waiting 1 s");
        StatusEvent threeArgs = new StatusEvent(StatusEventTypeEnum.BUSY, "Compacting", true);
        StatusEvent busy = StatusEvent.busy("Compacting", false);
        StatusEvent withTooltip = new StatusEvent(StatusEventTypeEnum.INFO, "Approval…", false,
                                                  "Read is waiting for you to accept or reject it.");

        assertNull(twoArgs.tooltip());
        assertNull(threeArgs.tooltip());
        assertNull(busy.tooltip());
        assertFalse(twoArgs.cancellable());
        assertEquals("Read is waiting for you to accept or reject it.", withTooltip.tooltip());
    }
}
