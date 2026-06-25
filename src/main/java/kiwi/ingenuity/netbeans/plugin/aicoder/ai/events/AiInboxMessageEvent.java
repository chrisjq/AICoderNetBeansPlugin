package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

public record AiInboxMessageEvent(String targetSessionId, String messageId, String subject, String fromName) implements AiPropertyEvent {

}
