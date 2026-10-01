package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins the post-handshake race fix in {@code handshakeAndSend} via its extracted seam
 * {@link OpenCodeAiProcessManager#deliverAfterHandshake}: {@code processing} stays held across the hand-off
 * and the queued prompt goes straight to {@code sendTurn} — never back through {@code sendPrompt}, whose own
 * guard (with {@code processing} still true) would silently drop the prompt. Mirrors
 * {@code CodexAiProcessManagerHandshakeTest} — same race, same fix shape, same shared
 * {@code AiProcessManager} base fields. Reverting {@link OpenCodeAiProcessManager#deliverAfterHandshake} to
 * the pre-fix shape (unconditional clear + {@code sendPrompt} re-entry) turns the first test red on both
 * counts.
 */
class OpenCodeAiProcessManagerHandshakeTest {

    /**
     * Records both delivery routes so each test can prove which one ran. State mutators live here because the
     * lifecycle flags are protected in AiProcessManager and only reachable through the subclass itself.
     */
    private static class RecordingManager extends OpenCodeAiProcessManager {

        final List<String> directTurns = new ArrayList<>();
        final List<String> promptReentries = new ArrayList<>();

        RecordingManager(AiProcessEventListener listener) {
            super(listener);
        }

        @Override
        synchronized void sendTurn(String text) {
            directTurns.add(text);
        }

        @Override
        public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
            promptReentries.add(text);
        }

        /**
         * Each arm method opens a handshake turn the way sendPrompt does and returns it.
         */
        Object armDeliverable() {
            running = true;
            processing = true;
            pendingDiff = false;
            return openTurn();
        }

        Object armPendingDiff() {
            running = true;
            pendingDiff = true;
            processing = true;
            return openTurn();
        }

        Object armStoppedButProcessing() {
            running = false;
            pendingDiff = false;
            processing = true;
            return openTurn();
        }

        private Object openTurn() {
            Object turn = new Object();
            handshakeTurn = turn;
            return turn;
        }

        boolean processingFlag() {
            return processing;
        }
    }

    @Test
    void deliverableAtHandoff_deliversViaSendTurn_neverBackThroughSendPrompt() {
        RecordingManager manager = new RecordingManager(event -> {
        });
        Object turn = manager.armDeliverable();

        manager.deliverAfterHandshake("hello", turn);

        assertEquals(List.of("hello"), manager.directTurns,
                "post-handshake delivery must go straight to sendTurn");
        assertTrue(manager.promptReentries.isEmpty(),
                "a sendPrompt re-entry would be rejected by its own guard and drop the prompt");
        assertTrue(manager.processingFlag(),
                "hand-off holds processing; only sendTurn paths rearm or losers clear it");
    }

    @Test
    void pendingDiffAtHandoff_clearsProcessingExactlyOnce_withoutAnyDelivery() {
        RecordingManager manager = new RecordingManager(event -> {
        });
        Object turn = manager.armPendingDiff();

        manager.deliverAfterHandshake("queued", turn);

        assertFalse(manager.processingFlag(), "diff panel won the race: cleared once, under the monitor");
        assertTrue(manager.directTurns.isEmpty());
        assertTrue(manager.promptReentries.isEmpty());
    }

    @Test
    void stoppedBeforeHandoff_clearsWithoutDelivery() {
        RecordingManager manager = new RecordingManager(event -> {
        });
        Object turn = manager.armStoppedButProcessing();

        manager.deliverAfterHandshake("late", turn);

        assertFalse(manager.processingFlag());
        assertTrue(manager.directTurns.isEmpty());
        assertTrue(manager.promptReentries.isEmpty());
    }

    /**
     * Stop (or stop(), or a reported exit) ended the turn while its handshake ran, and a newer turn has since
     * claimed processing. The stale hand-off must not send the stopped prompt, must not report anything, and
     * must not clear the newer turn's processing flag.
     */
    @Test
    void turnEndedWhileHandshakeRan_sendsNothing_andLeavesTheNewerTurnsProcessingAlone() {
        List<kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent> events = new ArrayList<>();
        RecordingManager manager = new RecordingManager(events::add);
        Object stoppedTurn = manager.armDeliverable();
        manager.armDeliverable(); // a newer turn now owns the handshake slot and processing

        manager.deliverAfterHandshake("stopped prompt", stoppedTurn);

        assertTrue(manager.directTurns.isEmpty(), "a turn the user stopped must not be sent");
        assertTrue(manager.promptReentries.isEmpty());
        assertTrue(events.isEmpty(), "the turn already has its closing status: " + events);
        assertTrue(manager.processingFlag(), "the newer turn's processing must be left alone");
    }
}
