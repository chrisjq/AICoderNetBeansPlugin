package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;

/**
 * One assembled chat completion, from SSE chunks or a single non-streaming JSON body.
 */
public record AssembledReply(
        String text,
        List<ChatToolCall> toolCalls,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        boolean endedCleanly,
        boolean sawOutput,
        String reasoning) {

    public AssembledReply {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        reasoning = reasoning == null ? "" : reasoning;
    }

    /**
     * Compatibility constructor for callers that do not supply reasoning.
     */
    public AssembledReply(String text, List<ChatToolCall> toolCalls, String finishReason,
                          Integer promptTokens, Integer completionTokens,
                          boolean endedCleanly, boolean sawOutput) {
        this(text, toolCalls, finishReason, promptTokens, completionTokens, endedCleanly, sawOutput, "");
    }
}
