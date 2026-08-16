package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.util.List;

/**
 * One message in an OpenAI-compatible chat request.
 *
 * A plain class rather than a record so content can be rewritten in place —
 * pinned slots are upserted rather than replaced. Accessors keep record-style
 * names so existing call sites compile unchanged, matching the convention
 * already used by AiSession.
 */
public class ChatMessage {

    private final ChatRole role;
    private final List<ChatToolCall> toolCalls;
    private final String toolCallId;

    private String content;

    public ChatMessage(ChatRole role, String content, List<ChatToolCall> toolCalls,
            String toolCallId) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.toolCallId = toolCallId;
    }

    public ChatRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ChatToolCall> toolCalls() {
        return toolCalls;
    }

    public String toolCallId() {
        return toolCallId;
    }

    /**
     * A copy that later mutation of this instance cannot affect. The tool-call
     * list is shared deliberately: ChatToolCall is an immutable record and the
     * list is unmodifiable, so sharing is safe and avoids pointless copying.
     */
    public ChatMessage copy() {
        return new ChatMessage(role, content, toolCalls, toolCallId);
    }
}
