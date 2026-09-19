package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * @param responseFormat optional OpenAI {@code response_format} value, used by {@link SchemaToolCalls} to constrain the
 * reply shape. Null for the ordinary path, where tool calls come back through {@code toolSchemas} instead.
 * @param reasoningEffort optional OpenAI-compatible {@code reasoning_effort} value (Ollama's Thinking setting). Null
 * when the user has not picked a level — serialised only when non-null, per
 * {@code OpenAiCompatibleClient#buildPayload}.
 */
public record ChatRequest(String baseUrl, String apiKey, String model,
                          List<ChatMessage> messages, List<JsonObject> toolSchemas,
                          JsonObject responseFormat, String reasoningEffort) {

    public ChatRequest(String baseUrl, String apiKey, String model,
                       List<ChatMessage> messages, List<JsonObject> toolSchemas,
                       JsonObject responseFormat) {
        this(baseUrl, apiKey, model, messages, toolSchemas, responseFormat, null);
    }

    public ChatRequest(String baseUrl, String apiKey, String model,
                       List<ChatMessage> messages, List<JsonObject> toolSchemas) {
        this(baseUrl, apiKey, model, messages, toolSchemas, null);
    }
}
