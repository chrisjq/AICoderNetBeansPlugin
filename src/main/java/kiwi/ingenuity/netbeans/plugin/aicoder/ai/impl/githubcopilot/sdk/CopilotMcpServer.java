package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.util.List;

/**
 * Describes an MCP server configuration shared by both Copilot protocols.
 */
public record CopilotMcpServer(String url, List<String> tools, int timeoutMillis) {

}
