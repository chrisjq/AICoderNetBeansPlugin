package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session.PiPersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live finding (Boss 2026-09-19): the extension registers pi's plugin tools inside {@code pi.on("session_start")},
 * which does a real MCP handshake (initialize / notifications/initialized / tools/list) before any
 * {@code pi.registerTool} call — pi does not await that handler, so a prompt arriving during the window sees zero
 * plugin tools. Reproduced deterministically: ~150ms after spawn loses every tool, ~3s after spawn is clean. Verifies
 * {@link PiAiProcessManager#sendPrompt} holds the FIRST prompt after a spawn until
 * {@code aicoder-pi-extension.ts.template}'s {@code registerMcpTools} readiness notify arrives (or a bound elapses),
 * and that only that first prompt ever waits. Captured via the fake process's stdin echoed to a file (mirrors
 * {@code PiMailDeliveryTest}/{@code PiCancelNoticeTest}'s harness); the readiness notify itself is written to the fake
 * process's STDOUT, since that is the event-stream channel {@code PiStreamJsonParser}/{@code
 * buildParserListener} actually observes it on.
 */
class PiToolsRegisteredWaitTest {

    private static final String REGISTERED_NOTIFY_LINE
            = "{\"type\":\"extension_ui_request\",\"method\":\"notify\","
            + "\"message\":\"AI Coder MCP tools registered: 5\"}";
    private static final String UNAVAILABLE_NOTIFY_LINE
            = "{\"type\":\"extension_ui_request\",\"method\":\"notify\","
            + "\"message\":\"AI Coder MCP tools unavailable: connection refused\"}";

    private RecordingEventListener events;
    private TestablePiAiProcessManager manager;
    private File workDir;
    private File capturedStdinFile;

