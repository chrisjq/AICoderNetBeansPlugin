package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolTest {

    @Test
    void toolNameReturnsBareName() {
        assertEquals("FindUsages", McpToolEnum.FIND_USAGES.toolName());
    }

    @Test
    void allMcpNamesContainsAllTools() {
        String all = McpToolEnum.allMcpNames();
        for (McpToolEnum t : McpToolEnum.values()) {
            String expected = "mcp__" + StringConst.PLUGIN_ID + "__" + t.toolName();
            assertTrue(all.contains(expected), "allMcpNames() missing: " + expected);
        }
    }

    @Test
    void allEnumValuesHaveHandlers() {
        Map<McpToolEnum, McpToolInterface> handlers = ClaudeToolHandlerFactory.build(() -> null, null);
        for (McpToolEnum t : McpToolEnum.values()) {
            assertTrue(handlers.containsKey(t), "No handler registered for McpToolEnum." + t.name());
        }
    }

    @Test
    void everyRegisteredSchemaDeclaresPropertiesForItsRequiredKeys() {
        Map<McpToolEnum, McpToolInterface> handlers = ClaudeToolHandlerFactory.build(() -> null, null);
        for (McpToolEnum tool : McpToolEnum.values()) {
            JsonObject input = handlers.get(tool).schema(Set.of())
                    .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
            assertTrue(input != null, tool.name() + " has no inputSchema");
            JsonObject properties = input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
            assertTrue(properties != null, tool.name() + " has no properties");
            if (input.has(ToolSchemaKeyEnum.REQUIRED.key())) {
                for (var required : input.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key())) {
                    assertTrue(properties.has(required.getAsString()),
                               tool.name() + " requires undeclared " + required.getAsString());
                }
            }
        }
    }

    @Test
    void ofFindsExistingTool() {
        assertEquals(McpToolEnum.GET_DIAGNOSTICS, McpToolEnum.of("GetDiagnostics"));
    }

    @Test
    void ofReturnsNullForUnknown() {
        assertNull(McpToolEnum.of("NoSuchTool"));
    }

    @Test
    void ofReturnsNullForNullInput() {
        assertNull(McpToolEnum.of(null));
    }
}
