package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextRetentionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTrimStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaPinSlotEnum;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ContextEnumsTest {

    @Test
    void pinSlotsAreDeclaredInRenderOrder() {
        assertArrayEquals(
                new OllamaPinSlotEnum[]{OllamaPinSlotEnum.IDENTITY, OllamaPinSlotEnum.BASELINE,
                    OllamaPinSlotEnum.INSTRUCTIONS, OllamaPinSlotEnum.TOOLS},
                OllamaPinSlotEnum.values(),
                "render order is load-bearing: it keeps the server prefix cache warm");
    }

    @Test
    void trimStrategiesAndTriggersExist() {
        assertEquals(4, OllamaContextTrimStrategyEnum.values().length);
        assertEquals(3, OllamaContextTriggerEnum.values().length);
        assertEquals(2, OllamaContextRetentionEnum.values().length);
    }
}
