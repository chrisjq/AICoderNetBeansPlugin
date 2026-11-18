package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Provides protocol-neutral access to a running Copilot SDK client.
 */
public interface CopilotSdkClient extends AutoCloseable {

    CompletableFuture<List<String>> listSessionIds();

    CompletableFuture<CopilotSdkSession> create(CopilotSessionSpec spec);

    CompletableFuture<CopilotSdkSession> resume(String sessionId, CopilotSessionSpec spec);

    CompletableFuture<List<CopilotModel>> listModels();

    CompletableFuture<Map<String, CopilotQuotaSnapshot>> quotaSnapshots();

    @Override
    void close();
}
