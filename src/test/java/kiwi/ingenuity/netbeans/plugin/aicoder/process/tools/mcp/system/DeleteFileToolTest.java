package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * AUDIT 3/6 — proves DeleteFileTool's filePath parameter drives the whole operation: the file it names is the one
 * deleted, a missing target is reported (with no confirm asked), a denied delete leaves the file untouched, and a blank
 * path is refused rather than guessing the focused editor.
 */
class DeleteFileToolTest {

    private static final String SESSION_ID = "delete-session";

    private static ToolRequestArguments args(String filePath) {
        JsonObject o = new JsonObject();
        if (filePath != null) {
            o.addProperty(DeleteFileParamEnum.FILE_PATH.key(), filePath);
        }
        return new ToolRequestArguments(o);
    }

    private static McpHookServer unrestrictedServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    @Test
    void schemaRequiresFilePath() {
        JsonObject schema = new DeleteFileTool(unrestrictedServer()).schema(java.util.Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        assertTrue(schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key())
                .has(DeleteFileParamEnum.FILE_PATH.key()));
        assertEquals(1, schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key()).size());
        assertEquals(DeleteFileParamEnum.FILE_PATH.key(),
                schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key()).get(0).getAsString());
    }

    @Test
    void deletesExactlyTheNamedFile(@TempDir Path dir) throws Exception {
        Path victim = Files.writeString(dir.resolve("doomed.txt"), "payload");
        Path stayer = Files.writeString(dir.resolve("stayer.txt"), "keep");
        DeleteFileTool tool = new DeleteFileTool(unrestrictedServer());

        String result = tool.handle(args(victim.toString()),
                new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertEquals("File deleted", result);
        assertFalse(Files.exists(victim), "the named file must be deleted");
        assertTrue(Files.exists(stayer), "a sibling file must survive");
    }

    @Test
    void missingFileReportsNotFoundWithoutConfirming(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("missing.txt");
        DeleteFileTool tool = new DeleteFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.allowed());

        String result = tool.handle(args(missing.toString()), session);

        assertTrue(result.contains("File not found"), result);
        assertTrue(session.captured.isEmpty(), "no confirm may be asked for a file that does not exist");
    }

    @Test
    void deniedDeleteLeavesFileInPlace(@TempDir Path dir) throws Exception {
        Path victim = Files.writeString(dir.resolve("doomed.txt"), "payload");
        DeleteFileTool tool = new DeleteFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.denied("no"));

        String result = tool.handle(args(victim.toString()), session);

        assertTrue(result.contains("User declined the delete"), result);
        assertTrue(Files.exists(victim), "file must survive a denied delete");
        assertEquals(1, session.captured.size(), "denial path must fire the Delete ConfirmEvent");
        assertEquals("Delete", ((ConfirmEvent) session.captured.get(0)).toolName());
    }

    @Test
    void blankFilePathIsRefusedWithoutFallback() throws Exception {
        DeleteFileTool tool = new DeleteFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.allowed());

        String result = tool.handle(args("  "), session);

        assertTrue(result.contains("filePath is required"), result);
        assertTrue(session.captured.isEmpty(), "must not act at all without a path");
    }

    private static final class StubSession extends AbstractAiSession {

        final List<AiProcessEvent> captured = new ArrayList<>();
        private final String id;
        private final PermissionDecision autoDecision;

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
                else if (event instanceof ConfirmEvent ce) {
                    ce.response().complete(autoDecision);
                }
            };
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
