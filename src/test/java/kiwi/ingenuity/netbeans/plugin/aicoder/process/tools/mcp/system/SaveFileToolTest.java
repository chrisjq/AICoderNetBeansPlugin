package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
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
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
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

    @Test
    void saveFileWithContent_ownSessionConfigDir_writesDirectlyWithNoPermissionEvent() throws Exception {
        // Mirrors the GetFileSizeAndMetaTool regression test: restrict on, zero project
        // dirs — isProjectFileAllowed alone would deny this, so only the own-config-dir
        // bypass can let it through, and it must do so without ever asking for approval.
        String sessionId = "save-file-test-" + UUID.randomUUID();
        McpHookServer server = new McpHookServer(0);
        server.init();
        try {
            server.registerSession(sessionId, CLAUDE, List.of(), true);
            Path configDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionId);
            Path memoryFile = configDir.resolve("memory.md");

            StubSession session = new StubSession(sessionId, PermissionDecision.denied(null));
            SaveFileTool tool = new SaveFileTool(server);

            String result = tool.handle(args(memoryFile.toString(), "remembered fact"), session);

            assertTrue(result.toLowerCase().contains("saved"),
                    "own config dir write must succeed even though the stub session auto-denies: " + result);
            assertTrue(session.captured.isEmpty(),
                    "own config dir write must bypass the diff panel — no PermissionEvent may be fired");
            assertEquals("remembered fact", Files.readString(memoryFile));
        }
        finally {
            server.stop();
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionId);
        }
    }

    @Test
    void saveFileWithoutContent_ownSessionConfigDir_flushesWithNoSystemNotification() throws Exception {
        String sessionId = "save-file-test-" + UUID.randomUUID();
        McpHookServer server = new McpHookServer(0);
        server.init();
        try {
            server.registerSession(sessionId, CLAUDE, List.of(), true);
            Path configDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionId);
            Path memoryFile = Files.createFile(configDir.resolve("memory.md"));
            Files.writeString(memoryFile, "already on disk");

            StubSession session = new StubSession(sessionId, PermissionDecision.denied(null));
            SaveFileTool tool = new SaveFileTool(server);

            String result = tool.handle(args(memoryFile.toString(), null), session);

            assertFalse(result.startsWith("Access denied"), "own config dir flush must not be denied: " + result);
            assertTrue(session.captured.isEmpty(),
                    "own config dir flush must not surface a SystemNotificationEvent either");
        }
        finally {
            server.stop();
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionId);
        }
    }

    @Test
    void schemaRequiresFilePathDespiteOverridingSchemaItself() {
        // SaveFileTool overrides schema() rather than relying on
        // AbstractFileTool's, so a base-class-only fix would miss it.
        SaveFileTool tool = new SaveFileTool(allowAllServer());

        JsonObject schema = tool.schema(Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());

        assertEquals(1, required.size());
        assertEquals(SaveFileParamEnum.FILE_PATH.key(), required.get(0).getAsString());
    }

    private static class StubSession extends AbstractAiSession {

        final List<AiProcessEvent> captured = new ArrayList<>();
        private final String id;
        private final PermissionDecision autoDecision;

        StubSession(PermissionDecision autoDecision) {
            this(SESSION_ID, autoDecision);
        }

        StubSession(String id, PermissionDecision autoDecision) {
            super(new AiSession(id, "Test", null, null, null, null,
                    Instant.EPOCH, Instant.EPOCH));
            this.id = id;
            this.autoDecision = autoDecision;
        }

        @Override
        public String getId() {
            return id;
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
