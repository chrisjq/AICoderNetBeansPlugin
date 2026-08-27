package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Regression guards for the streaming throttle in {@link MessagePanel}: N streamed deltas must not trigger N full
 * re-renders (the O(N²) EDT saturation that froze the IDE when several sessions streamed at once); rendering must keep
 * happening roughly once per throttle window DURING a continuous stream (a throttle, NOT a debounce — deltas must not
 * push the next render out); and {@link MessagePanel#finalise()} must flush whatever text is buffered so no tail is
 * dropped and no timer is left armed. The throttle/debounce distinction is asserted via the pure
 * {@link MessagePanel#shouldRebuildNow(long, long, boolean)} decision rule, so it is deterministic and never flaky.
 */
class MessagePanelStreamingThrottleTest {

    private static final int DELTAS = 50;

    /**
     * Test harness. Overrides {@code rebuildContent()} to count calls and record the accumulated text at each one,
     * skipping the (display-dependent) actual rendering so the test runs headless. The throttle logic under test is
     * otherwise unchanged.
     */
    static class CountingMessagePanel extends MessagePanel {

        private final List<String> rebuiltTexts = new ArrayList<>();

        CountingMessagePanel() {
            super(AiMessage.Role.ASSISTANT, false);
        }

        @Override
        protected void rebuildContent() {
            rebuiltTexts.add(getAccumulatedText());
        }

        int rebuildCount() {
            return rebuiltTexts.size();
        }

        String lastRebuiltText() {
            return rebuiltTexts.isEmpty() ? null : rebuiltTexts.get(rebuiltTexts.size() - 1);
        }
    }

    private static <T> T onEdt(Callable<T> fn) throws Exception {
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
     * The core integration regression: appending many deltas then finalising must NOT rebuild once per delta. With
     * per-delta rebuilds this ran {@value #DELTAS} times; the throttle plus the single flush keeps it to a small
     * bounded number.
     */
    @Test
    void deltasCoalesceIntoBoundedRebuilds() throws Exception {
        CountingMessagePanel panel = onEdt(CountingMessagePanel::new);
        for (int i = 0; i < DELTAS; i++) {
            final String chunk = "delta-" + i + " ";
            onEdt(() -> {
                panel.appendDelta(chunk);
                return null;
            });
        }
        onEdt(() -> {
            panel.finalise();
            return null;
        });
        int count = onEdt(panel::rebuildCount);
        assertTrue(count < DELTAS,
                "streaming N deltas must not cause N rebuilds: expected << " + DELTAS + ", got " + count);
        assertTrue(count >= 1, "finalise must flush at least one rebuild, got " + count);
    }

    /**
     * The tail-flush guarantee: after the final delta, the flushed rebuild must contain the COMPLETE text. A user who
     * stops mid-stream (cancel, Stop, turn end) must still see every character that had arrived.
     */
    @Test
    void finaliseFlushesCompleteText() throws Exception {
        CountingMessagePanel panel = onEdt(CountingMessagePanel::new);
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < DELTAS; i++) {
            final String chunk = "chunk" + i;
            expected.append(chunk);
            onEdt(() -> {
                panel.appendDelta(chunk);
                return null;
            });
        }
        onEdt(() -> {
            panel.finalise();
            return null;
        });
        // The final flush reflects the complete accumulated text, in order, with nothing dropped.
        assertEquals(expected.toString(), onEdt(panel::lastRebuiltText),
                "finalise must flush the complete accumulated text, nothing dropped or reordered");
        assertEquals(expected.toString(), onEdt(panel::getAccumulatedText),
                "accumulated buffer must retain every delta");
    }

    /**
     * The deterministic throttle-vs-debounce discriminator. The single line that matters is: a delta arriving while a
     * rebuild is already pending must do nothing to the timer. So {@code shouldRebuildNow} must return false whenever
     * pending is true, even after a huge elapsed interval — if this ever returns true (or the caller re-arms the timer)
     * while pending, the throttle has silently regressed into a debounce and a continuous stream would go blank until
     * the model pauses. The leading-edge clauses (not pending + interval elapsed -&gt; render now) are also pinned.
     */
    @Test
    void shouldRebuildNowIsATrailingThrottleNotALeadingDebounce() {
        long interval = TimeoutEnum.MESSAGE_REBUILD_THROTTLE_MILLIS.millis();
        long now = 1_000_000_000L;

        // Anti-debounce: a delta while a rebuild is pending must NEVER render an extra now, regardless of elapsed time.
        assertFalse(MessagePanel.shouldRebuildNow(now, now - 10 * interval, true),
                "pending + huge elapsed must still be false (no debounce reset / no extra render)");
        assertFalse(MessagePanel.shouldRebuildNow(now, now - interval, true),
                "pending + interval elapsed must still be false");
        assertFalse(MessagePanel.shouldRebuildNow(now, now, true),
                "pending + just rendered must be false");

        // Not pending and interval elapsed -> render now (leading edge).
        assertTrue(MessagePanel.shouldRebuildNow(now, now - interval, false),
                "not pending + exactly interval elapsed -> true (leading edge)");
        assertTrue(MessagePanel.shouldRebuildNow(now, now - interval - 5, false),
                "not pending + more than interval elapsed -> true");

        // Not pending and within interval -> defer (do not render now).
        assertFalse(MessagePanel.shouldRebuildNow(now, now - interval + 1, false),
                "not pending + just inside interval -> false (defer to window boundary)");
        assertFalse(MessagePanel.shouldRebuildNow(now, now - 1, false),
                "not pending + almost no elapsed -> false");
    }
}
