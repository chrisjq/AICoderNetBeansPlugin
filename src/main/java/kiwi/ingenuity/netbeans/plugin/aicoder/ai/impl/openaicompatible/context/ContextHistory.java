package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;

/**
 * The model-facing conversation history, grouped into turns, plus the pinned system prompt (kept outside the turns) and
 * a compaction summary slot reserved for the summariser (Plan 7).
 *
 * <p>
 * A <b>turn</b> is a user message together with everything up to the next user message. Because a tool result always
 * immediately follows the assistant tool-call that produced it, cutting only between turns can never split a call from
 * its result. Any messages that appear before the first user message (unusual) form a leading turn so nothing is lost.
 *
 * <p>
 * The class is not thread-safe; the owning broker serialises access.
 */
public final class ContextHistory {

    private static final String FIELD_PINNED = "pinned";
    private static final String FIELD_SUMMARY = "compactionSummary";
    private static final String FIELD_RATIO = "calibrationRatio";
    private static final String FIELD_MESSAGES = "messages";
    private static final String FIELD_ROLE = "role";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_TOOL_CALL_ID = "toolCallId";
    private static final String FIELD_TOOL_CALLS = "toolCalls";
    private static final String FIELD_CALL_ID = "id";
    private static final String FIELD_CALL_NAME = "name";
    private static final String FIELD_CALL_ARGS = "argumentsJson";

    private static final Logger LOGGER = Logger.getLogger(ContextHistory.class.getName());
    private static final AtomicBoolean LOGGED_PARSE_FAILURE = new AtomicBoolean(false);

    private String pinned;
    private String compactionSummary;
    private final List<ChatMessage> messages = new ArrayList<>();

    public ContextHistory() {
    }

    public ContextHistory(String pinned) {
        this.pinned = pinned;
    }

    public String pinned() {
        return pinned;
    }

    public void setPinned(String pinned) {
        this.pinned = pinned;
    }

    public String compactionSummary() {
        return compactionSummary;
    }

    public void setCompactionSummary(String compactionSummary) {
        this.compactionSummary = compactionSummary;
    }

    public void append(ChatMessage message) {
        if (message != null) {
            messages.add(message);
        }
    }

    /**
     * A live, unmodifiable view; the {@link ChatMessage} objects themselves remain mutable (the pruner rewrites
     * content).
     */
    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public int size() {
        return messages.size();
    }

    /**
     * Removes the oldest {@code count} turns from the model-facing history. Returns how many turns were actually
     * removed (clamped to the turn count).
     */
    public int dropOldestTurns(int count) {
        if (count <= 0 || messages.isEmpty()) {
            return 0;
        }
        List<List<ChatMessage>> turns = turns();
        int drop = Math.min(count, turns.size());
        int remove = 0;
        for (int i = 0; i < drop; i++) {
            remove += turns.get(i).size();
        }
        messages.subList(0, remove).clear();
        return drop;
    }

    /**
     * Inserts {@code message} at the front of the model-facing history (immediately after the pinned system prompt).
     */
    public void prepend(ChatMessage message) {
        if (message != null) {
            messages.add(0, message);
        }
    }

    /**
     * The messages as they go on the wire: the pinned system prompt first (when set), then every message in order. The
     * compaction summary, when present, is not injected here — it is a {@link ChatRole#SYSTEM} message the broker
     * places.
     */
    public List<ChatMessage> toRequestMessages() {
        List<ChatMessage> out = new ArrayList<>(messages.size() + 1);
        if (pinned != null) {
            out.add(new ChatMessage(ChatRole.SYSTEM, pinned, List.of(), null));
        }
        out.addAll(messages);
        return out;
    }

    /**
     * The messages grouped into turns. Each inner list is a fresh list of the same {@link ChatMessage} references, so
     * the pruner can mutate message content through it. A turn begins at a user message; older leading messages form a
     * turn.
     */
    public List<List<ChatMessage>> turns() {
        List<List<ChatMessage>> turns = new ArrayList<>();
        List<ChatMessage> current = null;
        for (ChatMessage message : messages) {
            if (message.role() == ChatRole.USER || current == null) {
                current = new ArrayList<>();
                turns.add(current);
            }
            current.add(message);
        }
        return turns;
    }

    /**
     * Estimated tokens for the pinned prompt plus every message; the compaction summary is counted when present.
     */
    public int estimateTokens(TokenEstimator estimator) {
        if (estimator == null) {
            return 0;
        }
        int total = 0;
        if (pinned != null) {
            total += estimator.estimate(new ChatMessage(ChatRole.SYSTEM, pinned, List.of(), null));
        }
        for (ChatMessage message : messages) {
            total += estimator.estimate(message);
        }
        if (compactionSummary != null) {
            total += estimator.estimate(new ChatMessage(ChatRole.SYSTEM, compactionSummary, List.of(), null));
        }
        return total;
    }

