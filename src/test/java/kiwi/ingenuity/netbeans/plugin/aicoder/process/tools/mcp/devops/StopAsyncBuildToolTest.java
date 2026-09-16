package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class StopAsyncBuildToolTest {

    @Test
    void schemaRequiresBuildId() {
        JsonObject schema = new StopAsyncBuildTool().schema(Set.of());
        JsonObject input = schema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonArray required = input.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());

        assertEquals(McpToolEnum.STOP_ASYNC_BUILD.toolName(), schema.get(ToolSchemaKeyEnum.NAME.key()).getAsString());
        assertEquals(1, required.size());
        assertEquals(McpToolPropertyEnum.BUILD_ID.key(), required.get(0).getAsString());
    }

    @Test
    void aBlankBuildIdIsRefused() {
        String result = new StopAsyncBuildTool().handle(new ToolRequestArguments(new JsonObject()), session());

        assertEquals("Error: buildId is required", result);
    }

    @Test
    void anUnknownBuildIdIsReportedAsNotYours() {
        JsonObject args = new JsonObject();
        args.addProperty(McpToolPropertyEnum.BUILD_ID.key(), "build-does-not-exist");

        String result = new StopAsyncBuildTool().handle(new ToolRequestArguments(args), session());

        assertTrue(result.startsWith("No queued or running async build with id build-does-not-exist belongs to you"),
                   result);
    }

    private static FakeSession session() {
        return new FakeSession(AiSession.create(null, AiTypeEnum.CLAUDE));
    }

    private static final class FakeSession extends AbstractAiSession {

        FakeSession(AiSession session) {
            super(session);
        }

        @Override
        public String getId() {
            return getAiSession().id();
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
