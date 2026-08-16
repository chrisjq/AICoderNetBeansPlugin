package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ContextEnumsTest {

    @Test
    void pinSlotsAreDeclaredInRenderOrder() {
        assertArrayEquals(
                new PinSlotEnum[]{PinSlotEnum.IDENTITY, PinSlotEnum.BASELINE,
                    PinSlotEnum.INSTRUCTIONS, PinSlotEnum.TOOLS},
                PinSlotEnum.values(),
                "render order is load-bearing: it keeps the server prefix cache warm");
    }

    @Test
    void trimStrategiesAndTriggersExist() {
        assertEquals(4, ContextTrimStrategyEnum.values().length);
        assertEquals(3, ContextTriggerEnum.values().length);
        assertEquals(2, ContextRetentionEnum.values().length);
    }
}
