package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WriteFileToolTest {

    private static String uniqueFile() {
        return "/tmp/write-file-tool-test-" + UUID.randomUUID() + ".txt";
    }

    private static ToolRequestArguments args(String filePath, String content) {
        JsonObject o = new JsonObject();
        o.addProperty(McpToolPropertyEnum.FILE_PATH.key(), filePath);
        o.addProperty("content", content);
        return new ToolRequestArguments(o);
    }

    @BeforeEach
    void setUp() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("registry-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        // restrictToProjectFiles=false so isFileAllowed() passes for any /tmp path.
        McpServerRegistry.getServer().registerSession("mySession", CLAUDE, List.of(), false);
        McpServerRegistry.getServer().registerSession("otherSession", CLAUDE, List.of(), false);
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    void handle_fileAlreadyLockedByAnotherSession_rejectsWithoutFiringPermissionEvent() {
        String filePath = uniqueFile();
        LockManager lockManager = LockManager.getInstance();
        assertTrue(lockManager.acquireFileLock("otherSession", filePath));
        try {
            WriteFileTool tool = new WriteFileTool();
            RecordingListener listener = new RecordingListener();
            FakeSession session = new FakeSession("mySession", listener);

            String result = tool.handle(args(filePath, "hello"), session);

            assertTrue(result.toLowerCase().contains("locked"), "expected a 'locked' message, got: " + result);
            assertTrue(listener.events.isEmpty(), "no PermissionEvent should be fired while the file is locked");
        }
        finally {
            lockManager.releaseFileLock("otherSession", filePath);
        }
    }

    @Test
    void handle_ownSessionConfigFile_writesDirectlyWithNoPermissionEvent() throws Exception {
        // Restrict on, no project dirs registered — isFileAllowed alone would deny this;
        // only the own-config-dir bypass can let it through, and it must do so without
        // ever raising a PermissionEvent.
        String sessionId = "write-file-own-config-" + UUID.randomUUID();
        McpServerRegistry.getServer().registerSession(sessionId, CLAUDE, List.of(), true);
        Path configDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionId);
        Path file = configDir.resolve("memory.md");
        try {
            WriteFileTool tool = new WriteFileTool();
            RecordingListener listener = new RecordingListener();
            FakeSession session = new FakeSession(sessionId, listener);

            String result = tool.handle(args(file.toString(), "remembered fact"), session);

            assertTrue(result.toLowerCase().contains("saved"), "expected success, got: " + result);
            assertTrue(listener.events.isEmpty(), "own config dir write must bypass the diff panel — no PermissionEvent");
        }
        finally {
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionId);
        }
    }

    @Test
    void handle_releasesFileLockAfterRejection() {
        String filePath = uniqueFile();
        WriteFileTool tool = new WriteFileTool();
        RecordingListener listener = new RecordingListener();
        listener.autoDecision = PermissionDecision.denied("no thanks");
        FakeSession session = new FakeSession("mySession", listener);

        String result = tool.handle(args(filePath, "hello"), session);

        assertTrue(result.contains("rejected"));
        // Lock must be released even on rejection — another session can now acquire it.
        LockManager lockManager = LockManager.getInstance();
        assertTrue(lockManager.acquireFileLock("otherSession", filePath));
        lockManager.releaseFileLock("otherSession", filePath);
    }

    private static final class NoopRegistrar extends AiMcpRegistrar {

        NoopRegistrar(String sessionId) {
            super(sessionId, AiTypeEnum.CLAUDE);
        }

        @Override
        public void addMcpEndpoint(String endpointUrl) {
        }

        @Override
        public void removeMcpEndpoint() {
        }

        @Override
        public boolean registerHooks(String serverBaseUrl) {
            return true;
        }

        @Override
        public void unregisterHooks() {
        }
    }

    private static final class RecordingListener implements AiProcessEventListener {

        final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        PermissionDecision autoDecision = PermissionDecision.denied("test default");

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            events.add(event);
            if (event instanceof PermissionEvent pe) {
                pe.response().complete(autoDecision);
            }
        }
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;
        private final AiProcessEventListener listener;

        FakeSession(String id, AiProcessEventListener listener) {
            super(AiSession.create(null, AiTypeEnum.CLAUDE));
            this.id = id;
            this.listener = listener;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return listener;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
