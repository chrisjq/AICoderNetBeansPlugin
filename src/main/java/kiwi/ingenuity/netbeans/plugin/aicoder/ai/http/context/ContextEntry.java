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
        JsonArray arr = o.getAsJsonArray("toolCalls");
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject co = el.getAsJsonObject();
                calls.add(new ChatToolCall(optString(co, "id"), optString(co, "name"),
                        optString(co, "arguments")));
            }
        }
        ChatMessage msg = new ChatMessage(
                ChatRole.valueOf(o.get("role").getAsString()),
                optString(o, "content"),
                calls,
                optString(o, "toolCallId"));
        return new ContextEntry(
                o.get("sequence").getAsLong(),
                o.get("groupId").getAsLong(),
                o.get("timestamp").getAsLong(),
                msg,
                ContextRetentionEnum.valueOf(o.get("retention").getAsString()),
                o.get("estimatedTokens").getAsInt(),
                optString(o, "cacheId"));
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
        o.addProperty("sequence", sequence);
        o.addProperty("groupId", groupId);
        o.addProperty("timestamp", timestamp);
        o.addProperty("retention", retention.name());
        o.addProperty("estimatedTokens", estimatedTokens);
        if (cacheId != null) {
            o.addProperty("cacheId", cacheId);
        }
        o.addProperty("role", message.role().name());
        if (message.content() != null) {
            o.addProperty("content", message.content());
        }
        if (message.toolCallId() != null) {
            o.addProperty("toolCallId", message.toolCallId());
        }
        JsonArray calls = new JsonArray();
        for (ChatToolCall c : message.toolCalls()) {
            JsonObject co = new JsonObject();
            co.addProperty("id", c.id());
            co.addProperty("name", c.name());
            co.addProperty("arguments", c.argumentsJson());
            calls.add(co);
        }
        o.add("toolCalls", calls);
        return o;
    }

}
