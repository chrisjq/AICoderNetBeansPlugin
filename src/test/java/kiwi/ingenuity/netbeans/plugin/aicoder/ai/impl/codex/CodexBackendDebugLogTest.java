package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The Codex backend's start, error and stop events reach the backend debug log. A start with no executable is refused
 * before any process is spawned, which exercises the error path and the stop that start runs first.
 */
@ResourceLock("aicoder-debuglog")
class CodexBackendDebugLogTest {

    @Test
    @Timeout(10)
    void aRefusedStartLogsItsErrorAndTheStop(@TempDir Path dir) throws Exception {
        CodexAiProcessManager manager = new CodexAiProcessManager(event -> {
        });
        manager.setSessionConfigDir(dir);
        manager.setBackendDebugLog(true);

        manager.start(dir.resolve("no-such-codex").toString(), "m");
        manager.closeBackendDebugLog(false).get();

        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains(" EVENT stop "), text);
        assertTrue(text.contains(" EVENT error "), text);
    }
}
