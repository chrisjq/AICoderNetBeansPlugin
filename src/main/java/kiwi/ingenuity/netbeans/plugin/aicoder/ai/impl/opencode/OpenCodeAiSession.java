package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import java.util.Map;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.MailDeliveryTimingEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * MCP-layer session for OpenCode. Registers tool handlers and adds itself to {@link SessionRegistry} so the shared MCP
 * server can route tool calls back to this session.
 */
public final class OpenCodeAiSession extends AbstractAiSession {

    private final AiProcessEventListener listener;
    private final Map<McpToolEnum, McpToolInterface> toolHandlers;
    /**
     * Answers whether the spawned agent can be steered mid-turn. A supplier rather than a value because the answer is
     * discovered asynchronously after the handshake and can change when the process is recycled — a snapshot taken at
     * construction would always read false.
     */
    private volatile BooleanSupplier steerCapable = () -> false;

    public OpenCodeAiSession(AiSession session, AiProcessEventListener listener) {
        super(session);
        this.listener = listener;
        this.toolHandlers = OpenCodeToolHandlerFactory.build(() -> listener, McpServerRegistry.getServer());
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

    /**
     * Wires in the live steer-capability check. Called by the process manager once it owns a spawned agent.
     */
    public void setSteerCapableSupplier(BooleanSupplier supplier) {
        this.steerCapable = supplier != null ? supplier : () -> false;
    }

    /**
     * DURING_TURN only while the spawned agent actually exposes the steer route, otherwise the declared AFTER_TURN.
     *
     * <p>
     * OpenCode is the one backend whose mail timing is not a fixed property of the type: ACP has no mid-turn injection
     * method, so delivery goes over the HTTP API the agent runs alongside it, and whether that route exists depends on
     * the opencode build in front of us. Peers act on what ListAiSessions reports — telling them "end of turn" on a
     * session that can in fact be steered would stop them setting important=true at all.
     */
    @Override
    public MailDeliveryTimingEnum getMailDeliveryTiming() {
        return steerCapable.getAsBoolean()
                ? MailDeliveryTimingEnum.DURING_TURN
                : super.getMailDeliveryTiming();
    }

    public synchronized void dispose() {
        SessionRegistry.unregister(this);
    }
}
