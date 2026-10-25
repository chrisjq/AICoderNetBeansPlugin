package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaChatRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaChatResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http.OllamaHttpAiClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaSummariser;

/**
 * Summarises an evicted span by asking the same backend that produced it.
 *
 * No tools are offered: a model given a tool list during summarisation will
 * often call one instead of answering. The reply is plain prose.
 */
public class OllamaContextSummariser implements OllamaSummariser {

    private static final String INSTRUCTION
            = "Summarise the following conversation excerpt in at most 200 words. "
            + "Preserve decisions made, file paths, and any facts the user stated about "
            + "themselves or the project. Do not add commentary. Reply with the summary only.";

    private final OllamaHttpAiClient client;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OllamaContextSummariser(OllamaHttpAiClient client, String baseUrl, String apiKey,
            String model) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String summarise(List<ChatMessage> span) throws IOException {
        if (span == null || span.isEmpty()) {
            return null;
        }
        StringBuilder transcript = new StringBuilder();
        for (ChatMessage m : span) {
            if (m.content() == null || m.content().isBlank()) {
                continue;
            }
            transcript.append(m.role().name().toLowerCase()).append(": ")
                    .append(m.content()).append('\n');
        }
        if (transcript.length() == 0) {
            return null;
        }
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(new ChatMessage(ChatRole.SYSTEM, INSTRUCTION, List.of(), null));
        prompt.add(new ChatMessage(ChatRole.USER, transcript.toString(), List.of(), null));

        OllamaChatResult result = client.chat(new OllamaChatRequest(baseUrl, apiKey, model, List.copyOf(prompt), List.of()),
                delta -> {
                });
        String text = result.assistantText();
        return text == null || text.isBlank() ? null : text.strip();
    }
}
