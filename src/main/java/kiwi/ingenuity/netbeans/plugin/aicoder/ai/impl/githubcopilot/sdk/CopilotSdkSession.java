package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.util.concurrent.CompletableFuture;

/**
 * Represents one protocol-neutral Copilot conversation session.
 */
public interface CopilotSdkSession extends AutoCloseable {

    String sessionId();

    CompletableFuture<String> send(String prompt);

    CompletableFuture<String> sendImmediate(String prompt);

    CompletableFuture<Void> abort();

    CompletableFuture<CopilotCompaction> compact(String customInstructions);

    void abortManualCompaction();

    AutoCloseable subscribe(CopilotEventSink sink);

    @Override
    void close();
}
