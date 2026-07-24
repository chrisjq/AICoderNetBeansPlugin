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

    /**
     * A question that was shown but never answered — the tool timed out or the
     * turn was cancelled. Recorded in history (unlike the live question panel,
     * which is transient) so the conversation shows it was asked and left
     * unanswered.
     */
    public static String formatUnansweredQuestion(com.google.gson.JsonArray questions) {
        StringBuilder sb = new StringBuilder("Question not answered (timed out or cancelled)");
        if (questions != null) {
            String joined = questions.asList().stream()
                    .filter(com.google.gson.JsonElement::isJsonObject)
                    .map(com.google.gson.JsonElement::getAsJsonObject)
                    .filter(q -> q.has("question"))
                    .map(q -> q.get("question").getAsString())
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("");
            if (!joined.isBlank()) {
                sb.append(": ").append(joined);
            }
        }
        return sb.toString();
    }

    public static String formatEdit(String shortPath) {
        return "Edit: " + shortPath;
    }

    public static String formatFileAccepted(String shortPath) {
        return shortPath + " — accepted";
    }

    public static String formatFileRejected(String shortPath) {
        return formatFileRejected(shortPath, null);
    }

    public static String formatFileRejected(String shortPath, String message) {
        return message != null && !message.isBlank()
                ? shortPath + " — rejected: " + message.trim()
                : shortPath + " — rejected";
    }

    public static String formatAutoAccepted(String toolName, String shortPath) {
        return toolName + ": " + shortPath + " — auto-accepted";
    }

    public static String formatToolAction(String toolName, String shortPath) {
        return toolName + ": " + shortPath;
    }

    public static String formatPermissionDenied(String shortPath, String reason) {
        String path = shortPath != null && !shortPath.isBlank() ? shortPath : "(unknown file)";
        String detail = reason != null && !reason.isBlank() ? reason : "permission denied";
        return path + " — denied (" + detail + ")";
    }
}
