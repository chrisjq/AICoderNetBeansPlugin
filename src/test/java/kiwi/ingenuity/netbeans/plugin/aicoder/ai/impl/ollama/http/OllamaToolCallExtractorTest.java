package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaChatResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaExtractedToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaToolCallExtractor;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OllamaToolCallExtractorTest {

    @Test
    void stringArgumentsWithDuplicateKey_capturesDuplicates() {
        String json = "[{\"name\": \"test_tool\", \"arguments\": \"{\\\"key\\\": \\\"a\\\", \\\"key\\\": \\\"b\\\"}\"}]";
        OllamaChatResult result = new OllamaChatResult(json, null, "tool_use");
        Set<String> knownTools = Set.of("test_tool");

        List<OllamaExtractedToolCall> extracted = OllamaToolCallExtractor.extract(result, knownTools);

        assertEquals(1, extracted.size(), "should extract one call");
        OllamaExtractedToolCall call = extracted.get(0);
        assertEquals("test_tool", call.name());
        assertNotNull(call.duplicateCounts(), "duplicates map should not be null");
        assertTrue(call.duplicateCounts().containsKey("key"), "should detect duplicate key");
        assertEquals(2, (int) call.duplicateCounts().get("key"), "key should appear twice");
    }

    @Test
    void stringArgumentsWithNoDuplicates_yieldsEmptyMap() {
        String json = "[{\"name\": \"test_tool\", \"arguments\": \"{\\\"key1\\\": \\\"a\\\", \\\"key2\\\": \\\"b\\\"}\"}]";
        OllamaChatResult result = new OllamaChatResult(json, null, "tool_use");

        List<OllamaExtractedToolCall> extracted = OllamaToolCallExtractor.extract(result, Set.of("test_tool"));

        assertEquals(1, extracted.size(), "should extract one call");
        OllamaExtractedToolCall call = extracted.get(0);
        assertTrue(call.duplicateCounts().isEmpty(), "no duplicates should yield empty map");
    }

    @Test
    void objectArgumentsRetainRawDuplicateCounts() {
        String json = "{\"name\":\"test_tool\",\"arguments\":{\"key\":\"a\",\"key\":\"b\"}}";
        List<OllamaExtractedToolCall> extracted = OllamaToolCallExtractor.extract(
                new OllamaChatResult(json, null, "tool_use"), Set.of("test_tool"));
        assertEquals(Map.of("key", 2), extracted.get(0).duplicateCounts());
    }

    @Test
    void convenienceConstructor_usesEmptyMapForDuplicates() {
        OllamaExtractedToolCall call = new OllamaExtractedToolCall("test_tool", "{\"key\": \"value\"}");

        assertTrue(call.duplicateCounts().isEmpty(), "convenience constructor should use empty map");
        assertEquals("test_tool", call.name());
    }

    @Test
    void compactConstructor_normalizesNullDuplicatesToEmptyMap() {
        OllamaExtractedToolCall call = new OllamaExtractedToolCall("test_tool", "{}", null);

        assertTrue(call.duplicateCounts().isEmpty(), "null duplicates should normalize to empty map");
    }
}
