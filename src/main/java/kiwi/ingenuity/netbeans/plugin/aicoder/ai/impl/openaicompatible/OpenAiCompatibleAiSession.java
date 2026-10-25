package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolHandlerFactory;

/**
 * The OpenAI Compatible session as the MCP layer sees it: the plugin's tool handlers, registered so the in-process
 * bridge can authenticate and run them (the same shape as Ollama (local)'s session).
 */
public final class OpenAiCompatibleAiSession extends AbstractAiSession {

    private final AiProcessEventListener listener;
    private final Map<McpToolEnum, McpToolInterface> toolHandlers;

    public OpenAiCompatibleAiSession(AiSession session, AiProcessEventListener listener) {
        super(session);
        this.listener = listener;
        this.toolHandlers = Collections.unmodifiableMap(
                new LinkedHashMap<>(ToolHandlerFactory.getToolHandlers(McpServerRegistry.getServer())));
        McpInstructionRegistry.registerHandlers(session.aiType(), toolHandlers);
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

    public void dispose() {
        SessionRegistry.unregister(this);
    }
}
