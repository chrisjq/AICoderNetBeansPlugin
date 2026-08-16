package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class ContextEntryTest {

    @Test
    void jsonRoundTripPreservesIdentityAndPayload() {
        ChatMessage msg = new ChatMessage(ChatRole.ASSISTANT, null,
                List.of(new ChatToolCall("call_0", "GetFileContent", "{\"a\":1}")), null);
        ContextEntry entry = new ContextEntry(12L, 4L, 1753574390000L, msg,
                ContextRetentionEnum.EVICTABLE, 84, null);

        JsonObject json = entry.toJson();
        ContextEntry restored = ContextEntry.fromJson(json);

        assertEquals(12L, restored.sequence());
        assertEquals(4L, restored.groupId());
        assertEquals(1753574390000L, restored.timestamp());
        assertEquals(ContextRetentionEnum.EVICTABLE, restored.retention());
        assertEquals(84, restored.estimatedTokens());
        assertEquals(ChatRole.ASSISTANT, restored.message().role());
        assertNull(restored.message().content());
        assertEquals(1, restored.message().toolCalls().size());
        assertEquals("GetFileContent", restored.message().toolCalls().get(0).name());
        assertEquals("call_0", restored.message().toolCalls().get(0).id());
    }

    @Test
    void toolResultRoundTripsWithItsCallId() {
        ChatMessage msg = new ChatMessage(ChatRole.TOOL, "file contents", List.of(), "call_0");
        ContextEntry entry = new ContextEntry(13L, 4L, 1L, msg,
                ContextRetentionEnum.EVICTABLE, 5, null);

        ContextEntry restored = ContextEntry.fromJson(entry.toJson());

        assertEquals(ChatRole.TOOL, restored.message().role());
        assertEquals("call_0", restored.message().toolCallId());
        assertEquals("file contents", restored.message().content());
    }

    @Test
    void mutableFieldsCanBeUpdatedInPlace() {
        ChatMessage msg = new ChatMessage(ChatRole.SYSTEM, "a", List.of(), null);
        ContextEntry entry = new ContextEntry(1L, 0L, 1L, msg,
                ContextRetentionEnum.PINNED, 1, null);

        entry.setEstimatedTokens(99);
        entry.setCacheId("resp_123");

        assertEquals(99, entry.estimatedTokens());
        assertEquals("resp_123", entry.cacheId());
        assertEquals(1L, entry.sequence(), "identity fields stay final");
    }
}
