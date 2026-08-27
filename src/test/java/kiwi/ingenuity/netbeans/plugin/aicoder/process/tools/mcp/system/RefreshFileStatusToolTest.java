package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * AUDIT 3/6 — proves RefreshFileStatusTool's optional filePath parameter routes to a DIFFERENT branch than omitting it:
 * with a path it targets that file's project ("File not found: …" for a path that resolves to nothing), without a path
 * it refreshes all open projects ("No open projects to refresh" in the headless test harness). The two replies
 * differing proves the parameter is read rather than silently ignored.
 */
class RefreshFileStatusToolTest {

    private static final String SESSION_ID = "refresh-session";

    private static ToolRequestArguments args(String filePath) {
        JsonObject o = new JsonObject();
        if (filePath != null) {
            o.addProperty(RefreshFileStatusParamEnum.FILE_PATH.key(), filePath);
        }
        return new ToolRequestArguments(o);
    }

    @Test
    void schemaAdvertisesFilePathAsOptional() {
        JsonObject schema = new RefreshFileStatusTool().schema(java.util.Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        assertTrue(schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key())
                .has(RefreshFileStatusParamEnum.FILE_PATH.key()));
        assertFalse(schema.has(ToolSchemaKeyEnum.REQUIRED.key()),
                "filePath must be optional — no required array may be advertised");
    }

    @Test
    void filePathRoutesToTheFileBranchInsteadOfTheAllProjectsBranch() {
        RefreshFileStatusTool tool = new RefreshFileStatusTool();
        String missing = "/tmp/definitely-missing-aicoder-audit-file.txt";
        FakeSession session = new FakeSession(SESSION_ID);

        String withPath = tool.handle(args(missing), session);
        String withoutPath = tool.handle(args(null), session);

        assertEquals("File not found: " + missing, withPath,
                "a filePath naming nothing must hit the file-scoped branch");
        assertNotEquals(withPath, withoutPath,
                "the reply must change with the filePath parameter — it may not be ignored");
        assertFalse(withoutPath.contains(missing),
                "the no-path reply must not contain the path the parameter would have supplied");
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
