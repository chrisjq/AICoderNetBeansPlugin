package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

/**
 * Ollama's concrete broker. The generic behaviour is entirely in the base
 * class; only the context limit is provider-specific.
 */
public class OllamaChatContextBroker extends AbstractChatContextBroker {

    private static final int UNKNOWN_CONTEXT_LIMIT = 0;

    public OllamaChatContextBroker(String sessionId, ContextBrokerSettings settings) {
        super(sessionId, settings);
    }

    /**
     * Ollama does not report num_ctx over the OpenAI-compatible surface, and it
     * varies per model. Zero means "unknown"; the trim trigger uses the
     * configured token threshold instead, which is why that threshold is an
     * absolute count rather than a percentage of the window.
     */
    @Override
    protected int contextLimit() {
        return UNKNOWN_CONTEXT_LIMIT;
    }
}
