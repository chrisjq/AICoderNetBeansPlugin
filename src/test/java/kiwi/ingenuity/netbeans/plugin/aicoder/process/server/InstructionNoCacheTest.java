package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionNoCacheTest {
    @Test
    void buildFullInstructionsIsStableAndUncached() {
        var handlers = McpInstructionRegistry.getHandlers(AiTypeEnum.CLAUDE);
        String a = McpInstructionRegistry.buildFullInstructions(AiTypeEnum.CLAUDE, handlers);
        String b = McpInstructionRegistry.buildFullInstructions(AiTypeEnum.CLAUDE, handlers);
        assertEquals(a, b);
        assertTrue(a.contains("## Policy"));
    }
}
