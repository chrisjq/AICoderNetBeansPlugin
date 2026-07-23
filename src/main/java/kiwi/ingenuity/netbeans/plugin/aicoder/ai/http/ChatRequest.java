package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * @param responseFormat optional OpenAI {@code response_format} value, used by
 * {@link SchemaToolCalls} to constrain the reply shape. Null for the ordinary
 * path, where tool calls come back through {@code toolSchemas} instead.
 */
public record ChatRequest(String baseUrl, String apiKey, String model,
        List<ChatMessage> messages, List<JsonObject> toolSchemas,
        JsonObject responseFormat) {

    public ChatRequest(String baseUrl, String apiKey, String model,
            List<ChatMessage> messages, List<JsonObject> toolSchemas) {
        this(baseUrl, apiKey, model, messages, toolSchemas, null);
    }
}
