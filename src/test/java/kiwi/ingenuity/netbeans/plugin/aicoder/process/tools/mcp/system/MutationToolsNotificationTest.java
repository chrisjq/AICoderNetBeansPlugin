package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MutationToolsNotificationTest {

    private static final String SESSION_ID = "test-session";

    private static McpHookServer allowAllServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    private static ToolRequestArguments deleteArgs(String filePath) {
        JsonObject obj = new JsonObject();
        if (filePath != null) {
            obj.addProperty("filePath", filePath);
        }
        return new ToolRequestArguments(obj);
    }

    private static ToolRequestArguments moveArgs(String sourcePath, String targetDir) {
        JsonObject obj = new JsonObject();
        obj.addProperty("sourcePath", sourcePath);
        obj.addProperty("targetDirectory", targetDir);
        return new ToolRequestArguments(obj);
    }

    private static ToolRequestArguments copyArgs(String sourcePath, String targetDir) {
        JsonObject obj = new JsonObject();
        obj.addProperty("sourcePath", sourcePath);
        obj.addProperty("targetDirectory", targetDir);
        return new ToolRequestArguments(obj);
    }

    private static java.io.File tempFile() throws Exception {
        java.io.File f = java.io.File.createTempFile("aicoder-confirm-test-", ".txt");
        f.deleteOnExit();
        return f;
    }

    // ---- SystemNotificationEvent structural tests ----
    @Test
    void systemNotificationEventStoresText() {
        SystemNotificationEvent event = new SystemNotificationEvent("DeleteFile: /src/Foo.java");
        assertEquals("DeleteFile: /src/Foo.java", event.text());
    }

    @Test
    void systemNotificationEventDeletePathFormat() {
        String path = "/src/com/example/Foo.java";
        SystemNotificationEvent event = new SystemNotificationEvent("DeleteFile: " + path);
        assertTrue(event.text().startsWith("DeleteFile: "), "must start with tool name");
        assertTrue(event.text().contains(path), "must contain the file path");
    }

    @Test
    void systemNotificationEventMoveMustContainBothPaths() {
        String src = "/src/Foo.java";
        String tgt = "/target/dir";
        SystemNotificationEvent event = new SystemNotificationEvent("MoveFile: " + src + " → " + tgt);
        assertTrue(event.text().contains(src), "must contain source path");
        assertTrue(event.text().contains(tgt), "must contain target directory");
    }

    @Test
    void systemNotificationEventCopyMustContainBothPaths() {
        String src = "/src/Foo.java";
        String tgt = "/target/dir";
        SystemNotificationEvent event = new SystemNotificationEvent("CopyFile: " + src + " → " + tgt);
        assertTrue(event.text().contains(src), "must contain source path");
        assertTrue(event.text().contains(tgt), "must contain target directory");
    }

    // ---- Failure-path tests: no notification emitted when operation fails ----
    // RefactoringProvider returns "File not found: ..." outside the NetBeans IDE
    // (resolveFileObject returns null without the platform). These tests verify
    // that no SystemNotificationEvent is fired when the operation does not succeed.
    @Test
    void deleteFileFailedOperationFiresNoNotification() {
        StubSession session = new StubSession();
        DeleteFileTool tool = new DeleteFileTool(allowAllServer());

        tool.handle(deleteArgs("/tmp/nonexistent-aicoder-test.txt"), session);

        long notifications = session.captured.stream()
                .filter(e -> e instanceof SystemNotificationEvent).count();
        assertEquals(0, notifications, "failed delete must emit no SystemNotificationEvent");
    }

    @Test
    void moveFileFailedOperationFiresNoNotification() throws Exception {
        StubSession session = new StubSession();
        MoveFileTool tool = new MoveFileTool(allowAllServer());

        tool.handle(moveArgs("/tmp/nonexistent-aicoder-src.txt", "/tmp"), session);

        long notifications = session.captured.stream()
                .filter(e -> e instanceof SystemNotificationEvent).count();
        assertEquals(0, notifications, "failed move must emit no SystemNotificationEvent");
    }

    @Test
    void copyFileFailedOperationFiresNoNotification() throws Exception {
        StubSession session = new StubSession();
        CopyFileTool tool = new CopyFileTool(allowAllServer());

        tool.handle(copyArgs("/tmp/nonexistent-aicoder-src.txt", "/tmp"), session);

        long notifications = session.captured.stream()
                .filter(e -> e instanceof SystemNotificationEvent).count();
        assertEquals(0, notifications, "failed copy must emit no SystemNotificationEvent");
    }

    @Test
    void deleteFileAccessDeniedFiresNoNotification() {
        StubSession nullIdSession = new StubSession() {
            @Override
            public String getId() {
                return null;
            }
        };
        DeleteFileTool tool = new DeleteFileTool(allowAllServer());

        String result = tool.handle(deleteArgs("/tmp/test.txt"), nullIdSession);

        assertTrue(result.contains("Access denied"), "should be access denied: " + result);
        assertTrue(nullIdSession.captured.isEmpty(),
                "access denied must not emit any event");
    }

    @Test
    void deleteFileFiresConfirmEventForExistingPath() throws Exception {
        java.io.File file = tempFile();
        ConfirmStubSession session = new ConfirmStubSession(PermissionDecision.denied(null));
        DeleteFileTool tool = new DeleteFileTool(allowAllServer());

        tool.handle(deleteArgs(file.getAbsolutePath()), session);

        long confirms = session.captured.stream()
                .filter(e -> e instanceof ConfirmEvent).count();
        assertEquals(1, confirms, "tool must fire ConfirmEvent for an existing path");
    }

    @Test
    void deleteFileRejectedByUserDoesNotProceed() throws Exception {
        java.io.File file = tempFile();
        ConfirmStubSession session = new ConfirmStubSession(PermissionDecision.denied(null));
        DeleteFileTool tool = new DeleteFileTool(allowAllServer());

        String result = tool.handle(deleteArgs(file.getAbsolutePath()), session);

        assertTrue(file.exists(), "rejected delete must leave the file on disk");
        assertTrue(result.contains("declined"), "result should indicate user declined: " + result);
        long notifications = session.captured.stream()
                .filter(e -> e instanceof SystemNotificationEvent).count();
        assertEquals(0, notifications, "rejected delete must emit no SystemNotificationEvent");
    }

    @Test
    void moveFileFiresConfirmEventForExistingSource() throws Exception {
        java.io.File file = tempFile();
        ConfirmStubSession session = new ConfirmStubSession(PermissionDecision.denied(null));
        MoveFileTool tool = new MoveFileTool(allowAllServer());

        tool.handle(moveArgs(file.getAbsolutePath(), "/tmp"), session);

        long confirms = session.captured.stream()
                .filter(e -> e instanceof ConfirmEvent).count();
        assertEquals(1, confirms, "tool must fire ConfirmEvent for an existing source");
    }

    @Test
    void copyFileFiresConfirmEventForExistingSource() throws Exception {
        java.io.File file = tempFile();
        ConfirmStubSession session = new ConfirmStubSession(PermissionDecision.denied(null));
        CopyFileTool tool = new CopyFileTool(allowAllServer());

        tool.handle(copyArgs(file.getAbsolutePath(), "/tmp"), session);

        long confirms = session.captured.stream()
                .filter(e -> e instanceof ConfirmEvent).count();
        assertEquals(1, confirms, "tool must fire ConfirmEvent for an existing source");
    }

    @Test
    void deleteFileNonexistentPathFiresNoConfirmEvent() {
        StubSession session = new StubSession();
        DeleteFileTool tool = new DeleteFileTool(allowAllServer());

        tool.handle(deleteArgs("/tmp/nonexistent-aicoder-confirm-test.txt"), session);

        long confirms = session.captured.stream()
                .filter(e -> e instanceof ConfirmEvent).count();
        assertEquals(0, confirms, "nonexistent path must not fire ConfirmEvent");
    }

    @Test
    void deleteFileTimeoutCompletesFutureAndReturnsRetryableMessage() throws Exception {
        java.io.File file = tempFile();
        // Listener captures the event but never completes the future (simulates no user response)
        StubSession session = new StubSession() {
            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return event -> captured.add(event);
            }
        };
        DeleteFileTool tool = new DeleteFileTool(allowAllServer());
        tool.confirmTimeoutMillis = 0; // immediate timeout

        String result = tool.handle(deleteArgs(file.getAbsolutePath()), session);

        assertTrue(result.toLowerCase().contains("timed out"),
                "timeout must return retryable message: " + result);
        assertTrue(file.exists(), "timed-out delete must leave the file on disk");
        ConfirmEvent ce = (ConfirmEvent) session.captured.stream()
                .filter(e -> e instanceof ConfirmEvent)
                .findFirst().orElseThrow(() -> new AssertionError("ConfirmEvent not fired"));
        assertTrue(ce.response().isDone(),
                "future must be completed after timeout so the UI can unblock");
        assertFalse(ce.response().get().allow(),
                "future must resolve to denied so UI posts no accepted message");
    }

    @Test
    void confirmEventForCopyIncludesTargetPath() throws Exception {
        java.io.File file = tempFile();
        ConfirmStubSession session = new ConfirmStubSession(PermissionDecision.denied(null));
        CopyFileTool tool = new CopyFileTool(allowAllServer());

        tool.handle(copyArgs(file.getAbsolutePath(), "/tmp"), session);

        ConfirmEvent ce = (ConfirmEvent) session.captured.stream()
                .filter(e -> e instanceof ConfirmEvent)
                .findFirst().orElseThrow(() -> new AssertionError("ConfirmEvent not fired"));
        assertEquals(file.getAbsolutePath(), ce.filePath(), "filePath must be source");
        assertEquals("/tmp", ce.targetPath(), "targetPath must be target directory for Copy");
    }

    @Test
    void confirmEventForMoveIncludesTargetPath() throws Exception {
        java.io.File file = tempFile();
        ConfirmStubSession session = new ConfirmStubSession(PermissionDecision.denied(null));
        MoveFileTool tool = new MoveFileTool(allowAllServer());

        tool.handle(moveArgs(file.getAbsolutePath(), "/tmp"), session);

        ConfirmEvent ce = (ConfirmEvent) session.captured.stream()
                .filter(e -> e instanceof ConfirmEvent)
                .findFirst().orElseThrow(() -> new AssertionError("ConfirmEvent not fired"));
        assertEquals(file.getAbsolutePath(), ce.filePath(), "filePath must be source");
        assertEquals("/tmp", ce.targetPath(), "targetPath must be target directory for Move");
    }

    @Test
    void confirmEventForDeleteHasNullTargetPath() throws Exception {
        java.io.File file = tempFile();
        ConfirmStubSession session = new ConfirmStubSession(PermissionDecision.denied(null));
        DeleteFileTool tool = new DeleteFileTool(allowAllServer());

        tool.handle(deleteArgs(file.getAbsolutePath()), session);

        ConfirmEvent ce = (ConfirmEvent) session.captured.stream()
                .filter(e -> e instanceof ConfirmEvent)
                .findFirst().orElseThrow(() -> new AssertionError("ConfirmEvent not fired"));
        assertNull(ce.targetPath(), "Delete must have null targetPath");
    }

    private static class StubSession extends AbstractAiSession {

        final List<AiProcessEvent> captured = new ArrayList<>();

        StubSession() {
            super(new AiSession(SESSION_ID, "Test", null, null, null, null,
                    Instant.EPOCH, Instant.EPOCH));
        }

        @Override
        public String getId() {
            return SESSION_ID;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return event -> {
                captured.add(event);
                if (event instanceof PermissionEvent pe) {
                    pe.response().complete(PermissionDecision.denied(null));
                }
            };
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }

    @Test
    void pendingDeleteFileConfirmationDoesNotBlockFileOrRefactorWork() throws Exception {
        java.io.File file = tempFile();
        BlockingConfirmSession session = new BlockingConfirmSession();
        DeleteFileTool tool = new DeleteFileTool(allowAllServer());
        tool.confirmTimeoutMillis = 5_000;
        assertPendingConfirmationDoesNotBlock(session, () -> tool.handle(
                deleteArgs(file.getAbsolutePath()), session));
        assertTrue(file.exists(), "the denied pending delete must not remove the file");
    }

    @Test
    void pendingDeleteDirectoryConfirmationDoesNotBlockFileOrRefactorWork() throws Exception {
        java.io.File directory = java.nio.file.Files.createTempDirectory("aicoder-delete-dir-").toFile();
        BlockingConfirmSession session = new BlockingConfirmSession();
        DeleteDirectoryTool tool = new DeleteDirectoryTool(allowAllServer());
        tool.confirmTimeoutMillis = 5_000;
        assertPendingConfirmationDoesNotBlock(session, () -> tool.handle(
                deleteArgs(directory.getAbsolutePath()), session));
        assertTrue(directory.exists(), "the denied pending directory delete must not remove the directory");
    }

    @Test
    void pendingCopyFileConfirmationDoesNotBlockFileOrRefactorWork() throws Exception {
        java.io.File file = tempFile();
        java.io.File targetDir = java.nio.file.Files.createTempDirectory("aicoder-copy-target-").toFile();
        BlockingConfirmSession session = new BlockingConfirmSession();
        CopyFileTool tool = new CopyFileTool(allowAllServer());
        tool.confirmTimeoutMillis = 5_000;
        assertPendingConfirmationDoesNotBlock(session, () -> {
            try {
                return tool.handle(copyArgs(file.getAbsolutePath(), targetDir.getAbsolutePath()), session);
            }
            catch (kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(new java.io.File(targetDir, file.getName()).exists(),
                "the denied pending copy must not create a copy");
    }

    @Test
    void pendingCopyFileConfirmationThroughRealInvokeDoesNotBlockFileOrRefactorWork() throws Exception {
        java.io.File file = tempFile();
        java.io.File targetDir = java.nio.file.Files.createTempDirectory("aicoder-copy-target-invoke-").toFile();
        BlockingConfirmSession session = new BlockingConfirmSession();
        CopyFileTool tool = new CopyFileTool(allowAllServer());
        tool.confirmTimeoutMillis = 5_000;
        JsonObject invokeArgs = new JsonObject();
        invokeArgs.addProperty("sourcePath", file.getAbsolutePath());
        invokeArgs.addProperty("targetDirectory", targetDir.getAbsolutePath());
        // Through the real dispatcher, not tool.handle() directly: this is what actually consults
        // requiresGlobalMutationLock()/usesOwnFileLocking() to decide whether the confirmation prompt
        // below runs under the exclusive gate or outside it entirely.
        assertPendingConfirmationDoesNotBlock(session, () -> {
            try {
                return McpToolInvoker.invoke(McpToolEnum.COPY_FILE, tool, invokeArgs, session);
            }
            catch (kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(new java.io.File(targetDir, file.getName()).exists(),
                "the denied pending copy must not create a copy");
    }

    @Test
    void pendingDeleteFileConfirmationThroughRealInvokeDoesNotBlockFileOrRefactorWork() throws Exception {
        java.io.File file = tempFile();
        BlockingConfirmSession session = new BlockingConfirmSession();
        DeleteFileTool tool = new DeleteFileTool(allowAllServer());
        tool.confirmTimeoutMillis = 5_000;
        JsonObject invokeArgs = new JsonObject();
        invokeArgs.addProperty("filePath", file.getAbsolutePath());
        assertPendingConfirmationDoesNotBlock(session, () -> {
            try {
                return McpToolInvoker.invoke(McpToolEnum.DELETE_FILE, tool, invokeArgs, session);
            }
            catch (kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(file.exists(), "the denied pending delete must not remove the file");
    }

    private static void assertPendingConfirmationDoesNotBlock(
            BlockingConfirmSession session, java.util.function.Supplier<String> pendingOperation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Future<String> pending = executor.submit(pendingOperation::get);
            assertTrue(session.shown.await(5, TimeUnit.SECONDS), "delete confirmation must be shown");

            Future<String> sameFile = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "delete-same", List.of(session.confirmedPath), () -> "same-file-ran"));
            Future<String> otherFile = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "delete-other", List.of(session.confirmedPath + "-other"), () -> "other-file-ran"));
            Future<String> refactor = executor.submit(() -> McpToolInvoker.withExclusiveMutation(
                    () -> "refactor-ran"));

            assertEquals("same-file-ran", sameFile.get(2, TimeUnit.SECONDS));
            assertEquals("other-file-ran", otherFile.get(2, TimeUnit.SECONDS));
            assertEquals("refactor-ran", refactor.get(2, TimeUnit.SECONDS));

            session.decision.complete(PermissionDecision.denied("test"));
            assertTrue(pending.get(5, TimeUnit.SECONDS).contains("declined"));
        }
        finally {
            session.decision.complete(PermissionDecision.denied("cleanup"));
            executor.shutdownNow();
        }
    }

    // ---- ConfirmEvent tests: tools ask before acting ----
    private static final class BlockingConfirmSession extends StubSession {

        final CountDownLatch shown = new CountDownLatch(1);
        final CompletableFuture<PermissionDecision> decision = new CompletableFuture<>();
        volatile String confirmedPath;

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return event -> {
                captured.add(event);
                if (event instanceof ConfirmEvent confirm) {
                    confirmedPath = confirm.filePath();
                    shown.countDown();
                    decision.whenComplete((value, error) -> confirm.response().complete(
                            error == null && value != null ? value : PermissionDecision.denied("cleanup")));
                }
            };
        }
    }

    private static class ConfirmStubSession extends StubSession {

        private final PermissionDecision resolveWith;

        ConfirmStubSession(PermissionDecision resolveWith) {
            this.resolveWith = resolveWith;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return event -> {
                captured.add(event);
                if (event instanceof PermissionEvent pe) {
                    pe.response().complete(PermissionDecision.denied(null));
                }
                if (event instanceof ConfirmEvent ce) {
                    ce.response().complete(resolveWith);
                }
            };
        }
    }
}
