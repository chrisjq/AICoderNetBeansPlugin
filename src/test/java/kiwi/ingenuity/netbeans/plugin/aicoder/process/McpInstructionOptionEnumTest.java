package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class McpInstructionOptionEnumTest {
    @Test
    void hasExactlyTheThreeFlags() {
        assertEquals(3, McpInstructionOptionEnum.values().length);
        assertNotNull(McpInstructionOptionEnum.valueOf("HEADER"));
        assertNotNull(McpInstructionOptionEnum.valueOf("TOOL_INSTRUCTION"));
        assertNotNull(McpInstructionOptionEnum.valueOf("CREDENTIALS"));
    }
}
