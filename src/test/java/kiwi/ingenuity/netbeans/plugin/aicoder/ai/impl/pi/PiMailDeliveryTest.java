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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec *Mail* (DURING_TURN): "the mail notice is sent as steer... pi delivers it once running tool calls finish... so
 * the interrupt hold built for Claude in F5 is not needed". Verifies {@code interrupt(Mail)} takes the {@code
 * steer} branch while a turn is running (captured via the fake process's stdin echoed to a file — see {@link
 * #capturedStdinFile}) and is a no-op while idle (the generic idle-delivery path calls {@code sendPrompt} itself, not
 * exercised here). Also pins the behavioural difference from Cancel: unlike {@code abort}, {@code steer} never ends the
 * turn — {@code processing} must stay {@code true} afterward.
 */
class PiMailDeliveryTest {

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

    @BeforeEach
    void setup() throws IOException {
        events = new RecordingEventListener();
        workDir = Files.createTempDirectory("pi-mail-test").toFile();
        capturedStdinFile = Files.createTempFile("pi-mail-test-stdin", ".jsonl").toFile();
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
    void mailDuringATurnSendsSteerAndDoesNotEndTheTurn() throws Exception {
        manager.sendPrompt("do something", workDir, List.of());
        assertTrue(manager.isProcessing());

        manager.interrupt(InterruptTypeEnum.Mail);

        // steer never aborts a running tool call and is not the turn's completion — processing must still be true.
        assertTrue(manager.isProcessing());
        awaitTrue(() -> {
            try {
                return capturedStdin().contains("\"steer\"");
            }
            catch (IOException e) {
                return false;
            }
        }, "steer command written to pi's stdin");
    }

    @Test
    void mailWhileIdleIsIgnoredByInterrupt() throws IOException {
        // No turn running, no session even spawned yet — interrupt(Mail) must not start one. The generic
        // idle-delivery path (not exercised by this unit test) is what actually starts a `prompt` turn for idle
        // mail, by calling sendPrompt() itself — see the spec's *Mail* section.
        manager.interrupt(InterruptTypeEnum.Mail);
        assertFalse(manager.isProcessing());
        assertTrue(capturedStdin().isEmpty());
    }

    @Test
    void cancelUnlikeMailDoesEndTheTurn() {
        manager.sendPrompt("do something", workDir, List.of());
        assertTrue(manager.isProcessing());

        manager.interrupt(InterruptTypeEnum.Cancel);

        assertFalse(manager.isProcessing());
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
            // letting the test observe exactly which command frames were sent.
            return PiPersistentSession.launch(
                    List.of("sh", "-c", "cat >> '" + capturedStdinFile.getAbsolutePath() + "'"),
                    workDir, stdoutLine, stderrLine);
        }
    }
}
