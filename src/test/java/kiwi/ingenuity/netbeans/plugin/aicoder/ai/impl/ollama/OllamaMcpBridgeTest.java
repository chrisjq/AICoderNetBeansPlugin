package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OllamaMcpBridgeTest {

    private static final class FakeTool implements McpToolInterface {

        @Override
        public McpSectionEnum section() {
            return McpSectionEnum.SYSTEM;
        }

        @Override
        public String instruction(Set<McpInstructionOptionEnum> options) {
            return "Fake -> echoes auth";
        }

        @Override
        public boolean isMutating() {
            return false;
        }

        @Override
        public JsonObject schema(Set<McpInstructionOptionEnum> options) {
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

    @Test
    void executeToolRefusesAToolTheSessionHasDisabled() {
        AiSession session = new AiSession(
                "sid-2", "s", null, AiTypeEnum.OLLAMA_LOCAL, null,
                AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings(), Instant.now(), Instant.now());
        FakeSession fakeSession = new FakeSession(session,
                                                  Map.of(McpToolEnum.PEER_MESSAGE_SEND, new FakeTool()));
        OllamaMcpBridge bridge = new OllamaMcpBridge(fakeSession);
        bridge.setSessionCredentials("real-session", "real-secret");

        String result = bridge.invokeTool(McpToolEnum.PEER_MESSAGE_SEND.toolName(), new JsonObject());

        assertTrue(result.startsWith("Error: Tool not enabled for this session: "
                + McpToolEnum.PEER_MESSAGE_SEND.toolName() + ". "), result);
    }

    private static String refusalFor(AiSessionSettings settings, McpToolEnum tool) {
        AiSession session = new AiSession(
                "sid-3", "s", null, AiTypeEnum.OLLAMA_LOCAL, null, settings, Instant.now(), Instant.now());
        OllamaMcpBridge bridge = new OllamaMcpBridge(new FakeSession(session, Map.of(tool, new FakeTool())));
        bridge.setSessionCredentials("real-session", "real-secret");
        return bridge.invokeTool(tool.toolName(), new JsonObject());
    }

    @Test
    void aToolWhoseMcpGroupIsSwitchedOffSaysSoAndNamesTheGroup() {
        AiSessionSettings settings = AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings();
        settings.setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)));
        settings.setAllowWebRequests(true);

        String result = refusalFor(settings, McpToolEnum.WEB_REQUEST);

        assertTrue(result.contains("The 'Web' tool group is switched off for this session"), result);
        assertFalse(result.contains("'Allow web requests'"), "the config setting is hidden while the group is off");
    }

    @Test
    void aToolWhoseConfigSettingIsOffUnderAnEnabledMcpGroupNamesTheSetting() {
        AiSessionSettings settings = AiTypeEnum.OLLAMA_LOCAL.createDefaultSettings();
        settings.setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        settings.setAllowWebRequests(false);

        String result = refusalFor(settings, McpToolEnum.WEB_REQUEST);

        assertTrue(result.contains("Web request access is disabled for this session"), result);
        assertTrue(result.contains("'Allow web requests'"), result);
        assertFalse(result.contains("tool group is switched off"), result);
    }
}
