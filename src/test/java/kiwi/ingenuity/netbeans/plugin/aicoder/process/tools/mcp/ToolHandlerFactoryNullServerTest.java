package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import java.util.EnumSet;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Guards construction of the full MCP tool registry with a null server (the path used by documentation generation) and
 * the well-formedness of every credential-free schema.
 */
class ToolHandlerFactoryNullServerTest {

    @Test
    void constructsEveryHandlerWithNullServer() {
        Map<McpToolEnum, McpToolInterface> handlers = ToolHandlerFactory.getToolHandlers(null);
        assertFalse(handlers.isEmpty(), "tool registry must not be empty");
        for (Map.Entry<McpToolEnum, McpToolInterface> entry : handlers.entrySet()) {
            assertNotNull(entry.getValue(), entry.getKey() + " maps to a null handler");
        }
    }

    @Test
    void everyHandlerBuildsAWellFormedSchemaWithoutCredentials() {
        Map<McpToolEnum, McpToolInterface> handlers = ToolHandlerFactory.getToolHandlers(null);
        EnumSet<McpInstructionOptionEnum> options = EnumSet.of(McpInstructionOptionEnum.TOOL_INSTRUCTION);
        for (Map.Entry<McpToolEnum, McpToolInterface> entry : handlers.entrySet()) {
            McpToolEnum tool = entry.getKey();
            JsonObject schema = entry.getValue().schema(options);
            assertTrue(schema.has(ToolSchemaKeyEnum.INPUT_SCHEMA.key()), tool.toolName() + " schema has no inputSchema");
            JsonObject inputSchema = schema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
            assertTrue(inputSchema.has(ToolSchemaKeyEnum.PROPERTIES.key())
                    && inputSchema.get(ToolSchemaKeyEnum.PROPERTIES.key()).isJsonObject(),
                    tool.toolName() + " inputSchema has no properties object");
        }
    }
}
