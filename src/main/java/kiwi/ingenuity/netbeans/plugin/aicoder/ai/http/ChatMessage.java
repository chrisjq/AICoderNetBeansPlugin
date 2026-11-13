package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.util.List;

/**
 * One message in an OpenAI-compatible chat request.
 *
 * A plain class rather than a record so content can be rewritten in place — pinned slots are upserted rather than
 * replaced. Accessors keep record-style names so existing call sites compile unchanged, matching the convention already
 * used by AiSession.
 * <p>
 * A message may carry images. Only the OpenAI Compatible request builder sends them; every other reader (history
 * persistence, the transcript, the pruner) sees the text alone.
 */
public class ChatMessage {

    private final ChatRole role;
    private final List<ChatToolCall> toolCalls;
    private final String toolCallId;
    private final List<ChatImage> images;

    private String content;

    public ChatMessage(ChatRole role, String content, List<ChatToolCall> toolCalls,
                       String toolCallId) {
        this(role, content, toolCalls, toolCallId, List.of());
    }

    public ChatMessage(ChatRole role, String content, List<ChatToolCall> toolCalls,
                       String toolCallId, List<ChatImage> images) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.toolCallId = toolCallId;
        this.images = images == null ? List.of() : List.copyOf(images);
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
     * The images that follow the text, in order; empty for almost every message.
     */
    public List<ChatImage> images() {
        return images;
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }

    /**
     * A copy that later mutation of this instance cannot affect. The tool-call and image lists are shared deliberately:
     * both hold immutable records and the lists are unmodifiable, so sharing is safe and avoids pointless copying.
     */
    public ChatMessage copy() {
        return new ChatMessage(role, content, toolCalls, toolCallId, images);
    }
}
