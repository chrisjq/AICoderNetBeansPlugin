package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The GitHub Copilot backend's start, error and stop events reach the backend debug log. A start with no executable is
 * refused before the SDK client is created, which exercises the error path and the stop that start runs first.
 */
@ResourceLock("aicoder-debuglog")
class GithubCopilotBackendDebugLogTest {

    @Test
    @Timeout(10)
    void aRefusedStartLogsItsErrorAndTheStop(@TempDir Path dir) throws Exception {
        GithubCopilotProcessManager manager = new GithubCopilotProcessManager(event -> {
        });
        manager.setSessionConfigDir(dir);
        manager.setBackendDebugLog(true);

        manager.start(dir.resolve("no-such-copilot").toString(), "m");
        manager.closeBackendDebugLog(false).get();

        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains(" EVENT stop "), text);
        assertTrue(text.contains(" EVENT error "), text);
    }
}
