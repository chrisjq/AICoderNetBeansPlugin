package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

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
 * Identity fields are final; everything that legitimately changes over an
 * entry's life is settable.
 */
public class ContextEntry {

    public static ContextEntry fromJson(JsonObject o) {
        List<ChatToolCall> calls = new ArrayList<>();
        JsonArray arr = o.getAsJsonArray(ContextJsonKeyEnum.TOOL_CALLS.key());
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject co = el.getAsJsonObject();
                calls.add(new ChatToolCall(optString(co, ContextJsonKeyEnum.ID.key()),
                        optString(co, ContextJsonKeyEnum.NAME.key()),
                        optString(co, ContextJsonKeyEnum.ARGUMENTS.key())));
            }
        }
        ChatMessage msg = new ChatMessage(
                ChatRole.valueOf(o.get(ContextJsonKeyEnum.ROLE.key()).getAsString()),
                optString(o, ContextJsonKeyEnum.CONTENT.key()),
                calls,
                optString(o, ContextJsonKeyEnum.TOOL_CALL_ID.key()));
        return new ContextEntry(
                o.get(ContextJsonKeyEnum.SEQUENCE.key()).getAsLong(),
                o.get(ContextJsonKeyEnum.GROUP_ID.key()).getAsLong(),
                o.get(ContextJsonKeyEnum.TIMESTAMP.key()).getAsLong(),
                msg,
                ContextRetentionEnum.valueOf(o.get(ContextJsonKeyEnum.RETENTION.key()).getAsString()),
                o.get(ContextJsonKeyEnum.ESTIMATED_TOKENS.key()).getAsInt(),
                optString(o, ContextJsonKeyEnum.CACHE_ID.key()));
    }

    private static String optString(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    private final long sequence;
    private final long groupId;
    private final long timestamp;

    private ChatMessage message;
    private ContextRetentionEnum retention;
    private int estimatedTokens;
    private String cacheId;

    public ContextEntry(long sequence, long groupId, long timestamp, ChatMessage message,
            ContextRetentionEnum retention, int estimatedTokens, String cacheId) {
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

    public ContextRetentionEnum retention() {
        return retention;
    }

    public void setRetention(ContextRetentionEnum retention) {
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
        o.addProperty(ContextJsonKeyEnum.SEQUENCE.key(), sequence);
        o.addProperty(ContextJsonKeyEnum.GROUP_ID.key(), groupId);
        o.addProperty(ContextJsonKeyEnum.TIMESTAMP.key(), timestamp);
        o.addProperty(ContextJsonKeyEnum.RETENTION.key(), retention.name());
        o.addProperty(ContextJsonKeyEnum.ESTIMATED_TOKENS.key(), estimatedTokens);
        if (cacheId != null) {
            o.addProperty(ContextJsonKeyEnum.CACHE_ID.key(), cacheId);
        }
        o.addProperty(ContextJsonKeyEnum.ROLE.key(), message.role().name());
        if (message.content() != null) {
            o.addProperty(ContextJsonKeyEnum.CONTENT.key(), message.content());
        }
        if (message.toolCallId() != null) {
            o.addProperty(ContextJsonKeyEnum.TOOL_CALL_ID.key(), message.toolCallId());
        }
        JsonArray calls = new JsonArray();
        for (ChatToolCall c : message.toolCalls()) {
            JsonObject co = new JsonObject();
            co.addProperty(ContextJsonKeyEnum.ID.key(), c.id());
            co.addProperty(ContextJsonKeyEnum.NAME.key(), c.name());
            co.addProperty(ContextJsonKeyEnum.ARGUMENTS.key(), c.argumentsJson());
            calls.add(co);
        }
        o.add(ContextJsonKeyEnum.TOOL_CALLS.key(), calls);
        return o;
    }

}
