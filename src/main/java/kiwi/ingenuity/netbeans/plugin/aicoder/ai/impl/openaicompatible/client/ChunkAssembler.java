package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;

/**
 * Builds one {@link AssembledReply} from OpenAI-compatible SSE lines or a single JSON body. Not thread-safe; one per
 * response.
 */
public final class ChunkAssembler {

    private static final Logger LOG = Logger.getLogger(ChunkAssembler.class.getName());

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, PartialToolCall> toolCalls = new LinkedHashMap<>();
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private boolean endedCleanly;
    private boolean sawOutput;
    private Consumer<String> textDelta = ignored -> {
    };
    private Consumer<String> reasoningDelta = ignored -> {
    };
    /**
     * Set after the first malformed JSON line; further malformed lines stay silent.
     */
    boolean loggedMalformed;

    public void onTextDelta(Consumer<String> onTextDelta) {
        this.textDelta = onTextDelta == null ? ignored -> {
        } : onTextDelta;
    }

    /**
     * Receives each streamed reasoning-field delta ({@code reasoning_content} and the other {@link ReasoningFieldEnum}
     * fields, the first non-empty one per chunk) as it arrives. Not called for a non-streaming body.
     */
    public void onReasoningDelta(Consumer<String> onReasoningDelta) {
        this.reasoningDelta = onReasoningDelta == null ? ignored -> {
        } : onReasoningDelta;
    }

    /**
     * Handles one SSE line. {@code data: {...}} is parsed; {@code data: [DONE]} sets {@code endedCleanly}; comments
     * ({@code :}), blanks and {@code event:} lines are ignored.
     */
    public void onSseLine(String line) {
        if (line == null || line.isBlank() || line.startsWith(":") || line.startsWith("event:")) {
            return;
        }
        if (!line.startsWith("data:")) {
            return;
        }
        String payload = line.substring("data:".length()).stripLeading();
        if ("[DONE]".equals(payload.strip())) {
            endedCleanly = true;
            return;
        }
        JsonObject root = parseObject(payload);
        if (root == null) {
            return;
        }
        applyUsage(root);
        JsonObject choice = firstChoice(root);
        if (choice == null) {
            return;
        }
        applyFinishReason(choice);
        JsonObject delta = object(choice, "delta");
        if (delta == null) {
            return;
        }
        appendReasoning(delta, true);
        appendContent(string(delta, "content"), true);
        appendToolCalls(array(delta, "tool_calls"));
    }

    /**
     * Parses a complete non-streaming chat-completion JSON body. Always marks the reply as ended cleanly.
     */
    public void fromNonStreaming(String json) {
        endedCleanly = true;
        JsonObject root = parseObject(json == null ? "" : json);
        if (root == null) {
            return;
        }
        applyUsage(root);
        JsonObject choice = firstChoice(root);
        if (choice == null) {
            return;
        }
        applyFinishReason(choice);
        JsonObject message = object(choice, "message");
        if (message == null) {
            return;
        }
        appendReasoning(message, false);
        appendContent(string(message, "content"), false);
        appendToolCalls(array(message, "tool_calls"));
    }

    public AssembledReply result() {
        List<ChatToolCall> calls = new ArrayList<>();
        toolCalls.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .map(PartialToolCall::toChatToolCall)
                .forEach(calls::add);
        return new AssembledReply(text.toString(), calls, finishReason, promptTokens, completionTokens,
                                  endedCleanly, sawOutput, reasoning.toString());
    }

    private JsonObject parseObject(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            logMalformed("not a JSON object");
        }
        catch (RuntimeException ex) {
            logMalformed(ex.getMessage());
        }
        return null;
    }

    private void logMalformed(String detail) {
        if (loggedMalformed) {
            return;
        }
        loggedMalformed = true;
        if (PluginSettings.isDebugJson()) {
            LOG.fine(() -> "ChunkAssembler: ignored malformed JSON: " + detail);
        }
    }

    private void applyUsage(JsonObject root) {
        JsonObject usage = object(root, "usage");
        if (usage == null) {
            return;
        }
        Integer prompt = integer(usage, "prompt_tokens");
        if (prompt != null) {
            promptTokens = prompt;
        }
        Integer completion = integer(usage, "completion_tokens");
        if (completion != null) {
            completionTokens = completion;
        }
    }

    private void applyFinishReason(JsonObject choice) {
        String reason = string(choice, "finish_reason");
        if (reason != null) {
            finishReason = reason;
            endedCleanly = true;
        }
    }

    private void appendReasoning(JsonObject container, boolean emitDelta) {
        for (ReasoningFieldEnum field : ReasoningFieldEnum.values()) {
            String value = string(container, field.key());
            if (value != null && !value.isEmpty()) {
                reasoning.append(value);
                if (emitDelta) {
                    reasoningDelta.accept(value);
                }
                return;
            }
        }
    }

    private void appendContent(String content, boolean emitDelta) {
        if (content == null) {
            return;
        }
        sawOutput = true;
        text.append(content);
        if (emitDelta) {
            textDelta.accept(content);
        }
    }

    private void appendToolCalls(JsonArray deltaToolCalls) {
        if (deltaToolCalls == null) {
            return;
        }
        for (JsonElement element : deltaToolCalls) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            sawOutput = true;
            JsonObject toolCall = element.getAsJsonObject();
            int index = toolCall.has("index") && toolCall.get("index").isJsonPrimitive()
                        ? toolCall.get("index").getAsInt() : toolCalls.size();
            PartialToolCall partial = toolCalls.computeIfAbsent(index, ignored -> new PartialToolCall());
            String id = string(toolCall, "id");
            if (id != null) {
                partial.id = id;
            }
            JsonObject function = object(toolCall, "function");
            if (function == null) {
                continue;
            }
            String name = string(function, "name");
            if (name != null) {
                partial.name = name;
            }
            String arguments = string(function, "arguments");
            if (arguments != null) {
                partial.arguments.append(arguments);
            }
        }
    }

    private static JsonObject firstChoice(JsonObject root) {
        JsonArray choices = array(root, "choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JsonElement first = choices.get(0);
        return first != null && first.isJsonObject() ? first.getAsJsonObject() : null;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String string(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static Integer integer(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsInt();
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    private static final class PartialToolCall {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private ChatToolCall toChatToolCall() {
            return new ChatToolCall(id, name, arguments.toString());
        }
    }
}
