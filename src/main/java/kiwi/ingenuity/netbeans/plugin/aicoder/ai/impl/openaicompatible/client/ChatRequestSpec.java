package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;

/**
 * Everything one chat request needs, independent of how it is serialised. The optional fields are {@code null} when the
 * caller wants the server default; {@code tools} may be {@code null} or empty when the turn has no tools.
 * {@code responseFormat} is the JSON-schema {@code response_format} object, sent only when non-null and the server has
 * not refused it.
 */
public record ChatRequestSpec(String apiBase, String model, List<ChatMessage> messages, JsonArray tools,
                              Double temperature, Double topP, Integer maxTokens, String reasoningEffort,
                              JsonObject responseFormat, String keepAlive) {

    /**
     * The shape before {@code keepAlive} existed: callers that never send it keep compiling.
     */
    public ChatRequestSpec(String apiBase, String model, List<ChatMessage> messages, JsonArray tools,
                           Double temperature, Double topP, Integer maxTokens, String reasoningEffort,
                           JsonObject responseFormat) {
        this(apiBase, model, messages, tools, temperature, topP, maxTokens, reasoningEffort, responseFormat, null);
    }

    /**
     * The shape before {@code responseFormat} existed: callers that never ask for structured output keep compiling.
     */
    public ChatRequestSpec(String apiBase, String model, List<ChatMessage> messages, JsonArray tools,
                           Double temperature, Double topP, Integer maxTokens, String reasoningEffort) {
        this(apiBase, model, messages, tools, temperature, topP, maxTokens, reasoningEffort, null, null);
    }
}
