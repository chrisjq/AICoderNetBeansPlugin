package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.session;

import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * MCP-layer session for Grok. Holds only the shared AiSession reference (via
 * AbstractAiSession) — all mutable state (name, settings, model) is read live
 * from that shared object, so renames and config changes made in AiTopComponent
 * propagate here automatically.
 */
public class GrokAiSession extends AbstractAiSession {

    private final AiProcessEventListener listener;
    private final Map<McpToolEnum, McpToolInterface> toolHandlers;

    public GrokAiSession(AiSession session, AiProcessEventListener listener) {
        super(session);
        this.listener = listener;
        this.toolHandlers = GrokToolHandlerFactory.build(() -> listener, McpServerRegistry.getServer());
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
