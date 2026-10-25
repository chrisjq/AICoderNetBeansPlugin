package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.DebugDirectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.DebugLogBudget;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("aicoder-debuglog")
class AiProcessManagerBudgetHookTest {

    @Test
    @Timeout(10)
    void theDefaultHookReturnsTheDefaultBudget() {
        assertEquals(DebugLogBudget.defaults(), new BudgetManager(null).debugLogBudget());
    }

    @Test
    @Timeout(10)
    void anOverriddenHookIsUsedWhenTheLogIsOpened(@TempDir Path dir) throws Exception {
        CappedManager manager = new CappedManager(dir);
        manager.setBackendDebugLog(true);
        manager.callDebugLog(DebugDirectionEnum.SENT, "k", "p".repeat(100));
        manager.closeBackendDebugLog(false).get();

        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains("truncated"), "the overridden 10-byte entry limit must truncate: " + text);
    }

    /**
     * Uses the default budget hook; the process manager stubs are never exercised.
     */
    private static class BudgetManager extends AiProcessManager {

        BudgetManager(Path configDir) {
            super((AiProcessEventListener) event -> {
            });
            if (configDir != null) {
                setSessionConfigDir(configDir);
            }
        }

        @Override
        public void start(String executableOrConfig, String modelOrConfig) {
        }

        @Override
        public void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        }

        @Override
        public void interrupt(InterruptTypeEnum type) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void resumeSession(String existingSessionId) {
        }

        @Override
        public boolean isMcpActive() {
            return false;
        }
    }

    private static final class CappedManager extends BudgetManager {

        CappedManager(Path configDir) {
            super(configDir);
        }

        @Override
        protected DebugLogBudget debugLogBudget() {
            return DebugLogBudget.of(8L << 20, 10_000, 10, 10L << 20, 1);
        }

        void callDebugLog(DebugDirectionEnum direction, String kind, String payload) {
            debugLog(direction, kind, payload);
        }
    }
}
