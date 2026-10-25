package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaChatResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class OllamaChatResultTest {

    @Test
    void legacyThreeArgConstructorLeavesTokenCountsNull() {
        OllamaChatResult r = new OllamaChatResult("hi", List.of(), "stop");
        assertNull(r.promptTokens(), "endpoints that report no usage must yield null, not zero");
        assertNull(r.completionTokens());
        assertEquals("hi", r.assistantText());
    }

    @Test
    void tokenCountsRoundTripWhenSupplied() {
        OllamaChatResult r = new OllamaChatResult("hi", List.of(), "stop", 1234, 56);
        assertEquals(1234, r.promptTokens());
        assertEquals(56, r.completionTokens());
    }
}
