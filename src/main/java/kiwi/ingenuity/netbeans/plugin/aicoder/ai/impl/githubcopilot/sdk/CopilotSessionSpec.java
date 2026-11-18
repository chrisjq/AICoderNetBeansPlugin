package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.util.List;
import java.util.Map;

/**
 * Describes backend-controlled settings for creating or resuming a session.
 */
public record CopilotSessionSpec(String sessionId, String model, String reasoningEffort,
                                 List<String> excludedTools,
                                 Map<String, CopilotMcpServer> mcpServers,
                                 CopilotPermissionGate gate, boolean cancelMcpAuth) {

}
