package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ChunkAssemblerTest {

    @Test
    @Timeout(5)
    void proseWithStopEndsCleanlyAndEmitsDeltas() {
        ChunkAssembler assembler = new ChunkAssembler();
        List<String> deltas = new ArrayList<>();
        assembler.onTextDelta(deltas::add);

        assembler.onSseLine("");
        assembler.onSseLine(": keep-alive");
        assembler.onSseLine("event: message");
        assembler.onSseLine(data("{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"));
        assembler.onSseLine(data("{\"choices\":[{\"delta\":{\"content\":\" world\"},\"finish_reason\":\"stop\"}]}"));

        AssembledReply reply = assembler.result();
        assertEquals("Hello world", reply.text());
        assertEquals("stop", reply.finishReason());
        assertTrue(reply.endedCleanly());
        assertTrue(reply.sawOutput());
        assertEquals(List.of(), reply.toolCalls());
        assertEquals(List.of("Hello", " world"), deltas);
    }

    @Test
    @Timeout(5)
    void doneWithoutFinishReasonStillEndsCleanly() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.onSseLine(data("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"));
        assembler.onSseLine("data: [DONE]");

        AssembledReply reply = assembler.result();
        assertEquals("Hi", reply.text());
        assertNull(reply.finishReason());
        assertTrue(reply.endedCleanly());
        assertTrue(reply.sawOutput());
    }

    @Test
    @Timeout(5)
    void eofWithNeitherDoneNorFinishReasonIsNotClean() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.onSseLine(data("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"));

        AssembledReply reply = assembler.result();
        assertEquals("Hi", reply.text());
        assertNull(reply.finishReason());
        assertFalse(reply.endedCleanly());
        assertTrue(reply.sawOutput());
    }

    @Test
    @Timeout(5)
    void interleavedToolCallFragmentsMergeByIndex() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.onSseLine(data(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"one\",\"arguments\":\"{\"}}]}}]}"));
        assembler.onSseLine(data(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"two\",\"arguments\":\"[\"}}]}}]}"));
        assembler.onSseLine(data(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]}}]}"));
        assembler.onSseLine(data(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"]\"}}]}}]}"));
        assembler.onSseLine(data("{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}"));

        AssembledReply reply = assembler.result();
        assertEquals(List.of(new ChatToolCall("a", "one", "{}"), new ChatToolCall("b", "two", "[]")),
                     reply.toolCalls());
        assertEquals("tool_calls", reply.finishReason());
        assertTrue(reply.endedCleanly());
        assertTrue(reply.sawOutput());
        assertEquals("", reply.text());
    }

    @Test
    @Timeout(5)
    void usageInTheLastChunkIsKept() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.onSseLine(data("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}"));
        assembler.onSseLine(data("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}"));
        assembler.onSseLine("data: [DONE]");

        AssembledReply reply = assembler.result();
        assertEquals("x", reply.text());
        assertEquals(10, reply.promptTokens());
        assertEquals(3, reply.completionTokens());
        assertTrue(reply.endedCleanly());
    }

    @Test
    @Timeout(5)
    void nonStreamingJsonWithText() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.fromNonStreaming(
                "{\"choices\":[{\"message\":{\"content\":\"Done\"},\"finish_reason\":\"stop\"}]}");

        AssembledReply reply = assembler.result();
        assertEquals("Done", reply.text());
        assertEquals("stop", reply.finishReason());
        assertTrue(reply.endedCleanly());
        assertTrue(reply.sawOutput());
        assertEquals(List.of(), reply.toolCalls());
    }

    @Test
    @Timeout(5)
    void nonStreamingJsonWithToolCalls() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.fromNonStreaming(
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"fn\",\"arguments\":\"{\\\"k\\\":1}\"}}]},\"finish_reason\":\"tool_calls\"}]}");

        AssembledReply reply = assembler.result();
        assertEquals(List.of(new ChatToolCall("c1", "fn", "{\"k\":1}")), reply.toolCalls());
        assertEquals("tool_calls", reply.finishReason());
        assertTrue(reply.endedCleanly());
        assertTrue(reply.sawOutput());
        assertEquals("", reply.text());
    }

    @Test
    @Timeout(5)
    void malformedJsonLineIsIgnoredAndLoggedOnce() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.onSseLine("data: not-json");
        assembler.onSseLine(data("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        assembler.onSseLine("data: still-bad");
        assembler.onSseLine("data: [DONE]");

        AssembledReply reply = assembler.result();
        assertEquals("ok", reply.text());
        assertTrue(reply.endedCleanly());
        assertTrue(assembler.loggedMalformed);
    }

    @Test
    @Timeout(5)
    void eachReasoningFieldIsCollected() {
        for (ReasoningFieldEnum field : ReasoningFieldEnum.values()) {
            ChunkAssembler assembler = new ChunkAssembler();
            assembler.onSseLine(data("{\"choices\":[{\"delta\":{\"" + field.key() + "\":\"r\"}}]}"));
            assertEquals("r", assembler.result().reasoning(), field.name());
        }
    }

    @Test
    @Timeout(5)
    void firstNonEmptyReasoningFieldInEnumOrderWins() {
        ChunkAssembler bothSet = new ChunkAssembler();
        bothSet.onSseLine(data(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"first\",\"reasoning\":\"second\"}}]}"));
        assertEquals("first", bothSet.result().reasoning());

        ChunkAssembler emptyThenNext = new ChunkAssembler();
        emptyThenNext.onSseLine(data(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"\",\"reasoning\":\"keep\",\"thinking\":\"skip\"}}]}"));
        assertEquals("keep", emptyThenNext.result().reasoning());
    }

    @Test
    @Timeout(5)
    void nonStreamingMessageReasoningIsCollected() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.fromNonStreaming(
                "{\"choices\":[{\"message\":{\"content\":\"ans\",\"thinking\":\"thought\"},\"finish_reason\":\"stop\"}]}");

        AssembledReply reply = assembler.result();
        assertEquals("ans", reply.text());
        assertEquals("thought", reply.reasoning());
    }

    private static String data(String json) {
        return "data: " + json;
    }
}
