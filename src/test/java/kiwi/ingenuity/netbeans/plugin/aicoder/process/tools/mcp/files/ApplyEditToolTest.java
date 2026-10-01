package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.GetFileContentTool;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplyEditToolTest {

    private static String uniqueFile() {
        return "/tmp/apply-edit-tool-test-" + UUID.randomUUID() + ".txt";
    }

    private static ToolRequestArguments args(String filePath, String oldString, String newString) {
        JsonObject o = new JsonObject();
        o.addProperty(McpToolPropertyEnum.FILE_PATH.key(), filePath);
        o.addProperty(McpToolPropertyEnum.OLD_STRING.key(), oldString);
        o.addProperty(McpToolPropertyEnum.NEW_STRING.key(), newString);
        return new ToolRequestArguments(o);
    }

    private static ToolRequestArguments args(String filePath, String oldString, String newString,
                                             boolean replaceAll, Integer expectedCount) {
        JsonObject o = new JsonObject();
        o.addProperty(McpToolPropertyEnum.FILE_PATH.key(), filePath);
        o.addProperty(McpToolPropertyEnum.OLD_STRING.key(), oldString);
        o.addProperty(McpToolPropertyEnum.NEW_STRING.key(), newString);
        o.addProperty(McpToolPropertyEnum.REPLACE_ALL.key(), replaceAll);
        if (expectedCount != null) {
            o.addProperty(McpToolPropertyEnum.EXPECTED_COUNT.key(), expectedCount);
        }
        return new ToolRequestArguments(o);
    }

    @BeforeEach
    void setUp() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("registry-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        McpServerRegistry.getServer().registerSession("mySession", CLAUDE, List.of(), false);
        McpServerRegistry.getServer().registerSession("otherSession", CLAUDE, List.of(), false);
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    void handle_fileAlreadyLockedByAnotherSession_showsPromptBeforeShortWriteLock() throws Exception {
        String filePath = uniqueFile();
        LockManager lockManager = LockManager.getInstance();
        assertTrue(lockManager.acquireFileLock("otherSession", filePath));
        try {
            ApplyEditTool tool = new ApplyEditTool();
            RecordingListener listener = new RecordingListener();
            FakeSession session = new FakeSession("mySession", listener);

            String result = tool.handle(args(filePath, "old", "new"), session);

            assertTrue(result.toLowerCase().contains("rejected"), "the listener's deliberate denial should win: " + result);
            assertEquals(1, listener.events.size(), "the diff prompt must not be blocked by a pending short write lock");
        }
        finally {
            lockManager.releaseFileLock("otherSession", filePath);
        }
    }

    @Test
    void handle_ownSessionConfigFile_appliesEditDirectlyWithNoPermissionEvent() throws Exception {
        // Restrict on, no project dirs registered — isFileAllowed alone would deny this;
        // only the own-config-dir bypass can let it through, and it must do so without
        // ever raising a PermissionEvent.
        String sessionId = "apply-edit-own-config-" + UUID.randomUUID();
        McpServerRegistry.getServer().registerSession(sessionId, CLAUDE, List.of(), true);
        Path configDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionId);
        Path file = Files.createFile(configDir.resolve("memory.md"));
        Files.writeString(file, "old text here");
        try {
            ApplyEditTool tool = new ApplyEditTool();
            RecordingListener listener = new RecordingListener();
            FakeSession session = new FakeSession(sessionId, listener);

            String result = tool.handle(args(file.toString(), "old text", "new text"), session);

            assertTrue(result.toLowerCase().contains("saved"), "expected success, got: " + result);
            assertTrue(listener.events.isEmpty(), "own config dir edit must bypass the diff panel — no PermissionEvent");
        }
        finally {
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionId);
        }
    }

    @Test
    void handle_releasesFileLockAfterRejection() throws Exception {
        String filePath = uniqueFile();
        ApplyEditTool tool = new ApplyEditTool();
        RecordingListener listener = new RecordingListener();
        listener.autoDecision = PermissionDecision.denied("no thanks");
        FakeSession session = new FakeSession("mySession", listener);

        String result = tool.handle(args(filePath, "old", "new"), session);

        assertTrue(result.contains("rejected"));
        LockManager lockManager = LockManager.getInstance();
        assertTrue(lockManager.acquireFileLock("otherSession", filePath));
        lockManager.releaseFileLock("otherSession", filePath);
    }

    @Test
    void handle_replaceAll_replacesEveryOccurrence() throws Exception {
        Path file = Files.createTempFile("apply-edit-replace-all-", ".txt");
        Files.writeString(file, "old one, old two, old three");
        try {
            ApplyEditTool tool = new ApplyEditTool();
            RecordingListener listener = new RecordingListener();
            listener.autoDecision = PermissionDecision.allowed();
            FakeSession session = new FakeSession("mySession", listener);

            String result = tool.handle(args(file.toString(), "old", "new", true, null), session);

            assertTrue(result.contains("3 occurrences replaced"), "expected a 3-occurrence report, got: " + result);
            assertEquals("new one, new two, new three", Files.readString(file));
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void handle_expectedCountMismatch_leavesFileUnchanged() throws Exception {
        Path file = Files.createTempFile("apply-edit-expected-count-", ".txt");
        String original = "old one, old two";
        Files.writeString(file, original);
        try {
            ApplyEditTool tool = new ApplyEditTool();
            RecordingListener listener = new RecordingListener();
            listener.autoDecision = PermissionDecision.allowed();
            FakeSession session = new FakeSession("mySession", listener);

            String result = tool.handle(args(file.toString(), "old", "new", true, 5), session);

            assertTrue(result.contains("expected 5") && result.contains("found 2"),
                    "expected a mismatch error naming both counts, got: " + result);
            assertEquals(original, Files.readString(file), "a count mismatch must change nothing");
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void handle_expectedCountWithoutReplaceAll_assertsExactMatches() throws Exception {
        Path file = Files.createTempFile("apply-edit-expected-count-single-", ".txt");
        Files.writeString(file, "only one old here");
        try {
            ApplyEditTool tool = new ApplyEditTool();
            RecordingListener listener = new RecordingListener();
            listener.autoDecision = PermissionDecision.allowed();
            FakeSession session = new FakeSession("mySession", listener);

            String result = tool.handle(args(file.toString(), "old", "new", false, 1), session);

            assertTrue(result.contains("1 occurrence replaced"), "expected a 1-occurrence report, got: " + result);
            assertEquals("only one new here", Files.readString(file));
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Pins down the documented "asserts N, replaces one" behaviour of expectedCount without replaceAll: three
     * occurrences actually present, expectedCount=3 passes the assertion, but only the FIRST is replaced —
     * the assertion and the replacement count are independent of each other.
     */
    @Test
    void handle_expectedCountWithoutReplaceAll_onThreeMatches_assertsAllButReplacesOnlyTheFirst() throws Exception {
        Path file = Files.createTempFile("apply-edit-expected-count-three-", ".txt");
        Files.writeString(file, "old one, old two, old three");
        try {
            ApplyEditTool tool = new ApplyEditTool();
            RecordingListener listener = new RecordingListener();
            listener.autoDecision = PermissionDecision.allowed();
            FakeSession session = new FakeSession("mySession", listener);

            String result = tool.handle(args(file.toString(), "old", "new", false, 3), session);

            assertTrue(result.contains("1 occurrence replaced"), "assertion passing must not change how many are replaced: " + result);
            assertEquals("new one, old two, old three", Files.readString(file), "only the first occurrence may change");
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Mutation-prove target: a negative expectedCount must be refused outright, not treated as the internal
     * "omitted" sentinel (which would silently disable the assertion the caller just asked for).
     */
    @Test
    void handle_negativeExpectedCount_isRefusedWithoutTouchingTheFile() throws Exception {
        Path file = Files.createTempFile("apply-edit-negative-expected-count-", ".txt");
        String original = "old text";
        Files.writeString(file, original);
        try {
            ApplyEditTool tool = new ApplyEditTool();
            RecordingListener listener = new RecordingListener();
            listener.autoDecision = PermissionDecision.allowed();
            FakeSession session = new FakeSession("mySession", listener);

            String result = tool.handle(args(file.toString(), "old", "new", false, -1), session);

            assertTrue(result.contains("must be 0 or greater"), result);
            assertEquals(original, Files.readString(file), "a refused negative expectedCount must change nothing");
            assertTrue(listener.events.isEmpty(), "must be refused before reaching the diff panel");
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void handle_emptyOldString_isRefused() throws Exception {
        Path file = Files.createTempFile("apply-edit-empty-old-string-", ".txt");
        String original = "some text";
        Files.writeString(file, original);
        try {
            ApplyEditTool tool = new ApplyEditTool();
            RecordingListener listener = new RecordingListener();
            listener.autoDecision = PermissionDecision.allowed();
            FakeSession session = new FakeSession("mySession", listener);

            String result = tool.handle(args(file.toString(), "", "new"), session);

            assertTrue(result.contains("must not be empty"), result);
            assertEquals(original, Files.readString(file));
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void pendingDiffApprovalDoesNotBlockSameOtherFileOrRefactorWork() throws Exception {
        String filePath = uniqueFile();
        Files.writeString(Path.of(filePath), "old");
        BlockingListener listener = new BlockingListener();
        FakeSession session = new FakeSession("mySession", listener);
        ApplyEditTool tool = new ApplyEditTool();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Future<String> pending = executor.submit(() -> tool.handle(
                    args(filePath, "old", "new"), session));
            assertTrue(listener.shown.await(5, TimeUnit.SECONDS), "diff approval must be shown");

            Future<String> sameFile = executor.submit(() -> {
                JsonObject read = new JsonObject();
                read.addProperty("filePath", filePath);
                read.addProperty("raw", true);
                return new GetFileContentTool(McpServerRegistry.getServer()).handle(
                        new ToolRequestArguments(read), session);
            });
            Future<String> otherFile = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "other-writer", List.of(filePath + "-other"), () -> "other-file-ran"));
            Future<String> refactor = executor.submit(() -> McpToolInvoker.withExclusiveMutation(
                    () -> "refactor-ran"));

            assertEquals("old", sameFile.get(2, TimeUnit.SECONDS));
            assertEquals("other-file-ran", otherFile.get(2, TimeUnit.SECONDS));
            assertEquals("refactor-ran", refactor.get(2, TimeUnit.SECONDS));

            listener.decision.complete(PermissionDecision.denied("test"));
            assertTrue(pending.get(5, TimeUnit.SECONDS).contains("rejected"));
        }
        finally {
            listener.decision.complete(PermissionDecision.denied("cleanup"));
            executor.shutdownNow();
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

    private static final class BlockingListener implements AiProcessEventListener {

        final CountDownLatch shown = new CountDownLatch(1);
        final java.util.concurrent.CompletableFuture<PermissionDecision> decision
                                                                         = new java.util.concurrent.CompletableFuture<>();

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            if (event instanceof PermissionEvent permission) {
                shown.countDown();
                decision.whenComplete((value, error) -> permission.response().complete(
                        error == null && value != null ? value : PermissionDecision.denied("cleanup")));
            }
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
