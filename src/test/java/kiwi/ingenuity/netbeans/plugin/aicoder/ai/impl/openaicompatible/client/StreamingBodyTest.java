package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class StreamingBodyTest {

    private static final class FakeSubscription implements Flow.Subscription {

        final AtomicLong requested = new AtomicLong();
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void request(long n) {
            requested.addAndGet(n);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static StreamingBody.Outcome outcome(StreamingBody body) throws Exception {
        return body.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(10)
    void sseLinesAreDeliveredAndRequestedOneChunkAtATime() throws Exception {
        List<String> lines = new ArrayList<>();
        AtomicInteger chunks = new AtomicInteger();
        StreamingBody body = new StreamingBody(true, 1000, 100_000, lines::add, chunks::incrementAndGet);
        FakeSubscription subscription = new FakeSubscription();
        body.onSubscribe(subscription);
        assertEquals(1, subscription.requested.get());

        body.onNext(List.of(bytes("data: a\n\ndata: b\r\nda")));
        body.onNext(List.of(bytes("ta: c")));
        body.onComplete();

        assertEquals(List.of("data: a", "", "data: b", "data: c"), lines);
        StreamingBody.Outcome outcome = outcome(body);
        assertEquals(StreamingBody.End.COMPLETE, outcome.end());
        assertNull(outcome.nonStreamingBody());
        assertEquals(3, subscription.requested.get());
        assertEquals(2, chunks.get());
        assertFalse(subscription.cancelled.get());
    }

    @Test
    @Timeout(10)
    void nonStreamingBodyIsAccumulated() throws Exception {
        StreamingBody body = new StreamingBody(false, 1000, 100_000, line -> {
                                           throw new AssertionError("no SSE lines expected");
                                       }, () -> {
                                       });
        body.onSubscribe(new FakeSubscription());
        body.onNext(List.of(bytes("{\"a\":"), bytes("1,\"b\":\"é")));
        body.onNext(List.of(bytes("\"}")));
        body.onComplete();

        StreamingBody.Outcome outcome = outcome(body);
        assertEquals(StreamingBody.End.COMPLETE, outcome.end());
        assertEquals("{\"a\":1,\"b\":\"é\"}", outcome.nonStreamingBody());
    }

    @Test
    @Timeout(20)
    void aTenMegabyteSseLineEndsWithSizeLimitAndTheBufferStaysBounded() throws Exception {
        long lineCap = 1L << 20;
        int chunkSize = 64 * 1024;
        List<String> lines = new ArrayList<>();
        StreamingBody body = new StreamingBody(true, lineCap, 100L << 20, lines::add, () -> {
                                       });
        FakeSubscription subscription = new FakeSubscription();
        body.onSubscribe(subscription);

        byte[] chunk = new byte[chunkSize];
        Arrays.fill(chunk, (byte) 'x');
        int fed = 0;
        for (int i = 0; i < (10 << 20) / chunkSize; i++) {
            body.onNext(List.of(ByteBuffer.wrap(chunk.clone())));
            fed++;
        }

        StreamingBody.Outcome outcome = outcome(body);
        assertEquals(StreamingBody.End.SIZE_LIMIT, outcome.end());
        assertTrue(subscription.cancelled.get());
        assertTrue(lines.isEmpty());
        assertTrue(body.peakBufferedBytes() <= lineCap + chunkSize,
                   "buffer peaked at " + body.peakBufferedBytes());
        assertTrue(fed > 16);
    }

    @Test
    @Timeout(20)
    void aLargeNonStreamingBodyUnderTheTotalCapCompletes() throws Exception {
        int chunkSize = 64 * 1024;
        int chunkCount = 80;
        StreamingBody body = new StreamingBody(false, 1000, 16L << 20, line -> {
                                       }, () -> {
                                       });
        body.onSubscribe(new FakeSubscription());
        byte[] chunk = new byte[chunkSize];
        Arrays.fill(chunk, (byte) 'y');
        for (int i = 0; i < chunkCount; i++) {
            body.onNext(List.of(ByteBuffer.wrap(chunk.clone())));
        }
        body.onComplete();

        StreamingBody.Outcome outcome = outcome(body);
        assertEquals(StreamingBody.End.COMPLETE, outcome.end());
        assertEquals((long) chunkSize * chunkCount, outcome.nonStreamingBody().length());
    }

    @Test
    @Timeout(10)
    void exceedingTheTotalCapIsASizeLimitInBothModes() throws Exception {
        for (boolean sse : new boolean[]{true, false}) {
            StreamingBody body = new StreamingBody(sse, 1000, 100, line -> {
                                           }, () -> {
                                           });
            FakeSubscription subscription = new FakeSubscription();
            body.onSubscribe(subscription);
            body.onNext(List.of(bytes("data: " + "z".repeat(60) + "\n")));
            assertFalse(body.getBody().toCompletableFuture().isDone());
            body.onNext(List.of(bytes("data: " + "z".repeat(60) + "\n")));

            assertEquals(StreamingBody.End.SIZE_LIMIT, outcome(body).end(), "sse=" + sse);
            assertTrue(subscription.cancelled.get());
        }
    }

    @Test
    @Timeout(10)
    void stopFromAnotherThreadCompletesWithin100msAndCancelsTheSubscription() throws Exception {
        StreamingBody body = new StreamingBody(true, 1000, 100_000, line -> {
                                       }, () -> {
                                       });
        FakeSubscription subscription = new FakeSubscription();
        body.onSubscribe(subscription);

        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            long start = System.nanoTime();
            other.submit(() -> body.stop(StreamingBody.End.CANCELLED));
            StreamingBody.Outcome outcome = body.getBody().toCompletableFuture().get(100, TimeUnit.MILLISECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(StreamingBody.End.CANCELLED, outcome.end());
            assertTrue(elapsedMs < 100, "took " + elapsedMs + " ms");
            assertTrue(subscription.cancelled.get());
        }
        finally {
            other.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void theFirstStopWinsAndLaterSignalsAreIgnored() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        StreamingBody body = new StreamingBody(true, 1000, 100_000, lines::add, () -> {
                                       });
        FakeSubscription subscription = new FakeSubscription();
        body.onSubscribe(subscription);
        body.onNext(List.of(bytes("data: one\n")));

        body.stop(StreamingBody.End.CANCELLED);
        StreamingBody.Outcome first = outcome(body);
        body.stop(StreamingBody.End.TIMEOUT);
        body.onNext(List.of(bytes("data: two\n")));
        body.onComplete();
        body.onError(new RuntimeException("late"));

        assertEquals(StreamingBody.End.CANCELLED, first.end());
        assertSame(first, outcome(body));
        assertEquals(List.of("data: one"), lines);
    }

    @Test
    @Timeout(10)
    void aSubscriptionArrivingAfterStopIsCancelledAtOnce() throws Exception {
        StreamingBody body = new StreamingBody(true, 1000, 100_000, line -> {
                                       }, () -> {
                                       });
        body.stop(StreamingBody.End.TIMEOUT);
        FakeSubscription subscription = new FakeSubscription();
        body.onSubscribe(subscription);

        assertTrue(subscription.cancelled.get());
        assertEquals(0, subscription.requested.get());
        assertEquals(StreamingBody.End.TIMEOUT, outcome(body).end());
    }

    @Test
    @Timeout(10)
    void onBytesRunsOncePerChunkBeforeTheLinesAreHandled() throws Exception {
        List<String> order = new ArrayList<>();
        StreamingBody body = new StreamingBody(true, 1000, 100_000,
                                               line -> order.add("line:" + line), () -> order.add("bytes"));
        body.onSubscribe(new FakeSubscription());
        body.onNext(List.of(bytes("a\nb\n")));
        body.onNext(List.of(bytes("c\n")));
        body.onComplete();

        assertEquals(List.of("bytes", "line:a", "line:b", "bytes", "line:c"), order);
    }

    @Test
    @Timeout(10)
    void aPublisherErrorEndsWithErrorAndKeepsTheCause() throws Exception {
        StreamingBody body = new StreamingBody(true, 1000, 100_000, line -> {
                                       }, () -> {
                                       });
        body.onSubscribe(new FakeSubscription());
        RuntimeException failure = new RuntimeException("connection reset");
        body.onError(failure);

        StreamingBody.Outcome outcome = outcome(body);
        assertEquals(StreamingBody.End.ERROR, outcome.end());
        assertSame(failure, outcome.error());
    }

    @Test
    @Timeout(10)
    void aLineHandlerThatThrowsEndsWithErrorAndCancels() throws Exception {
        StreamingBody body = new StreamingBody(true, 1000, 100_000, line -> {
                                           throw new IllegalStateException("boom");
                                       }, () -> {
                                       });
        FakeSubscription subscription = new FakeSubscription();
        body.onSubscribe(subscription);
        body.onNext(List.of(bytes("data: x\n")));

        StreamingBody.Outcome outcome = outcome(body);
        assertEquals(StreamingBody.End.ERROR, outcome.end());
        assertTrue(subscription.cancelled.get());
    }
}