    /**
     * Serialises the history for persistence, including the pinned prompt, every message (so prune placeholders survive
     * verbatim), the compaction summary and the estimator's calibration ratio.
     */
    public JsonObject toJson(TokenEstimator estimator) {
        JsonObject root = new JsonObject();
        if (pinned != null) {
            root.addProperty(FIELD_PINNED, pinned);
        }
        if (compactionSummary != null) {
            root.addProperty(FIELD_SUMMARY, compactionSummary);
        }
        if (estimator != null) {
            root.addProperty(FIELD_RATIO, estimator.calibrationRatio());
        }
        JsonArray array = new JsonArray();
        for (ChatMessage message : messages) {
            array.add(messageToJson(message));
        }
        root.add(FIELD_MESSAGES, array);
        return root;
    }

    /**
     * Restores a history saved by {@link #toJson(TokenEstimator)}. The calibration ratio, when present, is restored
     * into the supplied estimator. Corrupt or unexpected shapes are tolerated: missing pieces become absent.
     */
    public static ContextHistory fromJson(JsonObject json, TokenEstimator estimator) {
        ContextHistory history = new ContextHistory();
        if (json == null) {
            return history;
        }
        if (isPrimitive(json, FIELD_PINNED)) {
            history.setPinned(json.get(FIELD_PINNED).getAsString());
        }
        if (isPrimitive(json, FIELD_SUMMARY)) {
            history.setCompactionSummary(json.get(FIELD_SUMMARY).getAsString());
        }
        if (estimator != null && isNumberPrimitive(json, FIELD_RATIO)) {
            estimator.restoreRatio(json.get(FIELD_RATIO).getAsDouble());
        }
        JsonElement element = json.get(FIELD_MESSAGES);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    ChatMessage message = messageFromJson(item.getAsJsonObject());
                    if (message != null) {
                        history.append(message);
                    }
                }
            }
        }
        return history;
    }

    /**
     * Convenience for callers that only have the text and not an estimator. A malformed string yields an empty history
     * rather than throwing: saved context is best-effort, and a torn file must never break a session. The parse failure
     * is logged once, and only when JSON debug logging is on.
     */
    public static ContextHistory fromJson(String json, TokenEstimator estimator) {
        if (json == null || json.isBlank()) {
            return new ContextHistory();
        }
        try {
            return fromJson(JsonParser.parseString(json).getAsJsonObject(), estimator);
        }
        catch (RuntimeException ex) {
            if (PluginSettings.isDebugJson() && LOGGED_PARSE_FAILURE.compareAndSet(false, true)) {
                LOGGER.log(Level.WARNING, "Could not restore saved OpenAI-compatible context; starting empty", ex);
            }
            return new ContextHistory();
        }
    }

    private static JsonObject messageToJson(ChatMessage message) {
        JsonObject object = new JsonObject();
        object.addProperty(FIELD_ROLE, message.role() == null ? null : message.role().name());
        if (message.content() != null) {
            object.addProperty(FIELD_CONTENT, message.content());
        }
        if (message.toolCallId() != null) {
            object.addProperty(FIELD_TOOL_CALL_ID, message.toolCallId());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            JsonArray calls = new JsonArray();
            for (ChatToolCall call : message.toolCalls()) {
                JsonObject callObject = new JsonObject();
                if (call.id() != null) {
                    callObject.addProperty(FIELD_CALL_ID, call.id());
                }
                if (call.name() != null) {
                    callObject.addProperty(FIELD_CALL_NAME, call.name());
                }
                if (call.argumentsJson() != null) {
                    callObject.addProperty(FIELD_CALL_ARGS, call.argumentsJson());
                }
                calls.add(callObject);
            }
            object.add(FIELD_TOOL_CALLS, calls);
        }
        return object;
    }

    private static ChatMessage messageFromJson(JsonObject object) {
        ChatRole role = parseRole(object);
        if (role == null) {
            return null;
        }
        String content = isPrimitive(object, FIELD_CONTENT) ? object.get(FIELD_CONTENT).getAsString() : null;
        String toolCallId = isPrimitive(object, FIELD_TOOL_CALL_ID) ? object.get(FIELD_TOOL_CALL_ID).getAsString() : null;
        List<ChatToolCall> calls = new ArrayList<>();
        JsonElement element = object.get(FIELD_TOOL_CALLS);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    JsonObject callObject = item.getAsJsonObject();
                    calls.add(new ChatToolCall(
                            isPrimitive(callObject, FIELD_CALL_ID) ? callObject.get(FIELD_CALL_ID).getAsString() : null,
                            isPrimitive(callObject, FIELD_CALL_NAME) ? callObject.get(FIELD_CALL_NAME).getAsString() : null,
                            isPrimitive(callObject, FIELD_CALL_ARGS) ? callObject.get(FIELD_CALL_ARGS).getAsString() : null));
                }
            }
        }
        return new ChatMessage(role, content, calls, toolCallId);
    }

    private static ChatRole parseRole(JsonObject object) {
        if (!isPrimitive(object, FIELD_ROLE)) {
            return null;
        }
        try {
            return ChatRole.valueOf(object.get(FIELD_ROLE).getAsString());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isPrimitive(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isNumberPrimitive(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }
}
