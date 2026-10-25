package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.util.List;

/**
 * The outcome of one {@link OpenAiCompatibleHttpClient#send} call, after any retries and compatibility resends.
 *
 * @param reply what was received (never null; empty when nothing arrived)
 * @param end how the call ended
 * @param error the mapped failure for {@link End#HTTP_ERROR} and {@link End#ERROR}, otherwise null
 * @param retried true when a transport retry was used
 * @param timeoutKind which limit fired for {@link End#TIMEOUT}, otherwise null
 * @param nonStreaming true when the server answered with one complete body instead of a stream (spec §3.3)
 */
public record ClientResult(AssembledReply reply, End end, MappedError error, boolean retried,
                           TimeoutKind timeoutKind, boolean nonStreaming) {

    /**
     * {@link StreamingBody.End} plus {@code HTTP_ERROR} for a non-2xx answer.
     */
    public enum End {
        COMPLETE,
        CANCELLED,
        TIMEOUT,
        SIZE_LIMIT,
        ERROR,
        HTTP_ERROR
    }

    public enum TimeoutKind {
        FIRST_BYTE,
        IDLE,
        TOTAL
    }

    public ClientResult {
        if (reply == null) {
            reply = emptyReply();
        }
    }

    public ClientResult(AssembledReply reply, End end, MappedError error, boolean retried, TimeoutKind timeoutKind) {
        this(reply, end, error, retried, timeoutKind, false);
    }

    public ClientResult(AssembledReply reply, End end, MappedError error, boolean retried) {
        this(reply, end, error, retried, null, false);
    }

    public ClientResult withRetried(boolean value) {
        return new ClientResult(reply, end, error, value, timeoutKind, nonStreaming);
    }

    /**
     * The same result, marked as having come from a server that did not stream.
     */
    public ClientResult withNonStreaming(boolean value) {
        return new ClientResult(reply, end, error, retried, timeoutKind, value);
    }

    static AssembledReply emptyReply() {
        return new AssembledReply("", List.of(), null, null, null, false, false);
    }
}
