package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

class McpInstructionOptionEnumTest {

    @Test
    void hasExactlyTheSevenFlags() {
        assertEquals(7, McpInstructionOptionEnum.values().length);
        assertNotNull(McpInstructionOptionEnum.valueOf("HEADER"));
        assertNotNull(McpInstructionOptionEnum.valueOf("TOOL_INSTRUCTION"));
        assertNotNull(McpInstructionOptionEnum.valueOf("CREDENTIALS"));
        assertNotNull(McpInstructionOptionEnum.valueOf("ONLY_MCP_TOOL_ACCESS"));
        assertNotNull(McpInstructionOptionEnum.valueOf("SOFTEN_TOOL_DIRECTIVES"));
        assertNotNull(McpInstructionOptionEnum.valueOf("TOOL_CALLS_VIA_SCHEMA"));
        assertNotNull(McpInstructionOptionEnum.valueOf("FORCE_MCP_TOOL_USE"));
    }
}
