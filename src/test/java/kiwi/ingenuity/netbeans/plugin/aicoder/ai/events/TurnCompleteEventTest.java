package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TurnCompleteEventTest {

    @Test
    @Timeout(5)
    void noArgConstructorLeavesReasonAndMessageUnset() {
        TurnCompleteEvent event = new TurnCompleteEvent();

        assertNull(event.reason());
        assertNull(event.message());
    }

    @Test
    @Timeout(5)
    void reasonConstructorLeavesTheMessageUnset() {
        TurnCompleteEvent event = new TurnCompleteEvent(TurnEndReason.ANSWERED);

        assertEquals(TurnEndReason.ANSWERED, event.reason());
        assertNull(event.message());
    }

    @Test
    @Timeout(5)
    void reasonAndMessageConstructorKeepsBoth() {
        TurnCompleteEvent event = new TurnCompleteEvent(TurnEndReason.SERVER_ERROR, "The server rejected the API key.");

        assertEquals(TurnEndReason.SERVER_ERROR, event.reason());
        assertEquals("The server rejected the API key.", event.message());
    }
}
