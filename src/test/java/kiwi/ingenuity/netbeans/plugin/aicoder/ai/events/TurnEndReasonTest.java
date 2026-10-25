package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TurnEndReasonTest {

    @Test
    @Timeout(5)
    void noArgEventHasNullReason() {
        assertNull(new TurnCompleteEvent().reason());
        assertEquals(TurnEndReason.LENGTH, new TurnCompleteEvent(TurnEndReason.LENGTH).reason());
    }

    @Test
    @Timeout(5)
    void messagesFillTheirParameters() {
        assertEquals("Stopped after 7 tool rounds (the limit). Say 'continue' to go on.",
                     TurnEndReason.STEP_LIMIT.message(7));
        assertEquals("Stopped: the model kept repeating the same tool call (ListBuilds) without new results.",
                     TurnEndReason.REPETITION.message("ListBuilds"));
        assertEquals("Cancelled.", TurnEndReason.CANCELLED.message());
        assertNull(TurnEndReason.ANSWERED.message());
        assertEquals("Stopped after 1200 tool rounds (the limit). Say 'continue' to go on.",
                     TurnEndReason.STEP_LIMIT.message(1200));
        assertEquals("Server said {oops} 'x'", TurnEndReason.SERVER_ERROR.message("Server said {oops} 'x'"));
    }

    @Test
    @Timeout(5)
    void everyReasonExists() {
        assertEquals(10, TurnEndReason.values().length);
    }
}
