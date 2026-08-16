package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.util.List;

/**
 * One chat completion result.
 *
 * A plain class rather than a record, per the project convention. Token counts
 * are nullable Integer, not int: an endpoint that reports no usage must be
 * distinguishable from one reporting zero, because the estimator calibrates
 * only on real numbers.
 */
public class ChatResult {

    private final String assistantText;
    private final List<ChatToolCall> toolCalls;
    private final String finishReason;
    private final Integer promptTokens;
    private final Integer completionTokens;

    public ChatResult(String assistantText, List<ChatToolCall> toolCalls, String finishReason) {
        this(assistantText, toolCalls, finishReason, null, null);
    }

    public ChatResult(String assistantText, List<ChatToolCall> toolCalls, String finishReason,
            Integer promptTokens, Integer completionTokens) {
        this.assistantText = assistantText;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.finishReason = finishReason;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public String assistantText() {
        return assistantText;
    }

    public List<ChatToolCall> toolCalls() {
        return toolCalls;
    }

    public String finishReason() {
        return finishReason;
    }

    public Integer promptTokens() {
        return promptTokens;
    }

    public Integer completionTokens() {
        return completionTokens;
    }
}
