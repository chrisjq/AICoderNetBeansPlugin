package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context;

import java.io.IOException;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;

/**
 * Summarises a span of evicted messages. A one-method interface so the broker never depends on HttpAiClient, which
 * keeps it unit-testable without a network stub.
 */
@FunctionalInterface
public interface OllamaSummariser {

    /**
     * @return the summary text, or null/blank if none could be produced
     */
    String summarise(List<ChatMessage> span) throws IOException;
}
