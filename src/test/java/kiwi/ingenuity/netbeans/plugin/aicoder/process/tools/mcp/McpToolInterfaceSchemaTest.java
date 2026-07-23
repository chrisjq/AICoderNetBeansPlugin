package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolInterfaceSchemaTest {

    /**
     * Minimal fake tool whose schema() always returns a fresh object.
     */
    static class FakeTool implements McpToolInterface {

        public McpSectionEnum section() {
            return McpSectionEnum.SYSTEM;
        }

        public String instruction(Set<McpInstructionOptionEnum> options) {
            return "Fake -> does nothing";
        }

        public JsonObject schema(Set<McpInstructionOptionEnum> options) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", "Fake");
            JsonObject input = new JsonObject();
            input.addProperty("type", "object");
            input.add("properties", new JsonObject());
            tool.add("inputSchema", input);
            return McpToolSchemas.applyCredentialsIfRequested(tool, options);
        }

        public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
            return "";
        }
    }

    private boolean hasCredProp(JsonObject tool) {
        return tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("secretKey");
    }

    @Test
    void cliOptionsIncludeCredentials() {
        assertTrue(hasCredProp(new FakeTool().schema(AiTypeEnum.CLAUDE.getMcpOptions())));
    }

    @Test
    void apiBackendOptionsOmitCredentials() {
        assertFalse(hasCredProp(new FakeTool().schema(AiTypeEnum.OLLAMA_LOCAL.getMcpOptions())));
    }
}
