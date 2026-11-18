package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.McpAuthResult;
import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotQuotaSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSessionSpec;

final class V1Client implements CopilotSdkClient {

    private final CopilotClient client;

    V1Client(CopilotClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public CompletableFuture<List<String>> listSessionIds() {
        return client.listSessions().thenApply(sessions -> sessions == null ? List.of()
                                                           : sessions.stream().filter(Objects::nonNull).map(s -> s.getSessionId()).toList());
    }

    static Map<String, McpServerConfig> servers(CopilotSessionSpec spec) {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        if (spec.mcpServers() != null) {
            spec.mcpServers().forEach((name, server)
                    -> result.put(name, new McpHttpServerConfig().setUrl(server.url())
                                  .setTools(server.tools()).setTimeout(server.timeoutMillis())));
        }
        return result;
    }

    static SessionConfig createConfig(CopilotSessionSpec spec) {
        SessionConfig config = new SessionConfig().setSessionId(spec.sessionId()).setModel(spec.model()).setStreaming(true)
                .setExcludedTools(spec.excludedTools()).setMcpServers(servers(spec))
                .setOnPermissionRequest(new V1Permissions(spec.gate()));
        if (spec.reasoningEffort() != null && !spec.reasoningEffort().isBlank()) {
            config.setReasoningEffort(spec.reasoningEffort());
        }
        if (spec.cancelMcpAuth()) {
            config.setOnMcpAuthRequest((request, invocation)
                    -> CompletableFuture.completedFuture(McpAuthResult.cancelled()));
        }
        return config;
    }

    static ResumeSessionConfig resumeConfig(CopilotSessionSpec spec) {
        ResumeSessionConfig config = new ResumeSessionConfig().setModel(spec.model()).setStreaming(true)
                .setExcludedTools(spec.excludedTools()).setMcpServers(servers(spec))
                .setOnPermissionRequest(new V1Permissions(spec.gate()));
        if (spec.reasoningEffort() != null && !spec.reasoningEffort().isBlank()) {
            config.setReasoningEffort(spec.reasoningEffort());
        }
        if (spec.cancelMcpAuth()) {
            config.setOnMcpAuthRequest((request, invocation)
                    -> CompletableFuture.completedFuture(McpAuthResult.cancelled()));
        }
        return config;
    }

    @Override
    public CompletableFuture<CopilotSdkSession> create(CopilotSessionSpec spec) {
        return client.createSession(createConfig(spec)).thenApply(V1Session::new);
    }

    @Override
    public CompletableFuture<CopilotSdkSession> resume(String id, CopilotSessionSpec spec) {
        return client.resumeSession(id, resumeConfig(spec)).thenApply(V1Session::new);
    }

    @Override
    public CompletableFuture<List<CopilotModel>> listModels() {
        return client.listModels().thenApply(models -> models == null ? List.of()
                                                       : models.stream().filter(Objects::nonNull).map(m -> new CopilotModel(m.getId(), m.getName(),
                                                                                                                            m.getSupportedReasoningEfforts(), m.getDefaultReasoningEffort())).toList());
    }

    @Override
    public CompletableFuture<Map<String, CopilotQuotaSnapshot>> quotaSnapshots() {
        return client.getRpc().account.getQuota().thenApply(result -> {
            Map<String, CopilotQuotaSnapshot> snapshots = new LinkedHashMap<>();
            if (result != null && result.quotaSnapshots() != null) {
                result.quotaSnapshots().forEach((name, value) -> {
                    if (value != null) {
                        snapshots.put(name, new CopilotQuotaSnapshot(value.isUnlimitedEntitlement(),
                                                                     value.usedRequests(), value.entitlementRequests(), value.remainingPercentage(),
                                                                     value.resetDate() == null ? null : value.resetDate().toString()));
                    }
                });
            }
            return snapshots;
        });
    }

    @Override
    public void close() {
        client.close();
    }
}
