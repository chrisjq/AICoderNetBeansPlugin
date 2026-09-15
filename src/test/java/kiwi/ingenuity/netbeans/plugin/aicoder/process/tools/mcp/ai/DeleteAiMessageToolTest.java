package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class DeleteAiMessageToolTest {

    @Test
    void everyRequestedIdDeletedIsASuccess() {
        assertEquals("Deleted 2 message(s).", DeleteAiMessageTool.deleteResultMessage(2, 2));
    }

    @Test
    void idsThatMatchNothingAreReported() {
        // Live v1.4.15: DeleteAiMessage with a wrong ID returned "Deleted 0 message(s)." as a success.
        assertEquals("Error: nothing deleted. 1 ID(s) matched no message — the ID is incorrect or the message has expired"
                + " or was already deleted.", DeleteAiMessageTool.deleteResultMessage(0, 1));
        assertEquals("Deleted 1 of 3 message(s). 2 ID(s) matched no message — the ID is incorrect or the message has"
                + " expired or was already deleted.", DeleteAiMessageTool.deleteResultMessage(1, 3));
    }
}
