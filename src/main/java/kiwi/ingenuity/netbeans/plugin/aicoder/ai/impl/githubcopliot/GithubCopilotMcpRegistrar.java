package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;

/**
 * GitHub Copilot MCP registration strategy.
 *
 * MCP is wired per-session via the Copilot CLI's
 * {@code --additional-mcp-config} flag (built by the process manager), so
 * global endpoint registration and PreToolUse hooks are unnecessary — these
 * methods are intentional no-ops. The registrar exists only so the shared
 * {@code McpHookServer} starts/stops through {@code McpServerRegistry}.
 */
public class GithubCopilotMcpRegistrar extends AiMcpRegistrar {

    public GithubCopilotMcpRegistrar(String sessionId) {
        super(sessionId, AiTypeEnum.GitHubCoPilot);
    }

    @Override
    public void addMcpEndpoint(String endpointUrl) {
    }

    @Override
    public void removeMcpEndpoint() {
    }

    @Override
    public boolean registerHooks(String serverBaseUrl) {
        return true;
    }

    @Override
    public void unregisterHooks() {
    }
}
