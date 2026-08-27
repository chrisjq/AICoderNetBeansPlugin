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
 * AUDIT 3/6 — proves every CopyFileTool parameter is read and changes the operation: sourcePath selects the file to
 * copy, targetDirectory selects the destination, and newName renames the copy (base name without extension; blank
 * behaves as omitted). Also proves the ConfirmEvent gate stops a denied copy.
 */
class CopyFileToolTest {

    private static final String SESSION_ID = "copy-session";

    private static ToolRequestArguments args(String source, String targetDir, String newName) {
        JsonObject o = new JsonObject();
        if (source != null) {
            o.addProperty(CopyFileParamEnum.SOURCE_PATH.key(), source);
        }
        if (targetDir != null) {
            o.addProperty(CopyFileParamEnum.TARGET_DIRECTORY.key(), targetDir);
        }
        if (newName != null) {
            o.addProperty(CopyFileParamEnum.NEW_NAME.key(), newName);
        }
        return new ToolRequestArguments(o);
    }

    private static McpHookServer unrestrictedServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    @Test
    void schemaRequiresSourceAndTargetNewNameOptional() {
        JsonObject schema = new CopyFileTool(unrestrictedServer()).schema(java.util.Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(props.has(CopyFileParamEnum.SOURCE_PATH.key()));
        assertTrue(props.has(CopyFileParamEnum.TARGET_DIRECTORY.key()));
        assertTrue(props.has(CopyFileParamEnum.NEW_NAME.key()));
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(2, required.size());
        assertTrue(required.toString().contains(CopyFileParamEnum.SOURCE_PATH.key()));
        assertTrue(required.toString().contains(CopyFileParamEnum.TARGET_DIRECTORY.key()));
    }

    @Test
    void newNameRenamesTheCopy(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("orig.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        CopyFileTool tool = new CopyFileTool(unrestrictedServer());

        String result = tool.handle(args(source.toString(), targetDir.toString(), "renamed"),
                new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertTrue(Files.exists(targetDir.resolve("renamed.txt")), "copy must appear as renamed.txt: " + result);
        assertEquals("payload", Files.readString(targetDir.resolve("renamed.txt")));
        assertFalse(Files.exists(targetDir.resolve("orig.txt")), "original name must not be used when newName given");
        assertTrue(result.contains("renamed.txt"), result);
    }

    @Test
    void omittingNewNameKeepsOriginalName(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("orig.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        CopyFileTool tool = new CopyFileTool(unrestrictedServer());

        String result = tool.handle(args(source.toString(), targetDir.toString(), null),
                new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertTrue(Files.exists(targetDir.resolve("orig.txt")), result);
        assertEquals("payload", Files.readString(targetDir.resolve("orig.txt")));
        assertTrue(result.contains("orig.txt"), result);
    }

    @Test
    void blankNewNameTreatedAsOmitted(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("orig.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        CopyFileTool tool = new CopyFileTool(unrestrictedServer());

        String result = tool.handle(args(source.toString(), targetDir.toString(), "   "),
                new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertTrue(Files.exists(targetDir.resolve("orig.txt")), result);
        assertFalse(Files.exists(targetDir.resolve("   ")), "blank name must not create an empty-named copy");
    }

    @Test
    void missingSourceReportsNotFoundWithoutConfirming(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("missing.txt");
        Path targetDir = dir;
        CopyFileTool tool = new CopyFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.allowed());

        String result = tool.handle(args(missing.toString(), targetDir.toString(), null), session);

        assertTrue(result.contains("File not found"), result);
        assertTrue(session.captured.isEmpty(), "no confirm may be asked for a file that does not exist");
    }

    @Test
    void deniedCopyStopsAndLeavesEverythingUntouched(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("orig.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        CopyFileTool tool = new CopyFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.denied("no"));

        String result = tool.handle(args(source.toString(), targetDir.toString(), "renamed"), session);

        assertTrue(result.contains("User declined the copy"), result);
        assertTrue(Files.exists(source), "source must survive a denied copy");
        assertFalse(Files.exists(targetDir.resolve("renamed.txt")), "no copy may appear after a denial");
        assertEquals(1, session.captured.size(), "denial path must fire the Copy ConfirmEvent");
        assertEquals("Copy", ((ConfirmEvent) session.captured.get(0)).toolName());
    }

    @Test
    void missingSourcePathThrows() {
        CopyFileTool tool = new CopyFileTool(unrestrictedServer());

        assertThrows(McpArgumentException.class,
                () -> tool.handle(args(null, "/tmp", null), new StubSession(SESSION_ID, PermissionDecision.allowed())));
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
