package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;

public class NotificationUtil {

    private NotificationUtil() {
    }

    public static String formatInboxNotification(AiInboxMessage message, String senderName) {
        StringBuilder notifBuilder = new StringBuilder();
        notifBuilder.append("id=").append(message.id());

        String replyToId = message.replyToId();
        if (replyToId != null && !replyToId.isBlank()) {
            notifBuilder.append(", replyToId=").append(replyToId);
        }

        notifBuilder.append(", from=").append(senderName)
                .append(" (").append(message.fromSessionId()).append(")");
        notifBuilder.append(", replyExpected=").append(message.expectsReply() ? "Yes" : "No");

        String subject = message.subject();
        if (subject != null && !subject.isBlank()) {
            notifBuilder.append(", Subject=").append(subject);
        }

        return notifBuilder.toString();
    }

    // Chat system messages
    public static String formatInboxMessage(String senderName, String subject) {
        return "New message from [" + senderName + "]: " + subject;
    }

    public static String formatAnswer(String answer) {
        return "Answer: " + answer.replace("\n", " | ");
    }

    public static String formatEdit(String shortPath) {
        return "Edit: " + shortPath;
    }

    public static String formatFileAccepted(String shortPath) {
        return shortPath + " — accepted";
    }

    public static String formatFileRejected(String shortPath) {
        return shortPath + " — rejected";
    }

    public static String formatAutoAccepted(String toolName, String shortPath) {
        return toolName + ": " + shortPath + " — auto-accepted";
    }

    public static String formatToolAction(String toolName, String shortPath) {
        return toolName + ": " + shortPath;
    }
}
