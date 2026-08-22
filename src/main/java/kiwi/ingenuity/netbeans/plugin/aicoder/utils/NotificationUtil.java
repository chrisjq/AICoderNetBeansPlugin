package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiInboxMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.userinput.AskUserQuestionParamEnum;

public class NotificationUtil {

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

        // The header alone reads as an FYI. Three sessions received one, replied "I
        // am ready to execute it" in their own chat, and ended the turn without ever
        // calling ReadAiMessage — so the notification has to name the tools.
        //
        // The names come from McpToolEnum rather than being retyped: this text is
        // an instruction the AI acts on, so if a tool were renamed the notice
        // would send it after a tool that no longer exists, and we would be back
        // to the turn ending with the message unread.
        notifBuilder.append(" — read it with ")
                .append(McpToolEnum.READ_AI_MESSAGE.toolName()).append('.');

        return notifBuilder.toString();
    }

    public static String formatReplyExpectedInstruction() {
        return "A response must be sent to this message with "
                + McpToolEnum.SEND_AI_MESSAGE.toolName() + '.';
    }

    // Chat system messages
    public static String formatInboxMessage(String senderName, String subject) {
        return "New message from [" + senderName + "]: " + subject;
    }

    public static String formatAnswer(String answer) {
        return "Answer: " + answer.replace("\n", " | ");
    }

    /**
     * A question that was shown but never answered — the tool timed out or the turn was cancelled. Recorded in history
     * (unlike the live question panel, which is transient) so the conversation shows it was asked and left unanswered.
     */
    public static String formatUnansweredQuestion(com.google.gson.JsonArray questions) {
        StringBuilder sb = new StringBuilder("Question not answered (timed out or cancelled)");
        if (questions != null) {
            String joined = questions.asList().stream()
                    .filter(com.google.gson.JsonElement::isJsonObject)
                    .map(com.google.gson.JsonElement::getAsJsonObject)
                    .filter(q -> q.has(AskUserQuestionParamEnum.QUESTION.key()))
                    .map(q -> q.get(AskUserQuestionParamEnum.QUESTION.key()).getAsString())
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("");
            if (!joined.isBlank()) {
                sb.append(": ").append(joined);
            }
        }
        return sb.toString();
    }

    public static String formatFileAcceptedTool(String toolName, String shortPath) {
        return toolNameLabel(toolName) + formatFileAccepted(shortPath);
    }

    public static String formatFileRejectedTool(String toolName, String shortPath) {
        return toolNameLabel(toolName) + formatFileRejected(shortPath);
    }

    public static String formatFileRejectedTool(String toolName, String shortPath, String message) {
        return toolNameLabel(toolName) + formatFileRejected(shortPath, message);
    }

    private static String formatFileAccepted(String shortPath) {
        return shortPath + " — accepted";
    }

    private static String formatFileRejected(String shortPath) {
        return formatFileRejected(shortPath, null);
    }

    private static String formatFileRejected(String shortPath, String message) {
        return message != null && !message.isBlank()
                ? shortPath + " — rejected: " + message.trim()
                : shortPath + " — rejected";
    }

    public static String formatAutoAccepted(String toolName, String shortPath) {
        return toolNameLabel(toolName) + shortPath + " — auto-accepted";
    }

    public static String formatToolActionQuestion(String toolName, String shortPath) {
        return toolNameLabel(toolName) + shortPath + " ?";
    }

    public static String formatPermissionDenied(String toolName, String shortPath, String reason) {
        String path = shortPath != null && !shortPath.isBlank() ? shortPath : "(unknown file)";
        String detail = reason != null && !reason.isBlank() ? reason : "permission denied";
        return toolNameLabel(toolName) + path + " — denied (" + detail + ")";
    }

    public static String toolNameLabel(String toolName) {
        if (toolName != null && !toolName.isEmpty()) {
            return toolName + ": ";
        }

        return "";
    }

    private NotificationUtil() {
    }
}
