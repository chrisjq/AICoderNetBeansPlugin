package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context;

import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextEntry;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextRetentionEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class OllamaContextEntryTest {

    @Test
    void jsonRoundTripPreservesIdentityAndPayload() {
        ChatMessage msg = new ChatMessage(ChatRole.ASSISTANT, null,
                                          List.of(new ChatToolCall("call_0", "GetFileContent", "{\"a\":1}")), null);
        OllamaContextEntry entry = new OllamaContextEntry(12L, 4L, 1753574390000L, msg,
                                                          OllamaContextRetentionEnum.EVICTABLE, 84, null);

        JsonObject json = entry.toJson();
        OllamaContextEntry restored = OllamaContextEntry.fromJson(json);

        assertEquals(12L, restored.sequence());
        assertEquals(4L, restored.groupId());
        assertEquals(1753574390000L, restored.timestamp());
        assertEquals(OllamaContextRetentionEnum.EVICTABLE, restored.retention());
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
        OllamaContextEntry entry = new OllamaContextEntry(13L, 4L, 1L, msg,
                                                          OllamaContextRetentionEnum.EVICTABLE, 5, null);

        OllamaContextEntry restored = OllamaContextEntry.fromJson(entry.toJson());

        assertEquals(ChatRole.TOOL, restored.message().role());
        assertEquals("call_0", restored.message().toolCallId());
        assertEquals("file contents", restored.message().content());
    }

    @Test
    void mutableFieldsCanBeUpdatedInPlace() {
        ChatMessage msg = new ChatMessage(ChatRole.SYSTEM, "a", List.of(), null);
        OllamaContextEntry entry = new OllamaContextEntry(1L, 0L, 1L, msg,
                                                          OllamaContextRetentionEnum.PINNED, 1, null);

        entry.setEstimatedTokens(99);
        entry.setCacheId("resp_123");

        assertEquals(99, entry.estimatedTokens());
        assertEquals("resp_123", entry.cacheId());
        assertEquals(1L, entry.sequence(), "identity fields stay final");
    }
}
