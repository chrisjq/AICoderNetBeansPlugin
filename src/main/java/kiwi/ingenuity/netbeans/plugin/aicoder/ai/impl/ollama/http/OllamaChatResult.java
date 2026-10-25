package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.http;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;

/**
 * One chat completion result.
 *
 * A plain class rather than a record, per the project convention. Token counts are nullable Integer, not int: an
 * endpoint that reports no usage must be distinguishable from one reporting zero, because the estimator calibrates only
 * on real numbers.
 */
public class OllamaChatResult {

    private final String assistantText;
    private final List<ChatToolCall> toolCalls;
    private final String finishReason;
    private final Integer promptTokens;
    private final Integer completionTokens;

    public OllamaChatResult(String assistantText, List<ChatToolCall> toolCalls, String finishReason) {
        this(assistantText, toolCalls, finishReason, null, null);
    }

    public OllamaChatResult(String assistantText, List<ChatToolCall> toolCalls, String finishReason,
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