    private static void awaitTrue(BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    @BeforeEach
    void setup() throws IOException {
        events = new RecordingEventListener();
        workDir = Files.createTempDirectory("pi-tools-wait-test").toFile();
        capturedStdinFile = Files.createTempFile("pi-tools-wait-test-stdin", ".jsonl").toFile();
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

    private boolean stdinContains(String needle) {
        try {
            return capturedStdin().contains(needle);
        }
        catch (IOException e) {
            return false;
        }
    }

    @Test
    void firstPromptWaitsForTheReadySignalThenSends() throws Exception {
        manager.toolsRegisteredWaitMillis = 5000;
        manager.scriptOverride = "( sleep 0.3; printf '%s\\n' '" + REGISTERED_NOTIFY_LINE + "' ) &\n"
                + "cat >> '" + capturedStdinFile.getAbsolutePath() + "'\n";

        long start = System.currentTimeMillis();
        Thread sendThread = new Thread(() -> manager.sendPrompt("hello", workDir, List.of()));
        sendThread.start();

        Thread.sleep(100); // well before the 300ms release and nowhere near the 5s bound
        assertTrue(!stdinContains("hello"), "the prompt must not be sent before the ready signal arrives");

        sendThread.join(5000);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(stdinContains("hello"), "the prompt must be sent once the ready signal arrives");
        assertTrue(elapsed < 2000,
                   "must be released by the signal (~300ms), not by waiting out the full bound: took " + elapsed + "ms");
    }

    @Test
    void readySignalArrivingBeforeTheWaitStartsDoesNotDeadlock() throws Exception {
        manager.toolsRegisteredWaitMillis = 5000;
        // Emitted the instant the process starts, before it even reads a line — races ahead of sendPrompt's own
        // await() call, proving an already-counted-down latch never blocks regardless of arrival order.
        manager.scriptOverride = "printf '%s\\n' '" + REGISTERED_NOTIFY_LINE + "'\n"
                + "cat >> '" + capturedStdinFile.getAbsolutePath() + "'\n";

        long start = System.currentTimeMillis();
        manager.sendPrompt("hello", workDir, List.of());
        long elapsed = System.currentTimeMillis() - start;

        awaitTrue(() -> stdinContains("hello"), "prompt written to pi's stdin");
        assertTrue(elapsed < 2000, "an already-signalled latch must not block at all: took " + elapsed + "ms");
    }

    @Test
    void timeoutProceedsAndStillSendsTheFirstPrompt() throws Exception {
        manager.toolsRegisteredWaitMillis = 300;
        manager.scriptOverride = "cat >> '" + capturedStdinFile.getAbsolutePath() + "'\n"; // never notifies

        long start = System.currentTimeMillis();
        manager.sendPrompt("hello", workDir, List.of());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(stdinContains("hello"), "the prompt must still be sent once the bound elapses");
        assertTrue(elapsed >= 300, "must actually wait out the bound rather than skip it: took " + elapsed + "ms");
    }

    @Test
    void failureNoticeReleasesTheWaitImmediatelyToo() throws Exception {
        manager.toolsRegisteredWaitMillis = 5000;
        manager.scriptOverride = "( sleep 0.3; printf '%s\\n' '" + UNAVAILABLE_NOTIFY_LINE + "' ) &\n"
                + "cat >> '" + capturedStdinFile.getAbsolutePath() + "'\n";

        long start = System.currentTimeMillis();
        Thread sendThread = new Thread(() -> manager.sendPrompt("hello", workDir, List.of()));
        sendThread.start();
        sendThread.join(5000);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(stdinContains("hello"));
        assertTrue(elapsed < 2000, "the failure notice must release the wait immediately too: took " + elapsed + "ms");
    }

    @Test
    void unrelatedNotifyDoesNotReleaseTheWaitOrCountAsReadiness() throws Exception {
        // Boss 2026-09-19: a user's own pi extension calling ctx.ui.notify with unrelated text must not be mistaken
        // for either readiness prefix. Emitted instantly (races ahead of the wait, like the "before it starts" test
        // above) specifically to prove that even an EARLY notify that doesn't match either prefix changes nothing.
        manager.toolsRegisteredWaitMillis = 300;
        String unrelatedNotifyLine = "{\"type\":\"extension_ui_request\",\"method\":\"notify\","
                + "\"message\":\"Something else entirely\"}";
        manager.scriptOverride = "printf '%s\\n' '" + unrelatedNotifyLine + "'\n"
                + "cat >> '" + capturedStdinFile.getAbsolutePath() + "'\n";

        long start = System.currentTimeMillis();
        manager.sendPrompt("hello", workDir, List.of());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(stdinContains("hello"), "the prompt must still be sent once the bound elapses");
        assertTrue(elapsed >= 300, "an unrelated notify must not release the wait early: took " + elapsed + "ms");
        assertTrue(events.hasEvent(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.INFO
                && "Something else entirely".equals(se.text())),
                   "the unrelated notify must still surface to the user normally — the release check is additive, not a filter");
    }

    @Test
    void secondPromptOfTheSameSpawnDoesNotWait() throws Exception {
        manager.toolsRegisteredWaitMillis = 200; // first prompt times out quickly since no notify ever arrives
        manager.scriptOverride = """
                while IFS= read -r line; do
                    printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
                    printf '%s\\n' '{"type":"agent_settled"}'
                done
                """;
        // The script above never touches capturedStdinFile directly (sed feeds stdout, not a capture file) — inspect
        // sent content via the RecordingEventListener's forwarded events would work too, but simplest is to check
        // processing/timing directly, which is all this test needs.

        long firstStart = System.currentTimeMillis();
        manager.sendPrompt("first turn", workDir, List.of());
        long firstElapsed = System.currentTimeMillis() - firstStart;
        assertTrue(firstElapsed >= 200, "the first prompt must wait out the bound since no notify ever arrives");

        awaitTrue(() -> !manager.isProcessing(), "the first turn to settle via agent_settled");

        long secondStart = System.currentTimeMillis();
        manager.sendPrompt("second turn", workDir, List.of());
        long secondElapsed = System.currentTimeMillis() - secondStart;

        assertTrue(secondElapsed < 100, "the second prompt of the same spawn must not wait at all: took " + secondElapsed + "ms");
    }

    private static class RecordingEventListener implements AiProcessEventListener {

        private final List<AiProcessEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            received.add(event);
        }

        boolean hasEvent(Predicate<AiProcessEvent> predicate) {
            return received.stream().anyMatch(predicate);
        }
    }

    static class TestablePiAiProcessManager extends PiAiProcessManager {

        private final File capturedStdinFile;
        String scriptOverride;

        TestablePiAiProcessManager(AiProcessEventListener listener, File capturedStdinFile) {
            super(listener);
            this.capturedStdinFile = capturedStdinFile;
        }

        void setupForTest() {
            running = true;
            sessionId = java.util.UUID.randomUUID().toString();
            model = "test-model";
            executablePath = "/bin/cat";
            extensionPathForTests = "/tmp/aicoder-pi-test-extension.ts";
            resumeSession(java.util.UUID.randomUUID().toString());
        }

        @Override
        protected PiPersistentSession launchPersistentSession(List<String> cmd, File workDir,
                                                              Consumer<String> stdoutLine, Consumer<String> stderrLine) throws IOException {
            String script = scriptOverride != null
                            ? scriptOverride
                            : "cat >> '" + capturedStdinFile.getAbsolutePath() + "'\n";
            return PiPersistentSession.launch(List.of("sh", "-c", script), workDir, stdoutLine, stderrLine);
        }
    }
}
