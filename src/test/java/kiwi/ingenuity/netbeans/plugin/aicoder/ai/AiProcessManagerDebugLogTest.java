package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.DebugDirectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("aicoder-debuglog")
class AiProcessManagerDebugLogTest {

    @Test
    @Timeout(10)
    void disabledLogWritesNothing(@TempDir Path dir) throws Exception {
        var pm = new TestManager(dir);
        pm.callDebugLog(DebugDirectionEnum.SENT, "k", "v");
        pm.closeBackendDebugLog(false).get();
        assertFalse(Files.exists(dir.resolve("backend-debug.log")));
    }

    @Test
    @Timeout(10)
    void enabledLogWritesAndRedactsRegisteredSecrets(@TempDir Path dir) throws Exception {
        var pm = new TestManager(dir);
        pm.setBackendDebugLog(true);
        pm.callRegisterDebugSecret("sk-abc");
        pm.callDebugLog(DebugDirectionEnum.SENT, "request", "Bearer sk-abc");
        pm.closeBackendDebugLog(false).get();
        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains("request"));
        assertFalse(text.contains("sk-abc"));
    }

    @Test
    @Timeout(10)
    void closeWithDeleteRemovesTheFile(@TempDir Path dir) throws Exception {
        var pm = new TestManager(dir);
        pm.setBackendDebugLog(true);
        pm.callDebugLog(DebugDirectionEnum.EVENT, "start", "");
        pm.closeBackendDebugLog(true).get();
        assertFalse(Files.exists(dir.resolve("backend-debug.log")));
    }

    private static final class TestManager extends AiProcessManager {

        TestManager(Path dir) {
            super((AiProcessEventListener) event -> {
            });
            setSessionConfigDir(dir);
        }

        void callDebugLog(DebugDirectionEnum direction, String kind, String payload) {
            debugLog(direction, kind, payload);
        }

        void callRegisterDebugSecret(String value) {
            registerDebugSecret(value);
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
}
