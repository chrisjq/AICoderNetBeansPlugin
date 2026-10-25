package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session.ClaudePersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Drives the Claude process manager through its {@code /bin/cat} harness: the prompt is written to stdin (SENT) and cat
 * echoes it back on stdout (RECEIVED). Verifies the backend debug log records both directions, redacts registered
 * secrets, and creates no file while the setting is off.
 */
@ResourceLock("aicoder-debuglog")
class ClaudeBackendDebugLogTest {

    private static final String SECRET = "claude-debug-secret-value";
    private static final String LOG_FILE = "backend-debug.log";

    private DebugCatManager manager;

    @AfterEach
    void teardown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    @Timeout(10)
    void sentAndReceivedLinesAreLoggedAndTheSecretIsRedacted(@TempDir Path dir) throws Exception {
        manager = new DebugCatManager(dir);
        manager.setBackendDebugLog(true);
        manager.registerSecretForTest(SECRET);

        manager.sendPrompt("hello " + SECRET, dir.toFile(), List.of());
        Path log = dir.resolve(LOG_FILE);
        awaitContaining(log, " RECEIVED ");
        manager.closeBackendDebugLog(false).get();

        String text = Files.readString(log);
        assertEquals(1, countOccurrences(text, " SENT "), text);
        assertEquals(1, countOccurrences(text, " RECEIVED "), text);
        assertFalse(text.contains(SECRET), "the registered secret must never be written");
    }

    @Test
    @Timeout(10)
    void noFileIsCreatedWhileTheSettingIsOff(@TempDir Path dir) throws Exception {
        manager = new DebugCatManager(dir);

        manager.sendPrompt("hello", dir.toFile(), List.of());
        manager.launchCommand(0);
        manager.closeBackendDebugLog(false).get();

        assertFalse(Files.exists(dir.resolve(LOG_FILE)));
    }

    private static void awaitContaining(Path log, String marker) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!(Files.exists(log) && Files.readString(log).contains(marker))) {
            if (System.currentTimeMillis() > deadline) {
                fail("timeout waiting for " + marker);
            }
            Thread.sleep(20);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    static final class DebugCatManager extends ClaudeAiProcessManager {

        private final List<List<String>> launchCommands = new CopyOnWriteArrayList<>();

        DebugCatManager(Path configDir) {
            super((AiProcessEventListener) event -> {
            });
            setSessionConfigDir(configDir);
            running = true;
            sessionId = UUID.randomUUID().toString();
            model = "test-model";
            executablePath = "/bin/cat";
        }

        void registerSecretForTest(String value) {
            registerDebugSecret(value);
        }

        @Override
        protected ClaudePersistentSession launchPersistentSession(List<String> cmd, File workDir,
                                                                  Consumer<String> stdoutLine, Consumer<String> stderrLine) throws IOException {
            launchCommands.add(List.copyOf(cmd));
            return ClaudePersistentSession.launch(List.of("/bin/cat"), workDir, stdoutLine, stderrLine);
        }

        List<String> launchCommand(int index) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (launchCommands.size() <= index) {
                if (System.currentTimeMillis() > deadline) {
                    fail("timeout waiting for launch command #" + (index + 1));
                }
                Thread.sleep(20);
            }
            return launchCommands.get(index);
        }
    }
}
