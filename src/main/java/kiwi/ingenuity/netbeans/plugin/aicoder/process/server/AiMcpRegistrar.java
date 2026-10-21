package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;

/**
 * Per-AI-type strategy for registering and deregistering MCP endpoints. Extend this class for each AI backend (Claude,
 * Github Copliot, etc.) to provide the tool-specific CLI commands and hook configuration needed by that backend.
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
     * Remove any MCP endpoint that an older plugin version left in the AI tool's global configuration. Called on the
     * first registration of this AI type, on the last deregistration and at shutdown.
     */
    public abstract void removeMcpEndpoint();

    /**
     * Called once when the shared server is first started (first registered session). Register any global hooks
     * required by this AI type (e.g. pre-tool HTTP hooks in the AI tool's settings file).
     *
     * @param serverBaseUrl base URL of the shared server, e.g. {@code http://127.0.0.1:PORT}
     *
     * @return true on success; false causes server startup to abort
     */
    public abstract boolean registerHooks(String serverBaseUrl);

    /**
     * Called once when the last session deregisters and the server is about to stop. Undo whatever
     * {@link #registerHooks} wrote.
     */
    public abstract void unregisterHooks();
}
