package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.GetFileContentTool;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void handle_fileAlreadyLockedByAnotherSession_showsPromptBeforeShortWriteLock() {
        String filePath = uniqueFile();
        LockManager lockManager = LockManager.getInstance();
        assertTrue(lockManager.acquireFileLock("otherSession", filePath));
        try {
            WriteFileTool tool = new WriteFileTool();
            RecordingListener listener = new RecordingListener();
            FakeSession session = new FakeSession("mySession", listener);

            String result = tool.handle(args(filePath, "hello"), session);

            assertTrue(result.toLowerCase().contains("rejected"), "the listener's deliberate denial should win: " + result);
            assertEquals(1, listener.events.size(), "the diff prompt must not be blocked by a pending short write lock");
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

    @Test
    void fileChangedWhileDiffWasOpenIsRefusedUnderTheLock() throws Exception {
        Path file = Files.writeString(Path.of(uniqueFile()), "original");
        WriteFileTool tool = new WriteFileTool();
        BlockingListener listener = new BlockingListener();
        FakeSession session = new FakeSession("mySession", listener);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> pending = executor.submit(() -> tool.handle(args(file.toString(), "new content"), session));
            assertTrue(listener.shown.await(5, TimeUnit.SECONDS), "diff approval must be shown");

            Files.writeString(file, "changed-while-diff-was-open");
            listener.decision.complete(PermissionDecision.allowed());

            String result = pending.get(5, TimeUnit.SECONDS);
            assertTrue(result.contains("changed while the diff was open"), result);
            assertEquals("changed-while-diff-was-open", Files.readString(file),
                    "the change made during review must survive — the approved write must not overwrite it");
        }
        finally {
            listener.decision.complete(PermissionDecision.denied("cleanup"));
            executor.shutdownNow();
            Files.deleteIfExists(file);
        }
    }

    /**
     * N1: GetFileContent's own flush must not make an unrelated, genuinely-unchanged write look like it
     * changed. This environment has no live NetBeans editor document for an arbitrary temp file, so the flush
     * GetFileContent performs is itself a no-op here — the assertion that matters is that routing a read
     * through the exact path WriteFile's recheck depends on, while its diff is open, still lets the approved
     * write through afterward.
     */
    @Test
    void getFileContentReadWhileDiffIsOpenDoesNotCauseAFalseRefusal() throws Exception {
        Path file = Files.writeString(Path.of(uniqueFile()), "original");
        WriteFileTool tool = new WriteFileTool();
        BlockingListener listener = new BlockingListener();
        FakeSession session = new FakeSession("mySession", listener);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> pending = executor.submit(() -> tool.handle(args(file.toString(), "updated"), session));
            assertTrue(listener.shown.await(5, TimeUnit.SECONDS), "diff approval must be shown");

            JsonObject readArgs = new JsonObject();
            readArgs.addProperty("filePath", file.toString());
            readArgs.addProperty("raw", true);
            String readResult = new GetFileContentTool(McpServerRegistry.getServer()).handle(
                    new ToolRequestArguments(readArgs), session);
            assertEquals("original", readResult, "the read must see the pre-approval content");

            listener.decision.complete(PermissionDecision.allowed());
            String writeResult = pending.get(5, TimeUnit.SECONDS);
            assertTrue(writeResult.toLowerCase().contains("saved"),
                    "a read racing the diff must not cause a false 'changed' refusal: " + writeResult);
            assertEquals("updated", Files.readString(file));
        }
        finally {
            listener.decision.complete(PermissionDecision.denied("cleanup"));
            executor.shutdownNow();
            Files.deleteIfExists(file);
        }
    }

    private static final class BlockingListener implements AiProcessEventListener {

        final CountDownLatch shown = new CountDownLatch(1);
        final CompletableFuture<PermissionDecision> decision = new CompletableFuture<>();

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            if (event instanceof PermissionEvent permission) {
                shown.countDown();
                decision.whenComplete((value, error) -> permission.response().complete(
                        error == null && value != null ? value : PermissionDecision.denied("cleanup")));
            }
        }
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
