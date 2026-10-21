package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;

/**
 * MCP registrar for OpenCode. Unlike Claude/Grok which configure the AI CLI via settings files or CLI commands,
 * OpenCode receives MCP servers inline in the {@code session/new} ACP request. No external config is written and no CLI
 * is invoked.
 *
 * <p>
 * Each session's URL comes from {@code McpServerRegistry.endpointUrlFor(AiTypeEnum.OPENCODE, routingId)} at spawn.
 */
public final class OpenCodeAiMcpRegistrar extends AiMcpRegistrar {

    public OpenCodeAiMcpRegistrar(String sessionId) {
        super(sessionId, AiTypeEnum.OPENCODE);
    }

    @Override
    public void removeMcpEndpoint() {
        // Nothing to remove: no global OpenCode entry is written.
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
