package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolSchemasTest {
    private JsonObject toolWithInputSchema() {
        JsonObject tool = new JsonObject();
        JsonObject input = new JsonObject();
        input.addProperty("type", "object");
        input.add("properties", new JsonObject());
        tool.add("inputSchema", input);
        return tool;
    }

    private boolean requiredContains(JsonObject tool, String key) {
        var req = tool.getAsJsonObject("inputSchema").getAsJsonArray("required");
        if (req == null) {
            return false;
        }
        for (var el : req) {
            if (el.getAsString().equals(key)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void applyWithCredentialsInjectsSessionIdAndSecretKey() {
        JsonObject tool = McpToolSchemas.applyCredentialsIfRequested(toolWithInputSchema(), McpInstructionOptions.cli());
        assertTrue(requiredContains(tool, "sessionId"));
        assertTrue(requiredContains(tool, "secretKey"));
    }

    @Test
    void applyWithoutCredentialsLeavesSchemaClean() {
        JsonObject tool = McpToolSchemas.applyCredentialsIfRequested(toolWithInputSchema(), McpInstructionOptions.apiBackend());
        assertFalse(requiredContains(tool, "sessionId"));
        assertFalse(requiredContains(tool, "secretKey"));
        assertFalse(tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("sessionId"));
    }
}
