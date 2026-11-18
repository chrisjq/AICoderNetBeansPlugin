package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v2;

import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.rpc.CreateSessionOptions;
import com.microsoft.copilot.rpc.McpAuthResult;
import com.microsoft.copilot.rpc.McpHttpServerConfig;
import com.microsoft.copilot.rpc.McpServerConfig;
import com.microsoft.copilot.rpc.ResumeSessionOptions;
import com.microsoft.copilot.rpc.SessionEventOptions;
import com.microsoft.copilot.rpc.SessionInferenceOptions;
import com.microsoft.copilot.rpc.SessionMcpOptions;
import com.microsoft.copilot.rpc.SessionPermissionOptions;
import com.microsoft.copilot.rpc.SessionToolOptions;
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

final class V2Client implements CopilotSdkClient {

    private final CopilotClient client;

    V2Client(CopilotClient client) {
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
            spec.mcpServers().forEach((name, server) -> result.put(name,
                                                                   new McpHttpServerConfig().setUrl(server.url()).setTools(server.tools())
                                                                           .setTimeout(server.timeoutMillis())));
        }
        return result;
    }

    static CreateSessionOptions createConfig(CopilotSessionSpec spec) {
        CreateSessionOptions config = new CreateSessionOptions().setSessionId(spec.sessionId())
                .setModel(spec.model());
        configure(config, spec);
        return config;
    }

    static ResumeSessionOptions resumeConfig(CopilotSessionSpec spec) {
        ResumeSessionOptions config = new ResumeSessionOptions().setModel(spec.model());
        configure(config, spec);
        return config;
    }

    private static void configure(CreateSessionOptions config, CopilotSessionSpec spec) {
        config.setTools(new SessionToolOptions().setExcludedTools(spec.excludedTools()))
                .setMcp(mcpOptions(spec)).setPermissions(permissionOptions(spec)).setEvents(streamingEvents());
        if (spec.reasoningEffort() != null && !spec.reasoningEffort().isBlank()) {
            config.setInference(new SessionInferenceOptions().setReasoningEffort(spec.reasoningEffort()));
        }
    }

    private static void configure(ResumeSessionOptions config, CopilotSessionSpec spec) {
        config.setTools(new SessionToolOptions().setExcludedTools(spec.excludedTools()))
                .setMcp(mcpOptions(spec)).setPermissions(permissionOptions(spec)).setEvents(streamingEvents());
        if (spec.reasoningEffort() != null && !spec.reasoningEffort().isBlank()) {
            config.setInference(new SessionInferenceOptions().setReasoningEffort(spec.reasoningEffort()));
        }
    }

    private static SessionMcpOptions mcpOptions(CopilotSessionSpec spec) {
        SessionMcpOptions options = new SessionMcpOptions().setServers(servers(spec));
        if (spec.cancelMcpAuth()) {
            options.setOnAuthRequest((request, invocation)
                    -> CompletableFuture.completedFuture(McpAuthResult.cancelled()));
        }
        return options;
    }

    /**
     * Streams the reply in pieces, so the delta events reach the chat as the model writes them.
     */
    private static SessionEventOptions streamingEvents() {
        return new SessionEventOptions().setStreaming(true);
    }

    private static SessionPermissionOptions permissionOptions(CopilotSessionSpec spec) {
        return new SessionPermissionOptions().setOnRequest(new V2Permissions(spec.gate()));
    }

    @Override
    public CompletableFuture<CopilotSdkSession> create(CopilotSessionSpec spec) {
        return client.createSession(createConfig(spec)).thenApply(V2Session::new);
    }

    @Override
    public CompletableFuture<CopilotSdkSession> resume(String id, CopilotSessionSpec spec) {
        return client.resumeSession(id, resumeConfig(spec)).thenApply(V2Session::new);
    }

    @Override
    public CompletableFuture<List<CopilotModel>> listModels() {
        return client.listModels().thenApply(models -> models == null ? List.of()
                                                       : models.stream().filter(Objects::nonNull)
                        .map(model -> new CopilotModel(model.id(), model.name(),
                                                       model.supportedReasoningEfforts(), model.defaultReasoningEffort())).toList());
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
