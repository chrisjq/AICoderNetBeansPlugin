package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Streamed reasoning fields reach the reasoning consumer as they arrive; the content consumer never sees them.
 */
class ChunkAssemblerReasoningDeltaTest {

    @Test
    @Timeout(5)
    void reasoningFieldDeltasStreamSeparatelyFromTheContent() {
        ChunkAssembler assembler = new ChunkAssembler();
        List<String> text = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        assembler.onTextDelta(text::add);
        assembler.onReasoningDelta(reasoning::add);

        assembler.onSseLine("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"First \"}}]}");
        assembler.onSseLine("data: {\"choices\":[{\"delta\":{\"reasoning\":\"then\"}}]}");
        assembler.onSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"Answer.\"}}]}");
        assembler.onSseLine("data: [DONE]");

        assertEquals(List.of("First ", "then"), reasoning);
        assertEquals(List.of("Answer."), text);
        assertEquals("First then", assembler.result().reasoning(), "the reply still carries the whole reasoning");
        assertEquals("Answer.", assembler.result().text());
    }

    @Test
    @Timeout(5)
    void onlyTheFirstNonEmptyFieldOfAChunkIsStreamed() {
        ChunkAssembler assembler = new ChunkAssembler();
        List<String> reasoning = new ArrayList<>();
        assembler.onReasoningDelta(reasoning::add);

        assembler.onSseLine("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"a\",\"reasoning\":\"a\"}}]}");

        assertEquals(List.of("a"), reasoning);
    }

    @Test
    @Timeout(5)
    void aNonStreamingBodyDoesNotCallTheConsumer() {
        ChunkAssembler assembler = new ChunkAssembler();
        List<String> reasoning = new ArrayList<>();
        assembler.onReasoningDelta(reasoning::add);

        assembler.fromNonStreaming("{\"choices\":[{\"message\":{\"content\":\"Hi\",\"reasoning_content\":\"why\"},"
                + "\"finish_reason\":\"stop\"}]}");

        assertTrue(reasoning.isEmpty());
        assertEquals("why", assembler.result().reasoning());
    }

    @Test
    @Timeout(5)
    void noConsumerIsFine() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.onReasoningDelta(null);

        assembler.onSseLine("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"x\"}}]}");

        assertEquals("x", assembler.result().reasoning());
    }
}
