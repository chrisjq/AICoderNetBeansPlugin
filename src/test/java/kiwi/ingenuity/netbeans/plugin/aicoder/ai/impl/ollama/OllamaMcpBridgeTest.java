package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class OllamaMcpBridgeTest {

    private static final class FakeTool implements McpToolInterface {
        @Override
        public McpSectionEnum section() {
            return McpSectionEnum.SYSTEM;
        }

        @Override
        public String instruction() {
            return "Fake -> echoes auth";
        }

        @Override
        public boolean isMutating() {
            return false;
        }

        @Override
        public JsonObject schema() {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", McpToolEnum.GET_PLUGIN_VERSION.toolName());
            JsonObject inputSchema = new JsonObject();
            inputSchema.addProperty("type", "object");
            inputSchema.add("properties", new JsonObject());
            tool.add("inputSchema", inputSchema);
            return tool;
        }

        @Override
        public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
            return args.str("sessionId") + "|" + args.str("secretKey");
        }
    }

    private static final class FakeSession extends AbstractAiSession {
        private final Map<McpToolEnum, McpToolInterface> handlers;

        FakeSession(AiSession session, Map<McpToolEnum, McpToolInterface> handlers) {
            super(session);
            this.handlers = handlers;
        }

        @Override
        public String getId() {
            return getAiSession().id();
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return event -> {
            };
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return handlers;
        }
    }

    @Test
    void executeToolUsesSessionHandlerAndInjectedCredentials() {
        AiSession session = new AiSession(
                "sid-1", "s", null, AiTypeEnum.OLLAMA_LOCAL, null,
                AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(), Instant.now(), Instant.now());
        FakeSession fakeSession = new FakeSession(session,
                Map.of(McpToolEnum.GET_PLUGIN_VERSION, new FakeTool()));
        OllamaMcpBridge bridge = new OllamaMcpBridge(fakeSession);
        bridge.setSessionCredentials("real-session", "real-secret");
        JsonObject args = new JsonObject();
        args.addProperty("sessionId", "bad-session");
        args.addProperty("secretKey", "bad-secret");

        String result = bridge.invokeTool(McpToolEnum.GET_PLUGIN_VERSION.toolName(), args);

        assertEquals("real-session|real-secret", result);
    }

    @Test
    void unknownToolNameReturnsErrorString() {
        AiSession session = new AiSession(
                "sid-1", "s", null, AiTypeEnum.OLLAMA_LOCAL, null,
                AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(), Instant.now(), Instant.now());
        FakeSession fakeSession = new FakeSession(session, Map.of());
        OllamaMcpBridge bridge = new OllamaMcpBridge(fakeSession);
        bridge.setSessionCredentials("real-session", "real-secret");

        String result = bridge.invokeTool("NoSuchTool", new JsonObject());

        assertEquals("Error: unknown tool: NoSuchTool", result);
    }
}
