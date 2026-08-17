package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MutationToolsNotificationTest {

    private static final String SESSION_ID = "test-session";

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

    private static McpHookServer allowAllServer() {
        return new McpHookServer(0);
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
}
