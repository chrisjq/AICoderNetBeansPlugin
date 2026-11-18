package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v2;

import com.microsoft.copilot.CopilotSession;
import com.microsoft.copilot.generated.rpc.SessionHistoryCompactParams;
import com.microsoft.copilot.generated.rpc.SessionHistoryCompactResult;
import com.microsoft.copilot.rpc.MessageOptions;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotCompaction;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotEventSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSession;

final class V2Session implements CopilotSdkSession {

    private final CopilotSession session;
    private final AtomicBoolean closed = new AtomicBoolean();

    V2Session(CopilotSession session) {
        this.session = Objects.requireNonNull(session);
    }

    @Override
    public String sessionId() {
        return session.getSessionId();
    }

    @Override
    public CompletableFuture<String> send(String prompt) {
        return session.send(prompt);
    }

    @Override
    public CompletableFuture<String> sendImmediate(String prompt) {
        return session.send(new MessageOptions().setPrompt(prompt).setMode("immediate"));
    }

    @Override
    public CompletableFuture<Void> abort() {
        return session.abort();
    }

    @Override
    public CompletableFuture<CopilotCompaction> compact(String instructions) {
        return session.getRpc().history.compact(new SessionHistoryCompactParams(sessionId(), instructions,
                                                                                SessionHistoryCompactParams.SessionHistoryCompactParamsTrigger.MANUAL, null))
                .thenApply(V2Session::toCompaction);
    }

    @Override
    public void abortManualCompaction() {
        session.getRpc().history.abortManualCompaction();
    }

    @Override
    public AutoCloseable subscribe(CopilotEventSink sink) {
        return V2Events.subscribe(session, sink);
    }

    /**
     * The compaction outcome. A null result from the runtime becomes an all-null outcome, which the caller reads as
     * "did not compact".
     */
    static CopilotCompaction toCompaction(SessionHistoryCompactResult result) {
        if (result == null) {
            return new CopilotCompaction(null, null, null);
        }
        return new CopilotCompaction(result.success(), result.tokensRemoved(), result.messagesRemoved());
    }

    /**
     * Closes the session once. The SDK already treats a second close as a no-op, but the flag keeps that true for this
     * adapter whatever the SDK does.
     */
    @Override
    public void close() {
        closeOnce(closed, session::close);
    }

    /**
     * Runs {@code close} the first time only. A second call, from whichever thread, does nothing.
     */
    static void closeOnce(AtomicBoolean closed, Runnable close) {
        if (closed.compareAndSet(false, true)) {
            close.run();
        }
    }
}
