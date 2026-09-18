package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session.PiPersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live finding (Boss 2026-09-19): pi's {@code abort} produces plain tool-result text ("Command aborted") with nothing
 * distinguishing "the user cancelled this" from "this tool genuinely failed" — a real pi session confirmed it would
 * have retried an interrupted command without a notice explaining what happened. Verifies
 * {@link PiAiProcessManager#interrupt} arms a one-off notice that {@link PiAiProcessManager#sendPrompt} prepends to the
 * NEXT turn's message exactly once, captured via the fake process's stdin echoed to a file (mirrors
 * {@code PiMailDeliveryTest}'s harness).
 */
class PiCancelNoticeTest {

    private static final String NOTICE_MARKER = "Your previous turn was stopped by the user";

    private RecordingEventListener events;
    private TestablePiAiProcessManager manager;
    private File workDir;
    private File capturedStdinFile;

    private static void awaitTrue(BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @BeforeEach
    void setup() throws IOException {
        events = new RecordingEventListener();
        workDir = Files.createTempDirectory("pi-cancel-notice-test").toFile();
        capturedStdinFile = Files.createTempFile("pi-cancel-notice-test-stdin", ".jsonl").toFile();
        manager = new TestablePiAiProcessManager(events, capturedStdinFile);
        manager.setupForTest();
    }

    @AfterEach
    void teardown() {
        manager.stop();
    }

    private String capturedStdin() throws IOException {
        return Files.readString(capturedStdinFile.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void cancelThenNewPromptIncludesTheNoticeExactlyOnce() throws Exception {
        manager.sendPrompt("first turn", workDir, List.of());
        assertTrue(manager.isProcessing());

        manager.interrupt(InterruptTypeEnum.Cancel);
        assertFalse(manager.isProcessing());

        manager.sendPrompt("second turn", workDir, List.of());

        awaitTrue(() -> {
            try {
                return capturedStdin().contains("second turn");
            }
            catch (IOException e) {
                return false;
            }
        }, "second prompt written to pi's stdin");
        String stdin = capturedStdin();
        assertEquals(1, countOccurrences(stdin, NOTICE_MARKER),
                     "the notice must be prepended to the next turn exactly once");
    }

    @Test
    void normalTurnWithNoPriorCancelDoesNotIncludeTheNotice() throws Exception {
        manager.sendPrompt("just a normal prompt", workDir, List.of());

        awaitTrue(() -> {
            try {
                return capturedStdin().contains("just a normal prompt");
            }
            catch (IOException e) {
                return false;
            }
        }, "prompt written to pi's stdin");
        assertFalse(capturedStdin().contains(NOTICE_MARKER),
                    "a turn with no preceding cancel must not carry the stopped-turn notice");
    }

    @Test
    void twoCancelsInARowDoNotStackTwoNotices() throws Exception {
        manager.sendPrompt("first turn", workDir, List.of());
        manager.interrupt(InterruptTypeEnum.Cancel);
        // Second press: interrupt(Cancel) already sees no turn in flight and is a no-op — must not double-arm.
        manager.interrupt(InterruptTypeEnum.Cancel);

        manager.sendPrompt("second turn", workDir, List.of());

        awaitTrue(() -> {
            try {
                return capturedStdin().contains("second turn");
            }
            catch (IOException e) {
                return false;
            }
        }, "second prompt written to pi's stdin");
        assertEquals(1, countOccurrences(capturedStdin(), NOTICE_MARKER),
                     "two cancels before the notice is consumed must still collapse into exactly one");
    }

    private static class RecordingEventListener implements AiProcessEventListener {

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
        }
    }

    static class TestablePiAiProcessManager extends PiAiProcessManager {

        private final File capturedStdinFile;

        TestablePiAiProcessManager(AiProcessEventListener listener, File capturedStdinFile) {
            super(listener);
            this.capturedStdinFile = capturedStdinFile;
        }

        void setupForTest() {
            running = true;
            sessionId = java.util.UUID.randomUUID().toString();
            model = "test-model";
            executablePath = "/bin/cat";
            toolsRegisteredWaitMillis = 50L;
            extensionPathForTests = "/tmp/aicoder-pi-test-extension.ts";
            resumeSession(java.util.UUID.randomUUID().toString());
        }

        @Override
        protected PiPersistentSession launchPersistentSession(List<String> cmd, File workDir,
                                                              Consumer<String> stdoutLine, Consumer<String> stderrLine) throws IOException {
            // Appends everything written to stdin to a file without ever echoing to stdout — avoids the spurious
            // future-completion race documented in PiAiProcessManagerStateTest's Testable subclass, while still
            // letting the test observe exactly which command frames (and message text) were sent.
            return PiPersistentSession.launch(
                    List.of("sh", "-c", "cat >> '" + capturedStdinFile.getAbsolutePath() + "'"),
                    workDir, stdoutLine, stderrLine);
        }
    }
}
