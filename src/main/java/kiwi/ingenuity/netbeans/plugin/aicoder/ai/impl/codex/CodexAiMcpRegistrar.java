package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;

/**
 * MCP registrar for Codex. Like OpenCode, Codex has no PreToolUse-style hook
 * mechanism and needs no CLI/config-file registration step — the plugin's
 * {@code McpHookServer} endpoint is handed to the {@code codex app-server}
 * subprocess directly via {@code -c mcp_servers.<name>.url=...} at spawn time
 * (design doc §0a: per-invocation {@code -c} overrides are TOML-parsed and
 * never touch {@code ~/.codex/config.toml}, avoiding the cross-session
 * concurrency problem a shared config file would create).
 *
 * <p>
 * This registrar's only real job is triggering {@code McpServerRegistry}'s
 * start/stop of the shared HTTP server — same division of labour as
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiMcpRegistrar}.
 * {@link #getEndpointUrl()} is populated only for the first session of this AI
 * type (see that class's javadoc for why); {@code CodexAiProcessManager}
 * therefore sources the URL from {@code McpServerRegistry.endpointUrlFor}
 * rather than from this accessor.
 */
public final class CodexAiMcpRegistrar extends AiMcpRegistrar {

    private volatile String endpointUrl = null;

    public CodexAiMcpRegistrar(String sessionId) {
        super(sessionId, AiTypeEnum.CODEX);
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    @Override
    public void addMcpEndpoint(String url) {
        this.endpointUrl = url;
    }

    @Override
    public void removeMcpEndpoint() {
        this.endpointUrl = null;
    }

    @Override
    public boolean registerHooks(String serverBaseUrl) {
        // No-op: Codex has no PreToolUse hook mechanism; MCP registration
        // itself happens per-spawn via -c in CodexAiProcessManager.
        return true;
    }

    @Override
    public void unregisterHooks() {
        // No-op.
    }
}
