package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The Grok backend's start, error and stop events reach the backend debug log. The refused start covers Grok's own
 * start-up error; the turn failure covers the shared ACP error path that Grok and OpenCode both use.
 */
@ResourceLock("aicoder-debuglog")
class GrokBackendDebugLogTest {

    @Test
    @Timeout(10)
    void aRefusedStartLogsItsErrorAndTheStop(@TempDir Path dir) throws Exception {
        GrokAiProcessManager manager = new GrokAiProcessManager(event -> {
        });
        manager.setSessionConfigDir(dir);
        manager.setBackendDebugLog(true);

        manager.start(dir.resolve("no-such-grok").toString(), "m");
        manager.closeBackendDebugLog(false).get();

        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains(" EVENT stop "), text);
        assertTrue(text.contains(" EVENT error "), text);
    }

    @Test
    @Timeout(10)
    void aFailedTurnLogsItsError(@TempDir Path dir) throws Exception {
        GrokAiProcessManager manager = new GrokAiProcessManager(event -> {
        });
        manager.setSessionConfigDir(dir);
        manager.setBackendDebugLog(true);

        manager.handleTurnError(new IllegalStateException("boom"));
        manager.closeBackendDebugLog(false).get();

        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains(" EVENT error "), text);
    }
}
