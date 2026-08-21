package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SaveFileToolTest {

    private static final String SESSION_ID = "test-session";

    private static ToolRequestArguments args(String filePath, String content) {
        JsonObject obj = new JsonObject();
        if (filePath != null) {
            obj.addProperty("filePath", filePath);
        }
        if (content != null) {
            obj.addProperty("content", content);
        }
        return new ToolRequestArguments(obj);
    }

    private static McpHookServer allowAllServer() {
        return new McpHookServer(0);
    }

    @Test
    void saveFileWithContentFiresPermissionEventWithToolNameWrite() {
        StubSession session = new StubSession(PermissionDecision.denied(null));
        SaveFileTool tool = new SaveFileTool(allowAllServer());

        tool.handle(args("/tmp/test.txt", "hello"), session);

        assertEquals(1, session.captured.size(),
                "content path must fire exactly one PermissionEvent");
        PermissionEvent pe = assertInstanceOf(PermissionEvent.class, session.captured.get(0));
        assertEquals("Write", pe.toolName());
        assertEquals("/tmp/test.txt", pe.filePath());
    }

    @Test
    void saveFileWithContentNoLongerRefusesWhenAutoAcceptOff() {
        StubSession session = new StubSession(PermissionDecision.denied(null));
        SaveFileTool tool = new SaveFileTool(allowAllServer());

        String result = tool.handle(args("/tmp/test.txt", "hello"), session);

        assertFalse(result.contains("Auto-Accept is disabled"),
                "effectiveAutoAccept gate must be removed: " + result);
        assertTrue(result.contains("do not retry"),
                "denial must be reported as a proper rejection: " + result);
    }

    @Test
    void saveFileWithoutFilePathIsRejectedRatherThanSavingTheFocusedEditor() {
        // The no-content path flushes unsaved editor changes to disk. It used to
        // fall back to EditorContextProvider.getCurrentFilePath(), so omitting
        // the path committed whatever the user was part-way through editing.
        StubSession session = new StubSession(PermissionDecision.denied(null));
        SaveFileTool tool = new SaveFileTool(allowAllServer());

        String result = tool.handle(args(null, null), session);

        // "filePath", not "file_path" — that is the actual schema key, and an
        // error naming a key the parser does not accept just makes the caller
        // retry with it. WriteFile and ApplyEdit used to be the exception,
        // taking file_path; they were moved to camelCase so every tool now
        // agrees. Claude's own hook payload still uses file_path, which is why
        // ClaudeHookKeyEnum keeps that spelling separately.
        assertTrue(result.contains("filePath is required"), result);
        assertTrue(session.captured.isEmpty(), "must not act at all without a path");
    }

    @Test
    void saveFileWithBlankFilePathIsAlsoRejected() {
        StubSession session = new StubSession(PermissionDecision.denied(null));
        SaveFileTool tool = new SaveFileTool(allowAllServer());

        assertTrue(tool.handle(args("   ", null), session).contains("filePath is required"));
    }

    @Test
    void saveFileWithoutContentDoesNotFirePermissionEvent() {
        StubSession session = new StubSession(PermissionDecision.denied(null));
        SaveFileTool tool = new SaveFileTool(allowAllServer());

        tool.handle(args("/tmp/nonexistent-aicoder-test.txt", null), session);

        assertTrue(session.captured.isEmpty(),
                "no-content flush path must not fire PermissionEvent");
    }

    private static class StubSession extends AbstractAiSession {

        final List<AiProcessEvent> captured = new ArrayList<>();
        private final PermissionDecision autoDecision;

        StubSession(PermissionDecision autoDecision) {
            super(new AiSession(SESSION_ID, "Test", null, null, null, null,
                    Instant.EPOCH, Instant.EPOCH));
            this.autoDecision = autoDecision;
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
                    pe.response().complete(autoDecision);
                }
            };
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
