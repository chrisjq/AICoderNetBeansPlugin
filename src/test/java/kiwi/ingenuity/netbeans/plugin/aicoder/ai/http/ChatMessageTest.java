package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void accessorsKeepRecordStyleNames() {
        ChatMessage m = new ChatMessage(ChatRole.USER, "hi", List.of(), null);
        assertEquals(ChatRole.USER, m.role());
        assertEquals("hi", m.content());
        assertEquals(List.of(), m.toolCalls());
        assertNull(m.toolCallId());
    }

    @Test
    void contentIsMutable() {
        ChatMessage m = new ChatMessage(ChatRole.SYSTEM, "old", List.of(), null);
        m.setContent("new");
        assertEquals("new", m.content());
    }

    @Test
    void copyIsIndependentOfTheOriginal() {
        ChatMessage original = new ChatMessage(ChatRole.SYSTEM, "old", List.of(), null);
        ChatMessage copy = original.copy();

        original.setContent("mutated after copy");

        assertNotSame(original, copy);
        assertEquals("old", copy.content(),
                "a snapshot handed to ChatRequest must not change under an in-flight request");
    }
}
