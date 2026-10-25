package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;

/**
 * One stored message plus the bookkeeping the raw ChatMessage cannot hold.
 *
 * Identity fields are final; everything that legitimately changes over an entry's life is settable.
 */
public class OllamaContextEntry {

    public static OllamaContextEntry fromJson(JsonObject o) {
        List<ChatToolCall> calls = new ArrayList<>();
        JsonArray arr = o.getAsJsonArray(OllamaContextJsonKeyEnum.TOOL_CALLS.key());
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject co = el.getAsJsonObject();
                calls.add(new ChatToolCall(optString(co, OllamaContextJsonKeyEnum.ID.key()),
                                           optString(co, OllamaContextJsonKeyEnum.NAME.key()),
                                           optString(co, OllamaContextJsonKeyEnum.ARGUMENTS.key())));
            }
        }
        ChatMessage msg = new ChatMessage(
                ChatRole.valueOf(o.get(OllamaContextJsonKeyEnum.ROLE.key()).getAsString()),
                optString(o, OllamaContextJsonKeyEnum.CONTENT.key()),
                calls,
                optString(o, OllamaContextJsonKeyEnum.TOOL_CALL_ID.key()));
        return new OllamaContextEntry(
                o.get(OllamaContextJsonKeyEnum.SEQUENCE.key()).getAsLong(),
                o.get(OllamaContextJsonKeyEnum.GROUP_ID.key()).getAsLong(),
                o.get(OllamaContextJsonKeyEnum.TIMESTAMP.key()).getAsLong(),
                msg,
                OllamaContextRetentionEnum.valueOf(o.get(OllamaContextJsonKeyEnum.RETENTION.key()).getAsString()),
                o.get(OllamaContextJsonKeyEnum.ESTIMATED_TOKENS.key()).getAsInt(),
                optString(o, OllamaContextJsonKeyEnum.CACHE_ID.key()));
    }

    private static String optString(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    private final long sequence;
    private final long groupId;
    private final long timestamp;

    private ChatMessage message;
    private OllamaContextRetentionEnum retention;
    private int estimatedTokens;
    private String cacheId;

    public OllamaContextEntry(long sequence, long groupId, long timestamp, ChatMessage message,
                              OllamaContextRetentionEnum retention, int estimatedTokens, String cacheId) {
        this.sequence = sequence;
        this.groupId = groupId;
        this.timestamp = timestamp;
        this.message = message;
        this.retention = retention;
        this.estimatedTokens = estimatedTokens;
        this.cacheId = cacheId;
    }

    public long sequence() {
        return sequence;
    }

    public long groupId() {
        return groupId;
    }

    public long timestamp() {
        return timestamp;
    }

    public ChatMessage message() {
        return message;
    }

    public void setMessage(ChatMessage message) {
        this.message = message;
    }

    public OllamaContextRetentionEnum retention() {
        return retention;
    }

    public void setRetention(OllamaContextRetentionEnum retention) {
        this.retention = retention;
    }

    public int estimatedTokens() {
        return estimatedTokens;
    }

    public void setEstimatedTokens(int estimatedTokens) {
        this.estimatedTokens = estimatedTokens;
    }

    public String cacheId() {
        return cacheId;
    }

    public void setCacheId(String cacheId) {
        this.cacheId = cacheId;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty(OllamaContextJsonKeyEnum.SEQUENCE.key(), sequence);
        o.addProperty(OllamaContextJsonKeyEnum.GROUP_ID.key(), groupId);
        o.addProperty(OllamaContextJsonKeyEnum.TIMESTAMP.key(), timestamp);
        o.addProperty(OllamaContextJsonKeyEnum.RETENTION.key(), retention.name());
        o.addProperty(OllamaContextJsonKeyEnum.ESTIMATED_TOKENS.key(), estimatedTokens);
        if (cacheId != null) {
            o.addProperty(OllamaContextJsonKeyEnum.CACHE_ID.key(), cacheId);
        }
        o.addProperty(OllamaContextJsonKeyEnum.ROLE.key(), message.role().name());
        if (message.content() != null) {
            o.addProperty(OllamaContextJsonKeyEnum.CONTENT.key(), message.content());
        }
        if (message.toolCallId() != null) {
            o.addProperty(OllamaContextJsonKeyEnum.TOOL_CALL_ID.key(), message.toolCallId());
        }
        JsonArray calls = new JsonArray();
        for (ChatToolCall c : message.toolCalls()) {
            JsonObject co = new JsonObject();
            co.addProperty(OllamaContextJsonKeyEnum.ID.key(), c.id());
            co.addProperty(OllamaContextJsonKeyEnum.NAME.key(), c.name());
            co.addProperty(OllamaContextJsonKeyEnum.ARGUMENTS.key(), c.argumentsJson());
            calls.add(co);
        }
        o.add(OllamaContextJsonKeyEnum.TOOL_CALLS.key(), calls);
        return o;
    }

}
