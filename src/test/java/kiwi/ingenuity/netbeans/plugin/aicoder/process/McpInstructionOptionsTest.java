package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpInstructionOptionsTest {

    @Test
    void cliHasAllThreeIncludingCredentials() {
        var o = AiTypeEnum.CLAUDE.getMcpOptions();
        assertTrue(o.contains(McpInstructionOptionEnum.HEADER));
        assertTrue(o.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION));
        assertTrue(o.contains(McpInstructionOptionEnum.CREDENTIALS));
    }

    @Test
    void apiBackendOmitsCredentials() {
        var o = AiTypeEnum.OLLAMA_LOCAL.getMcpOptions();
        assertTrue(o.contains(McpInstructionOptionEnum.HEADER));
        assertTrue(o.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION));
        assertFalse(o.contains(McpInstructionOptionEnum.CREDENTIALS));
    }

    @Test
    void returnedSetsAreImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> AiTypeEnum.CLAUDE.getMcpOptions().add(McpInstructionOptionEnum.HEADER));
    }
}
