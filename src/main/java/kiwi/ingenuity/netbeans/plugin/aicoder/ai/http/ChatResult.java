package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.util.List;

public record ChatResult(String assistantText, List<ChatToolCall> toolCalls,
        String finishReason) {

}
