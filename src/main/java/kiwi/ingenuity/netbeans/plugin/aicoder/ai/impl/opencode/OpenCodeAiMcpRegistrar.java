package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;

/**
 * MCP registrar for OpenCode. Unlike Claude/Grok which configure the AI CLI via
 * settings files or CLI commands, OpenCode receives MCP servers inline in the
 * {@code session/new} ACP request. No external config is written and no CLI is
 * invoked.
 *
 * <p>
 * {@link #addMcpEndpoint} captures the URL that {@code McpServerRegistry}
 * delivers. <strong>Note:</strong> the registry only calls
 * {@code addMcpEndpoint} for the first session of each AI type; registrars
 * belonging to later sessions of the same type will have a null
 * {@link #getEndpointUrl()}. The process manager therefore sources the URL from
 * {@code McpServerRegistry.endpointUrlFor(AiTypeEnum.OPENCODE)} rather than
 * from this accessor — which remains useful only for debugging the first
 * session.
 */
public final class OpenCodeAiMcpRegistrar extends AiMcpRegistrar {

    private volatile String endpointUrl = null;

    public OpenCodeAiMcpRegistrar(String sessionId) {
        super(sessionId, AiTypeEnum.OPENCODE);
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
        // No-op: OpenCode has no PreToolUse hook mechanism.
        return true;
    }

    @Override
    public void unregisterHooks() {
        // No-op.
    }
}
