package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

/**
 * Builds the JSON body of one {@code /chat/completions} request. It never emits {@code EndTurn} — that is the Ollama
 * path's contract, not this one; a reply with no tool call ends the turn here. The optional fields are included only
 * when the caller supplied them and the session's {@link CompatibilityFlags} still allow them.
 */
public final class RequestBuilder {

    private static final String KEY_MODEL = "model";
    private static final String KEY_STREAM = "stream";
    private static final String KEY_STREAM_OPTIONS = "stream_options";
    private static final String KEY_INCLUDE_USAGE = "include_usage";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_TOOLS = "tools";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_TOP_P = "top_p";
    private static final String KEY_MAX_TOKENS = "max_tokens";
    private static final String KEY_MAX_COMPLETION_TOKENS = "max_completion_tokens";
    private static final String KEY_REASONING_EFFORT = "reasoning_effort";
    private static final String KEY_RESPONSE_FORMAT = "response_format";
    private static final String KEY_KEEP_ALIVE = "keep_alive";

    private static final String KEY_ROLE = "role";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_TOOL_CALL_ID = "tool_call_id";
    private static final String KEY_TOOL_CALLS = "tool_calls";
    private static final String KEY_ID = "id";
    private static final String KEY_TYPE = "type";
    private static final String KEY_FUNCTION = "function";
    private static final String KEY_NAME = "name";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_ARGUMENTS = "arguments";
    private static final String KEY_PARAMETERS = "parameters";
    private static final String KEY_INPUT_SCHEMA = "input_schema";

    private static final String TYPE_FUNCTION = "function";

    private RequestBuilder() {
    }

    public static JsonObject build(ChatRequestSpec spec, CompatibilityFlags flags) {
        JsonObject payload = new JsonObject();
        payload.addProperty(KEY_MODEL, spec.model());
        payload.addProperty(KEY_STREAM, true);

        if (flags.supportsStreamUsage()) {
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty(KEY_INCLUDE_USAGE, true);
            payload.add(KEY_STREAM_OPTIONS, streamOptions);
        }

        JsonArray messages = new JsonArray();
        for (ChatMessage message : spec.messages() == null ? List.<ChatMessage>of() : spec.messages()) {
            messages.add(toApiMessage(message));
        }
        payload.add(KEY_MESSAGES, messages);

        JsonArray tools = toApiTools(spec.tools());
        if (tools.size() > 0) {
            payload.add(KEY_TOOLS, tools);
        }

        if (spec.temperature() != null) {
            payload.addProperty(KEY_TEMPERATURE, spec.temperature());
        }
        if (spec.topP() != null) {
            payload.addProperty(KEY_TOP_P, spec.topP());
        }
        if (spec.maxTokens() != null) {
            payload.addProperty(flags.usesMaxCompletionTokens() ? KEY_MAX_COMPLETION_TOKENS : KEY_MAX_TOKENS,
                                spec.maxTokens());
        }
        if (spec.reasoningEffort() != null && !spec.reasoningEffort().isBlank() && flags.supportsReasoningEffort()) {
            payload.addProperty(KEY_REASONING_EFFORT, spec.reasoningEffort());
        }
        if (spec.responseFormat() != null && flags.supportsJsonSchemaResponseFormat()) {
            payload.add(KEY_RESPONSE_FORMAT, spec.responseFormat());
        }
        if (spec.keepAlive() != null && !spec.keepAlive().isBlank()) {
            payload.addProperty(KEY_KEEP_ALIVE, spec.keepAlive());
        }
        return payload;
    }

    private static JsonObject toApiMessage(ChatMessage message) {
        JsonObject out = new JsonObject();
        out.addProperty(KEY_ROLE, message.role().name().toLowerCase(Locale.ROOT));
        if (message.content() == null) {
            out.add(KEY_CONTENT, null);
        }
        else {
            out.addProperty(KEY_CONTENT, message.content());
        }
        if (message.toolCallId() != null) {
            out.addProperty(KEY_TOOL_CALL_ID, message.toolCallId());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            JsonArray toolCalls = new JsonArray();
            for (ChatToolCall call : message.toolCalls()) {
                JsonObject toolCall = new JsonObject();
                if (call.id() != null) {
                    toolCall.addProperty(KEY_ID, call.id());
                }
                toolCall.addProperty(KEY_TYPE, TYPE_FUNCTION);
                JsonObject function = new JsonObject();
                function.addProperty(KEY_NAME, call.name());
                function.addProperty(KEY_ARGUMENTS, call.argumentsJson() == null ? "{}" : call.argumentsJson());
                toolCall.add(KEY_FUNCTION, function);
                toolCalls.add(toolCall);
            }
            out.add(KEY_TOOL_CALLS, toolCalls);
        }
        return out;
    }

    private static JsonArray toApiTools(JsonArray toolSchemas) {
        JsonArray tools = new JsonArray();
        if (toolSchemas == null) {
            return tools;
        }
        for (JsonElement element : toolSchemas) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject schema = element.getAsJsonObject();
            JsonObject function = new JsonObject();
            copyIfPresent(schema, function, KEY_NAME);
            copyIfPresent(schema, function, KEY_DESCRIPTION);
            JsonElement parameters = firstPresent(schema, KEY_PARAMETERS, ToolSchemaKeyEnum.INPUT_SCHEMA.key(),
                                                  KEY_INPUT_SCHEMA);
            if (parameters != null && parameters.isJsonObject()) {
                function.add(KEY_PARAMETERS, deepCopy(parameters.getAsJsonObject()));
            }
            JsonObject tool = new JsonObject();
            tool.addProperty(KEY_TYPE, TYPE_FUNCTION);
            tool.add(KEY_FUNCTION, function);
            tools.add(tool);
        }
        return tools;
    }

    /**
     * The first of {@code keys} whose value is present and not JSON null, or null. Tool schemas arrive with the MCP
     * camelCase {@code inputSchema}; the other two spellings are kept for the OpenAI-style and older shapes.
     */
    private static JsonElement firstPresent(JsonObject source, String... keys) {
        for (String key : keys) {
            if (source.has(key) && !source.get(key).isJsonNull()) {
                return source.get(key);
            }
        }
        return null;
    }

    private static void copyIfPresent(JsonObject source, JsonObject target, String key) {
        if (source.has(key) && !source.get(key).isJsonNull()) {
            target.add(key, source.get(key));
        }
    }

    private static JsonObject deepCopy(JsonObject source) {
        return source == null ? new JsonObject() : JsonParser.parseString(source.toString()).getAsJsonObject();
    }
}
