package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

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
 * {@link CodexAiProcessManager#deliverAfterHandshake}: {@code processing} stays held across the hand-off and the queued
 * prompt goes straight to {@code sendTurn} — never back through {@code sendPrompt}, whose own guard (with
 * {@code processing} still true) would silently drop the prompt. Reverting
 * {@link CodexAiProcessManager#deliverAfterHandshake} to the pre-fix shape (unconditional clear + {@code sendPrompt}
 * re-entry) turns the first test red on both counts.
 */
class CodexAiProcessManagerHandshakeTest {

    /**
     * Records both delivery routes so each test can prove which one ran. State mutators live here because the lifecycle
     * flags are protected in AiProcessManager and only reachable through the subclass itself.
     */
    private static class RecordingManager extends CodexAiProcessManager {

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

        void armDeliverable() {
            running = true;
            processing = true;
            pendingDiff = false;
        }

        void armPendingDiff() {
            running = true;
            pendingDiff = true;
            processing = true;
        }

        void armStoppedButProcessing() {
            running = false;
            pendingDiff = false;
            processing = true;
        }

        boolean processingFlag() {
            return processing;
        }
    }

    @Test
    void deliverableAtHandoff_deliversViaSendTurn_neverBackThroughSendPrompt() {
        RecordingManager manager = new RecordingManager(event -> {
        });
        manager.armDeliverable();

        manager.deliverAfterHandshake("hello");

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
        manager.armPendingDiff();

        manager.deliverAfterHandshake("queued");

        assertFalse(manager.processingFlag(), "diff panel won the race: cleared once, under the monitor");
        assertTrue(manager.directTurns.isEmpty());
        assertTrue(manager.promptReentries.isEmpty());
    }

    @Test
    void stoppedBeforeHandoff_clearsWithoutDelivery() {
        RecordingManager manager = new RecordingManager(event -> {
        });
        manager.armStoppedButProcessing();

        manager.deliverAfterHandshake("late");

        assertFalse(manager.processingFlag());
        assertTrue(manager.directTurns.isEmpty());
        assertTrue(manager.promptReentries.isEmpty());
    }
}
