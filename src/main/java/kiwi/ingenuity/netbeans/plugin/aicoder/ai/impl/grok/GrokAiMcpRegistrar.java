package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;

/**
 * MCP registrar for Grok. Like OpenCode, Grok receives its MCP server inline in the {@code session/new}/
 * {@code session/load} ACP request (see {@code GrokAiProcessManager.buildSessionNewParams}), not via a CLI
 * command or a config file — so no {@code grok mcp add}/{@code remove} call and no PreToolUse hook file are
 * needed any more. Permission is handled over the ACP wire too ({@code session/request_permission}, bridged
 * by {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AbstractAcpClientHandler}), which is what replaces
 * the old hook file this registrar used to write.
 *
 * <p>
 * {@link #addMcpEndpoint} captures the URL {@code McpServerRegistry} delivers, for the first session of this
 * type only — later sessions read the URL from {@code McpServerRegistry.endpointUrlFor(AiTypeEnum.GROK)}
 * instead (see {@code OpenCodeAiMcpRegistrar}'s identical note).
 */
public final class GrokAiMcpRegistrar extends AiMcpRegistrar {

    private volatile String endpointUrl = null;

    public GrokAiMcpRegistrar(String sessionId) {
        super(sessionId, AiTypeEnum.GROK);
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
        return true; // no-op: Grok's permission bridge runs over ACP, not a PreToolUse hook file.
    }

    @Override
    public void unregisterHooks() {
        // no-op
    }
}
