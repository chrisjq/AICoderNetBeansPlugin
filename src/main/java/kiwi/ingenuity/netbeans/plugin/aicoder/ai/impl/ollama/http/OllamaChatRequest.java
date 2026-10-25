package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http;

import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;

/**
 * @param responseFormat optional OpenAI {@code response_format} value, used by {@link OllamaSchemaToolCalls} to
 * constrain the reply shape. Null for the ordinary path, where tool calls come back through {@code toolSchemas}
 * instead.
 * @param reasoningEffort optional OpenAI-compatible {@code reasoning_effort} value (Ollama's Thinking setting). Null
 * when the user has not picked a level — serialised only when non-null, per {@code OllamaHttpClient#buildPayload}.
 */
public record OllamaChatRequest(String baseUrl, String apiKey, String model,
                                List<ChatMessage> messages, List<JsonObject> toolSchemas,
                                JsonObject responseFormat, String reasoningEffort) {

    public OllamaChatRequest(String baseUrl, String apiKey, String model,
                             List<ChatMessage> messages, List<JsonObject> toolSchemas,
                             JsonObject responseFormat) {
        this(baseUrl, apiKey, model, messages, toolSchemas, responseFormat, null);
    }

    public OllamaChatRequest(String baseUrl, String apiKey, String model,
                             List<ChatMessage> messages, List<JsonObject> toolSchemas) {
        this(baseUrl, apiKey, model, messages, toolSchemas, null);
    }
}
