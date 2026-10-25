package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SseLineSplitterTest {

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(5)
    void twoLinesInOneChunkAreDeliveredInOrder() {
        List<String> lines = new ArrayList<>();
        SseLineSplitter splitter = new SseLineSplitter(1000, lines::add);

        assertTrue(splitter.accept(bytes("one\ntwo\n")));

        assertEquals(List.of("one", "two"), lines);
    }

    @Test
    @Timeout(5)
    void aLineSplitAcrossThreeChunksIsDeliveredWhole() {
        List<String> lines = new ArrayList<>();
        SseLineSplitter splitter = new SseLineSplitter(1000, lines::add);

        splitter.accept(bytes("hel"));
        splitter.accept(bytes("lo wo"));
        splitter.accept(bytes("rld\n"));

        assertEquals(List.of("hello world"), lines);
    }

    @Test
    @Timeout(5)
    void crlfLineEndingsLoseTheCarriageReturn() {
        List<String> lines = new ArrayList<>();
        SseLineSplitter splitter = new SseLineSplitter(1000, lines::add);

        splitter.accept(bytes("a\r\nb\r\n"));

        assertEquals(List.of("a", "b"), lines);
    }

    @Test
    @Timeout(5)
    void multiByteCharactersSplitBetweenChunksDecodeWhole() {
        List<String> lines = new ArrayList<>();
        SseLineSplitter splitter = new SseLineSplitter(1000, lines::add);
        byte[] all = "é\n日本\n".getBytes(StandardCharsets.UTF_8);

        splitter.accept(ByteBuffer.wrap(Arrays.copyOfRange(all, 0, 1)));
        splitter.accept(ByteBuffer.wrap(Arrays.copyOfRange(all, 1, 4)));
        splitter.accept(ByteBuffer.wrap(Arrays.copyOfRange(all, 4, 7)));
        splitter.accept(ByteBuffer.wrap(Arrays.copyOfRange(all, 7, all.length)));

        assertEquals(List.of("é", "日本"), lines);
    }

    @Test
    @Timeout(5)
    void emptyLinesAreDeliveredAsEmptyStrings() {
        List<String> lines = new ArrayList<>();
        SseLineSplitter splitter = new SseLineSplitter(1000, lines::add);

        splitter.accept(bytes("\n\na\n"));

        assertEquals(List.of("", "", "a"), lines);
    }

    @Test
    @Timeout(5)
    void aLineOverTheLimitIsRejectedWithoutDeliveringAPartialLine() {
        List<String> lines = new ArrayList<>();
        SseLineSplitter splitter = new SseLineSplitter(1000, lines::add);

        assertFalse(splitter.accept(bytes("x".repeat(2000))));
        assertFalse(splitter.accept(bytes("\n")));
        splitter.finish();

        assertEquals(List.of(), lines);
    }

    @Test
    @Timeout(5)
    void finishFlushesTheLastUnterminatedLine() {
        List<String> lines = new ArrayList<>();
        SseLineSplitter splitter = new SseLineSplitter(1000, lines::add);

        splitter.accept(bytes("tail"));
        assertEquals(List.of(), lines);

        splitter.finish();

        assertEquals(List.of("tail"), lines);
    }
}
