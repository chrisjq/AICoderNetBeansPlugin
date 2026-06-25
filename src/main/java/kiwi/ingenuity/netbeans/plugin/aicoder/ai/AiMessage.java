package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

public record AiMessage(Role role, String markdownText, long timestamp, boolean restored) {

    public static AiMessage user(String text) {
        return new AiMessage(Role.USER, text, System.currentTimeMillis(), false);
    }

    public static AiMessage assistant(String text) {
        return new AiMessage(Role.ASSISTANT, text, System.currentTimeMillis(), false);
    }

    public static AiMessage system(String text) {
        return new AiMessage(Role.SYSTEM, text, System.currentTimeMillis(), false);
    }

    public static AiMessage restoredUser(String text, long ts) {
        return new AiMessage(Role.USER, text, ts, true);
    }

    public static AiMessage restoredAssistant(String text, long ts) {
        return new AiMessage(Role.ASSISTANT, text, ts, true);
    }

    public static AiMessage restoredSystem(String text, long ts) {
        return new AiMessage(Role.SYSTEM, text, ts, true);
    }

    public enum Role {
        USER, ASSISTANT, SYSTEM
    }
}
