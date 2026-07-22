package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.util.List;

public record ChatMessage(ChatRole role, String content,
        List<ChatToolCall> toolCalls, String toolCallId) {

}
