package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallExtractorTest {

    @Test
    void structuredToolCallsAreMappedDirectly() {
        ChatResult result = new ChatResult(
                "assistant raw content",
                List.of(new ChatToolCall("call_1", "get_weather", "{\"city\":\"Wellington, NZ\"}")),
                "tool_calls");

        List<ExtractedToolCall> calls = ToolCallExtractor.extract(result, Set.of());

        assertEquals(1, calls.size());
        assertEquals("get_weather", calls.get(0).name());
        assertEquals("{\"city\":\"Wellington, NZ\"}", calls.get(0).argumentsJson());
    }

    @Test
    void emptyStructuredToolCallListReturnsEmpty() {
        ChatResult result = new ChatResult("final answer", List.of(), "stop");
        assertTrue(ToolCallExtractor.extract(result, Set.of("get_weather")).isEmpty());
    }

    @Test
    void contentFallbackUsesKnownToolNameGuard() {
        ChatResult result = new ChatResult(
                "{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Wellington, NZ\"}}",
                List.of(),
                "stop");

        List<ExtractedToolCall> calls = ToolCallExtractor.extract(result, Set.of("get_weather"));

        assertEquals(1, calls.size());
        assertEquals("get_weather", calls.get(0).name());
        assertEquals("{\"city\":\"Wellington, NZ\"}", calls.get(0).argumentsJson());
    }

    @Test
    void contentFallbackDoesNotFireForUnknownToolNames() {
        ChatResult result = new ChatResult(
                "{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Wellington, NZ\"}}",
                List.of(),
                "stop");

        assertTrue(ToolCallExtractor.extract(result, Set.of()).isEmpty());
    }

    @Test
    void contentFallbackSupportsArrayOfToolObjects() {
        ChatResult result = new ChatResult(
                "["
                + "{\"name\":\"first_tool\",\"arguments\":{\"value\":1}},"
                + "{\"name\":\"second_tool\",\"arguments\":{\"value\":2}}"
                + "]",
                List.of(),
                "stop");

        List<ExtractedToolCall> calls = ToolCallExtractor.extract(result, Set.of("first_tool", "second_tool"));

        assertEquals(2, calls.size());
        assertEquals("first_tool", calls.get(0).name());
        assertEquals("{\"value\":1}", calls.get(0).argumentsJson());
        assertEquals("second_tool", calls.get(1).name());
        assertEquals("{\"value\":2}", calls.get(1).argumentsJson());
    }

    @Test
    void ordinaryJsonAnswerDoesNotMisfire() {
        ChatResult result = new ChatResult("{\"answer\":42}", List.of(), "stop");
        assertTrue(ToolCallExtractor.extract(result, Set.of("get_weather")).isEmpty());
    }

    @Test
    void malformedJsonReturnsEmptyWithoutThrowing() {
        ChatResult result = new ChatResult("{\"name\":\"broken\"", List.of(), "stop");
        assertTrue(ToolCallExtractor.extract(result, Set.of("broken")).isEmpty());
    }

    @Test
    void contentFallbackAcceptsStringifiedArguments() {
        ChatResult result = new ChatResult(
                "{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Wellington, NZ\\\"}\"}",
                List.of(),
                "stop");

        List<ExtractedToolCall> calls = ToolCallExtractor.extract(result, Set.of("get_weather"));

        assertEquals(1, calls.size());
        assertEquals("get_weather", calls.get(0).name());
        assertEquals("{\"city\":\"Wellington, NZ\"}", calls.get(0).argumentsJson());
    }

    /**
     * Verbatim shape emitted by qwen2.5-coder:14b — a pretty-printed call
     * inside a ```json fence. Before fences were unwrapped this parsed as
     * malformed, so the block was shown to the user instead of being invoked.
     */
    @Test
    void contentFallbackUnwrapsJsonCodeFence() {
        ChatResult result = new ChatResult(
                "```json\n{\n  \"name\": \"ListAiSessions\",\n  \"arguments\": {}\n}\n```",
                List.of(),
                "stop");

        List<ExtractedToolCall> calls = ToolCallExtractor.extract(result, Set.of("ListAiSessions"));

        assertEquals(1, calls.size());
        assertEquals("ListAiSessions", calls.get(0).name());
        assertEquals("{}", calls.get(0).argumentsJson());
    }

    @Test
    void contentFallbackUnwrapsUnlabelledCodeFence() {
        ChatResult result = new ChatResult(
                "```\n{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Wellington, NZ\"}}\n```",
                List.of(),
                "stop");

        List<ExtractedToolCall> calls = ToolCallExtractor.extract(result, Set.of("get_weather"));

        assertEquals(1, calls.size());
        assertEquals("get_weather", calls.get(0).name());
        assertEquals("{\"city\":\"Wellington, NZ\"}", calls.get(0).argumentsJson());
    }

    @Test
    void fencedOrdinaryJsonAnswerStillDoesNotMisfire() {
        ChatResult result = new ChatResult("```json\n{\"answer\":42}\n```", List.of(), "stop");
        assertTrue(ToolCallExtractor.extract(result, Set.of("get_weather")).isEmpty());
    }

    @Test
    void contentFallbackTreatsAbsentArgumentsAsEmpty() {
        ChatResult result = new ChatResult(
                "{\"name\":\"get_weather\"}",
                List.of(),
                "stop");

        List<ExtractedToolCall> calls = ToolCallExtractor.extract(result, Set.of("get_weather"));

        assertEquals(1, calls.size());
        assertEquals("get_weather", calls.get(0).name());
        assertEquals("{}", calls.get(0).argumentsJson());
    }
}
