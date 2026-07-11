package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

public record PermissionDecision(boolean allow, String message) {

    public static PermissionDecision allowed() {
        return new PermissionDecision(true, null);
    }

    public static PermissionDecision denied(String message) {
        return new PermissionDecision(false, message);
    }

    public String effectiveDenyMessage(String fallback) {
        return message != null && !message.isBlank() ? message.trim() : fallback;
    }
}
