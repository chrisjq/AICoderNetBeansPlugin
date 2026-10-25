package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session.PiPersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("aicoder-debuglog")
class PiBackendDebugLogTest {

    private static final String SCRIPT = "echo '{\"type\":\"agent_start\"}'; cat >/dev/null";

    @Test
    @Timeout(10)
    void aPromptWithTheLogOnRecordsOneSentAndOneReceivedEntry(@TempDir Path dir) throws Exception {
        String text = runOnePrompt(dir, true);

        assertNotNull(text, "the log file must exist when the setting is on");
        assertEquals(1, countOccurrences(text, "prompt"), "exactly one SENT entry carries the prompt");
        assertEquals(1, countOccurrences(text, "agent_start"), "exactly one RECEIVED entry carries the echoed event");
    }

    @Test
    @Timeout(10)
    void aPromptWithTheLogOffCreatesNoFile(@TempDir Path dir) throws Exception {
        assertNull(runOnePrompt(dir, false));
    }

    private static String runOnePrompt(Path dir, boolean logOn) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AiProcessEventListener listener = event -> {
            if (event instanceof StatusEvent se && se.type() == StatusEventTypeEnum.THINKING) {
                received.countDown();
            }
        };
        TestPi pm = new TestPi(listener, dir);
        pm.setBackendDebugLog(logOn);
        pm.setupForTest();
        try {
            pm.sendPrompt("hello", dir.toFile(), List.of());
            assertTrue(received.await(5, TimeUnit.SECONDS), "the echoed event never reached the listener");
            pm.closeBackendDebugLog(false).get(5, TimeUnit.SECONDS);
            Path log = dir.resolve("backend-debug.log");
            return Files.exists(log) ? Files.readString(log) : null;
        }
        finally {
            pm.stop();
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final class TestPi extends PiAiProcessManager {

        TestPi(AiProcessEventListener listener, Path dir) {
            super(listener);
            setSessionConfigDir(dir);
        }

        void setupForTest() {
            running = true;
            sessionId = UUID.randomUUID().toString();
            model = "test-model";
            executablePath = "/bin/cat";
            extensionPathForTests = "/tmp/aicoder-pi-debuglog-test-extension.ts";
            resumeSession(UUID.randomUUID().toString());
        }

        @Override
        protected PiPersistentSession launchPersistentSession(List<String> cmd, File workDir,
                                                              Consumer<String> stdoutLine,
                                                              Consumer<String> stderrLine) throws IOException {
            return PiPersistentSession.launch(List.of("sh", "-c", SCRIPT), workDir, stdoutLine, stderrLine);
        }
    }
}
