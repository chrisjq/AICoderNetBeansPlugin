package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OLLAMA_LOCAL;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractMcpBridgeTest {

    private static final class EchoBridge extends AbstractMcpBridge {

        public EchoBridge(AiTypeEnum aiType) {
            super(aiType);
        }

        @Override
        protected String executeTool(String toolName, JsonObject argsWithAuth) {
            return toolName + "|"
                    + argsWithAuth.get("sessionId").getAsString() + "|"
                    + argsWithAuth.get("secretKey").getAsString() + "|"
                    + argsWithAuth.get("payload").getAsString();
        }
    }

    private static final class ExplodingBridge extends AbstractMcpBridge {

        public ExplodingBridge(AiTypeEnum aiType) {
            super(aiType);
        }

        @Override
        protected String executeTool(String toolName, JsonObject argsWithAuth) {
            throw new IllegalStateException("boom");
        }
    }

    private static final class FakeTool implements McpToolInterface {

        @Override
        public McpSectionEnum section() {
            return McpSectionEnum.SYSTEM;
        }

        @Override
        public String instruction(Set<McpInstructionOptionEnum> options) {
            return "Fake -> no-op";
        }

        @Override
        public JsonObject schema(Set<McpInstructionOptionEnum> options) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", "FakeTool");
            JsonObject inputSchema = new JsonObject();
            inputSchema.addProperty("type", "object");
            inputSchema.add("properties", new JsonObject());
            inputSchema.add("required", new JsonArray());
            tool.add("inputSchema", inputSchema);
            return McpToolSchemas.applyCredentialsIfRequested(tool, options);
        }

        @Override
        public String handle(ToolRequestArguments args, AbstractAiSession session) {
            return "";
        }
    }

    @Test
    void listToolsForModelDefaultsToApiBackendOptions() {
        EchoBridge bridge = new EchoBridge(OLLAMA_LOCAL);

        JsonArray tools = bridge.listToolsForModel(List.of(new FakeTool()));
        JsonObject schema = tools.get(0).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("inputSchema").getAsJsonObject("properties");

        assertFalse(properties.has("sessionId"));
        assertFalse(properties.has("secretKey"));
    }

    @Test
    void setOptionsCanReEnableCredentialFieldsForCliCallers() {
        EchoBridge bridge = new EchoBridge(CLAUDE);

        JsonArray tools = bridge.listToolsForModel(List.of(new FakeTool()));
        JsonObject schema = tools.get(0).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("inputSchema").getAsJsonObject("properties");

        assertTrue(properties.has("sessionId"));
        assertTrue(properties.has("secretKey"));
    }

    @Test
    void invokeToolOverwritesModelSuppliedCredentials() {
        EchoBridge bridge = new EchoBridge(OLLAMA_LOCAL);
        bridge.setSessionCredentials("real-session", "real-secret");
        JsonObject args = new JsonObject();
        args.addProperty("sessionId", "model-session");
        args.addProperty("secretKey", "model-secret");
        args.addProperty("payload", "value");

        String result = bridge.invokeTool("GetWeather", args);

        assertEquals("GetWeather|real-session|real-secret|value", result);
        assertEquals("model-session", args.get("sessionId").getAsString(), "original args must stay untouched");
        assertEquals("model-secret", args.get("secretKey").getAsString(), "original args must stay untouched");
    }

    @Test
    void invokeToolFailsCleanlyBeforeCredentialsAreSet() {
        EchoBridge bridge = new EchoBridge(OLLAMA_LOCAL);
        assertEquals("Error: session credentials are not set", bridge.invokeTool("GetWeather", new JsonObject()));
    }

    @Test
    void blankToolNameFailsCleanly() {
        EchoBridge bridge = new EchoBridge(OLLAMA_LOCAL);
        bridge.setSessionCredentials("sid", "secret");
        assertEquals("Error: toolName must not be blank", bridge.invokeTool("   ", new JsonObject()));
    }

    @Test
    void runtimeExceptionsAreWrappedAsErrorStrings() {
        ExplodingBridge bridge = new ExplodingBridge(OLLAMA_LOCAL);
        bridge.setSessionCredentials("sid", "secret");
        assertEquals("Error: boom", bridge.invokeTool("Explode", new JsonObject()));
    }

    @Test
    void blankCredentialsAreRejectedImmediately() {
        EchoBridge bridge = new EchoBridge(OLLAMA_LOCAL);
        assertThrows(IllegalArgumentException.class, () -> bridge.setSessionCredentials("", "secret"));
        assertThrows(IllegalArgumentException.class, () -> bridge.setSessionCredentials("sid", " "));
    }
}
