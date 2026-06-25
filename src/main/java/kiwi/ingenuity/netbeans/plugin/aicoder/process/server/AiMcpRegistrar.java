package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;

/**
 * Per-AI-type strategy for registering and deregistering MCP endpoints. Extend
 * this class for each AI backend (Claude, Github Copliot, etc.) to provide the
 * tool-specific CLI commands and hook configuration needed by that backend.
 * McpServerRegistry calls these methods at the right lifecycle moments.
 */
public abstract class AiMcpRegistrar {

    private final String sessionId;
    private final AiTypeEnum aiType;

    protected AiMcpRegistrar(String sessionId, AiTypeEnum aiType) {
        this.sessionId = sessionId;
        this.aiType = aiType;
    }

    public final String getSessionId() {
        return sessionId;
    }

    public final AiTypeEnum getAiType() {
        return aiType;
    }

    /**
     * Register this AI type's shared MCP endpoint with the AI tool's
     * configuration so all sessions of this type can call plugin tools. Called
     * only once per AI type (on first session of that type).
     *
     * @param endpointUrl full endpoint URL, e.g.
     * {@code http://127.0.0.1:PORT/mcp/AiTypeEnum}
     */
    public abstract void addMcpEndpoint(String endpointUrl);

    /**
     * Remove this AI type's shared MCP endpoint from the AI tool's
     * configuration. Called when all sessions are gone.
     */
    public abstract void removeMcpEndpoint();

    /**
     * Called once when the shared server is first started (first registered
     * session). Register any global hooks required by this AI type (e.g.
     * pre-tool HTTP hooks in the AI tool's settings file).
     *
     * @param serverBaseUrl base URL of the shared server, e.g.
     * {@code http://127.0.0.1:PORT}
     * @return true on success; false causes server startup to abort
     */
    public abstract boolean registerHooks(String serverBaseUrl);

    /**
     * Called once when the last session deregisters and the server is about to
     * stop. Undo whatever {@link #registerHooks} wrote.
     */
    public abstract void unregisterHooks();
}
