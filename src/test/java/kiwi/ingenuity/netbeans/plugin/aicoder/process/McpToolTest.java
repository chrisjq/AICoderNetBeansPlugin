package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
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
