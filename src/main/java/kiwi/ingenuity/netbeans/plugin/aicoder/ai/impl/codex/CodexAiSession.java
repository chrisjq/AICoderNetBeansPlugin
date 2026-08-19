package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * MCP-layer session for Codex. Registers tool handlers and adds itself to
 * {@link SessionRegistry} so the shared MCP server can route {@code tools/call}
 * back to this session — without this, {@code McpHookServer} cannot resolve a
 * Codex session id at all and every tool call fails with "Unknown session"
 * (mirrors {@code OpenCodeAiSession}).
 */
public final class CodexAiSession extends AbstractAiSession {

    private final AiProcessEventListener listener;
    private final Map<McpToolEnum, McpToolInterface> toolHandlers;

    public CodexAiSession(AiSession session, AiProcessEventListener listener) {
        super(session);
        this.listener = listener;
        this.toolHandlers = CodexToolHandlerFactory.build(() -> listener, McpServerRegistry.getServer());
        McpInstructionRegistry.registerHandlers(session.aiType(), this.toolHandlers);
        SessionRegistry.register(this);
    }

    @Override
    public String getId() {
        return getAiSession().id();
    }

    @Override
    public AiProcessEventListener getAiProcessEventListener() {
        return listener;
    }

    @Override
    public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
        return toolHandlers;
    }

    public synchronized void dispose() {
        SessionRegistry.unregister(this);
    }
}
