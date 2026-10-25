package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ReasoningFieldEnumTest {

    @Test
    @Timeout(5)
    void keysAndOrderMatchTheSpec() {
        assertEquals(List.of(
                ReasoningFieldEnum.REASONING_CONTENT,
                ReasoningFieldEnum.REASONING,
                ReasoningFieldEnum.THINKING,
                ReasoningFieldEnum.REASONING_TEXT),
                     List.of(ReasoningFieldEnum.values()));
        assertEquals("reasoning_content", ReasoningFieldEnum.REASONING_CONTENT.key());
        assertEquals("reasoning", ReasoningFieldEnum.REASONING.key());
        assertEquals("thinking", ReasoningFieldEnum.THINKING.key());
        assertEquals("reasoning_text", ReasoningFieldEnum.REASONING_TEXT.key());
    }
}
