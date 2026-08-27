package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
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
 * AUDIT 3/6 — proves every GetFileContentTool parameter is read and changes the output: filePath selects the file,
 * startLine narrows from that line, endLine narrows to that line (both 1-based inclusive), and out-of-range values
 * clamp instead of failing.
 */
class GetFileContentToolTest {

    private static final String SESSION_ID = "gfc-session";

    private static ToolRequestArguments args(String filePath, Integer startLine, Integer endLine) {
        JsonObject o = new JsonObject();
        if (filePath != null) {
            o.addProperty(GetFileContentParamEnum.FILE_PATH.key(), filePath);
        }
        if (startLine != null) {
            o.addProperty(GetFileContentParamEnum.START_LINE.key(), startLine);
        }
        if (endLine != null) {
            o.addProperty(GetFileContentParamEnum.END_LINE.key(), endLine);
        }
        return new ToolRequestArguments(o);
    }

    private static McpHookServer unrestrictedServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    private static FakeSession session() {
        return new FakeSession(SESSION_ID);
    }

    private static Path threeLineFile(Path dir) throws Exception {
        Path f = dir.resolve("sample.txt");
        Files.writeString(f, "alpha\nbeta\ngamma");
        return f;
    }

    @Test
    void schemaAdvertisesFilePathRequiredPlusStartEndLine() {
        JsonObject schema = new GetFileContentTool(unrestrictedServer()).schema(java.util.Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(props.has(GetFileContentParamEnum.FILE_PATH.key()));
        assertTrue(props.has(GetFileContentParamEnum.START_LINE.key()));
        assertTrue(props.has(GetFileContentParamEnum.END_LINE.key()));
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(1, required.size());
        assertEquals(GetFileContentParamEnum.FILE_PATH.key(), required.get(0).getAsString());
        assertEquals("integer", props.getAsJsonObject(GetFileContentParamEnum.START_LINE.key())
                .get(ToolSchemaKeyEnum.TYPE.key()).getAsString());
    }

    @Test
    void wholeFileReturnedWhenStartAndEndOmitted(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, null), session());

        assertTrue(result.contains("(lines 1–3 of 3"), result);
        assertTrue(result.contains("alpha") && result.contains("beta") && result.contains("gamma"), result);
    }

    @Test
    void startLineNarrowsFromThatLine(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), 2, null), session());

        assertTrue(result.contains("(lines 2–3 of 3"), result);
        assertTrue(result.contains("beta") && result.contains("gamma"), result);
        assertFalse(result.contains("alpha"), result);
    }

    @Test
    void endLineNarrowsToThatLine(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, 2), session());

        assertTrue(result.contains("(lines 1–2 of 3"), result);
        assertTrue(result.contains("alpha") && result.contains("beta"), result);
        assertFalse(result.contains("gamma"), result);
    }

    @Test
    void startAndEndSelectWindowBothInclusive(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), 2, 2), session());

        assertTrue(result.contains("(lines 2–2 of 3"), result);
        assertTrue(result.contains("beta"), result);
        assertFalse(result.contains("alpha") || result.contains("gamma"), result);
    }

    @Test
    void endLineBeyondEofClampsToWholeFile(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, 100), session());

        assertTrue(result.contains("(lines 1–3 of 3"), result);
        assertTrue(result.contains("gamma"), result);
    }

    @Test
    void missingFileReturnsNotFound(@TempDir Path dir) throws Exception {
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());
        String missing = dir.resolve("nope.txt").toString();

        String result = tool.handle(args(missing, null, null), session());

        assertTrue(result.startsWith("File not found: " + missing), result);
    }

    @Test
    void missingFilePathThrows() {
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        assertThrows(McpArgumentException.class, () -> tool.handle(args(null, null, null), session()));
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;

        FakeSession(String id) {
            super(AiSession.create(null, AiTypeEnum.CLAUDE));
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
