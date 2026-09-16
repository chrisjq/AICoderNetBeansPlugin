package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ListBuildsToolTest {

    @Test
    void schemaHasNoParametersOfItsOwn() {
        JsonObject schema = new ListBuildsTool().schema(Set.of());
        JsonObject input = schema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());

        assertEquals(McpToolEnum.LIST_BUILDS.toolName(), schema.get(ToolSchemaKeyEnum.NAME.key()).getAsString());
        assertEquals(0, input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key()).size());
        JsonArray required = input.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertTrue(required == null || required.isEmpty());
    }

    @Test
    void reportsTheQueueToTheCallingSession() {
        FakeSession session = new FakeSession(AiSession.create(null, AiTypeEnum.CLAUDE));

        String report = new ListBuildsTool().handle(new ToolRequestArguments(new JsonObject()), session);

        assertTrue(report.startsWith("Build queue at "), report);
        assertTrue(report.contains("Current builds, in order of execution ("), report);
        assertTrue(report.contains("Recent builds, newest first (last 5):"), report);
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
