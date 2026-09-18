package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session;

import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

/**
 * MCP-layer session for pi. Mirrors {@code ClaudeAiSession}'s shared-state pattern (holds only the {@link AiSession}
 * reference via {@link AbstractAiSession}) and registers the plugin session UUID in {@link SessionRegistry} at
 * construction, exactly as Claude does — that is what lets {@code McpHookServer}'s PreToolUse handler resolve a hook
 * POST for this session.
 *
 * <p>
 * <b>Task 0.6 finding (spec *Implementation-time verifications*, item 7):</b> unlike Claude, pi needs no
 * {@code registerAlias} step. Claude's hook payload carries Claude's own CLI-internal {@code session_id} — a value the
 * plugin only learns by reading it back off the stream (see {@code ClaudeStreamJsonParser#setOnFirstSessionId}) — so
 * {@code ClaudeAiSession} maps that discovered id to itself as an alias. pi's generated extension instead has
 * {@code AICODER_CONFIG.sessionId} baked in verbatim as <em>the plugin's own session UUID</em> at generation time (spec
 * *Extension file generation and lifetime*: {@code sessionId = the plugin session UUID}) — never a value discovered
 * from pi's own RPC stream. So every hook POST from the extension already carries exactly the id this class registers
 * itself under below; the primary {@link SessionRegistry#register} is resolved by {@code getId()} with no second
 * identity to alias. The per-session hook lock itself is created generically by
 * {@code McpHookServer.registerSession}/{@code updateSessionScope} (keyed on the same plugin session id) wherever a
 * session is opened, for every AI type alike — not something this class creates directly.
 */
public class PiAiSession extends AbstractAiSession {

    private final AiProcessEventListener listener;
    private final Map<McpToolEnum, McpToolInterface> toolHandlers;

    public PiAiSession(AiSession session, AiProcessEventListener listener) {
        super(session);
        this.listener = listener;
        this.toolHandlers = PiToolHandlerFactory.build(() -> listener, McpServerRegistry.getServer());
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
