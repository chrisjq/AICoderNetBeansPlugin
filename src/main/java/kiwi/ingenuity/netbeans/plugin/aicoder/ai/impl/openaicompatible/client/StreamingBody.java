package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Body subscriber for one response. In SSE mode every line goes to a bounded {@link SseLineSplitter}; otherwise the
 * body is accumulated for a single JSON parse. A running byte total is checked against a hard cap, and
 * {@link #stop(End)} cancels the subscription and completes the body at once, so a reader can never stay blocked on a
 * stalled or cancelled stream. The first completion wins; later signals are ignored.
 */
public final class StreamingBody implements HttpResponse.BodySubscriber<StreamingBody.Outcome> {

    public enum End {
        COMPLETE,
        CANCELLED,
        TIMEOUT,
        SIZE_LIMIT,
        ERROR
    }

    /**
     * @param nonStreamingBody the whole body for a completed non-streaming response, otherwise null
     * @param error the failure for {@link End#ERROR}, otherwise null
     */
    public record Outcome(End end, String nonStreamingBody, Throwable error) {

    }

    private final boolean sse;
    private final long maxLineBytes;
    private final long maxTotalBytes;
    private final Consumer<String> onSseLine;
    private final Runnable onBytes;
    private final SseLineSplitter splitter;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final CompletableFuture<Outcome> result = new CompletableFuture<>();
    private final AtomicBoolean done = new AtomicBoolean();
    private volatile Flow.Subscription subscription;
    private long total;
    private long currentLineBytes;
    private volatile long peakBufferedBytes;

    public StreamingBody(boolean sse, long maxLineBytes, long maxTotalBytes,
                         Consumer<String> onSseLine, Runnable onBytes) {
        this.sse = sse;
        this.maxLineBytes = maxLineBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.onSseLine = onSseLine;
        this.onBytes = onBytes;
        this.splitter = sse ? new SseLineSplitter((int) Math.min(maxLineBytes, Integer.MAX_VALUE), onSseLine) : null;
    }

    /**
     * Cancels the subscription and completes with the given end; idempotent, the first completion wins.
     */
    public void stop(End end) {
        finish(new Outcome(end, null, null), true);
    }

    /**
     * Largest number of body bytes held in memory at once (the open SSE line, or the non-streaming body).
     */
    long peakBufferedBytes() {
        return peakBufferedBytes;
    }

    @Override
    public CompletionStage<Outcome> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        if (done.get()) {
            subscription.cancel();
            return;
        }
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (done.get()) {
            return;
        }
        try {
            onBytes.run();
            for (ByteBuffer item : items) {
                if (done.get()) {
                    return;
                }
                if (!consume(item)) {
                    return;
                }
            }
        }
        catch (RuntimeException ex) {
            finish(new Outcome(End.ERROR, null, ex), true);
            return;
        }
        Flow.Subscription current = subscription;
        if (current != null && !done.get()) {
            current.request(1);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        finish(new Outcome(End.ERROR, null, throwable), false);
    }

    @Override
    public void onComplete() {
        if (done.get()) {
            return;
        }
        try {
            if (sse) {
                splitter.finish();
                finish(new Outcome(End.COMPLETE, null, null), false);
            }
            else {
                finish(new Outcome(End.COMPLETE, body.toString(StandardCharsets.UTF_8), null), false);
            }
        }
        catch (RuntimeException ex) {
            finish(new Outcome(End.ERROR, null, ex), false);
        }
    }

    /**
     * @return false when the response ended (size limit)
     */
    private boolean consume(ByteBuffer item) {
        int size = item.remaining();
        total += size;
        if (total > maxTotalBytes) {
            finish(new Outcome(End.SIZE_LIMIT, null, null), true);
            return false;
        }
        if (sse) {
            long tail = bytesAfterLastNewline(item);
            boolean ok = splitter.accept(item);
            if (!ok) {
                peakBufferedBytes = Math.max(peakBufferedBytes, maxLineBytes + 1);
                finish(new Outcome(End.SIZE_LIMIT, null, null), true);
                return false;
            }
            currentLineBytes = tail < 0 ? currentLineBytes + size : tail;
            peakBufferedBytes = Math.max(peakBufferedBytes, currentLineBytes);
        }
        else {
            byte[] bytes = new byte[size];
            item.get(bytes);
            body.write(bytes, 0, size);
            peakBufferedBytes = Math.max(peakBufferedBytes, body.size());
        }
        return true;
    }

    /**
     * @return the number of bytes after the last newline in the buffer, or -1 when it holds no newline
     */
    private static long bytesAfterLastNewline(ByteBuffer item) {
        int start = item.position();
        for (int i = item.limit() - 1; i >= start; i--) {
            if (item.get(i) == '\n') {
                return item.limit() - 1 - i;
            }
        }
        return -1;
    }

    private void finish(Outcome outcome, boolean cancelSubscription) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        if (cancelSubscription) {
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
        }
        result.complete(outcome);
    }
}
