package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
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
 * AUDIT 3/6 — proves MoveFileTool honours both parameters: sourcePath selects the file to move and targetDirectory the
 * destination (content lands in the target, source is gone). Also proves the ConfirmEvent gate and the missing-argument
 * errors.
 */
class MoveFileToolTest {

    private static final String SESSION_ID = "move-session";

    private static ToolRequestArguments args(String source, String targetDir) {
        JsonObject o = new JsonObject();
        if (source != null) {
            o.addProperty(MoveFileParamEnum.SOURCE_PATH.key(), source);
        }
        if (targetDir != null) {
            o.addProperty(MoveFileParamEnum.TARGET_DIRECTORY.key(), targetDir);
        }
        return new ToolRequestArguments(o);
    }

    private static McpHookServer unrestrictedServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    @Test
    void schemaRequiresSourceAndTarget() {
        JsonObject schema = new MoveFileTool(unrestrictedServer()).schema(java.util.Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(props.has(MoveFileParamEnum.SOURCE_PATH.key()));
        assertTrue(props.has(MoveFileParamEnum.TARGET_DIRECTORY.key()));
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(2, required.size());
        assertTrue(required.toString().contains(MoveFileParamEnum.SOURCE_PATH.key()));
        assertTrue(required.toString().contains(MoveFileParamEnum.TARGET_DIRECTORY.key()));
    }

    @Test
    void movesFileIntoTargetDirectory(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());

        String result = tool.handle(args(source.toString(), targetDir.toString()),
                new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertEquals("File moved", result);
        assertFalse(Files.exists(source), "source must be gone after a move");
        assertTrue(Files.exists(targetDir.resolve("moved.txt")), "file must appear in the target directory");
        assertEquals("payload", Files.readString(targetDir.resolve("moved.txt")));
    }

    @Test
    void missingSourceReportsNotFoundWithoutConfirming(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("missing.txt");
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.allowed());

        String result = tool.handle(args(missing.toString(), dir.toString()), session);

        assertTrue(result.contains("File not found"), result);
        assertTrue(session.captured.isEmpty(), "no confirm may be asked for a file that does not exist");
    }

    @Test
    void missingTargetDirectoryIsReported(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());

        String result = tool.handle(args(source.toString(), dir.resolve("no-such-dir").toString()),
                new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertTrue(result.contains("Target directory not found"), result);
        assertTrue(Files.exists(source), "source must survive a failed move");
    }

    @Test
    void deniedMoveStopsAndLeavesFileInPlace(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.denied("no"));

        String result = tool.handle(args(source.toString(), targetDir.toString()), session);

        assertTrue(result.contains("User declined the move"), result);
        assertTrue(Files.exists(source), "file must survive a denied move");
        assertFalse(Files.exists(targetDir.resolve("moved.txt")), "nothing may appear in the target after a denial");
        assertEquals(1, session.captured.size(), "denial path must fire the Move ConfirmEvent");
        assertEquals("Move", ((ConfirmEvent) session.captured.get(0)).toolName());
    }

    @Test
    void missingSourcePathThrows() {
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());

        assertThrows(McpArgumentException.class,
                () -> tool.handle(args(null, "/tmp"), new StubSession(SESSION_ID, PermissionDecision.allowed())));
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
