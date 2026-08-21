package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

/**
 * Codex-specific field names inside a session's persisted {@code config}
 * object, alongside the shared ones in {@code AiSessionSettingsKeyEnum}.
 * <p>
 * <b>On-disk value — do not change it.</b> A rename does not fail; the field
 * reads back absent and the session silently starts a fresh Codex thread
 * instead of resuming the saved one, losing the backend conversation.
 * <p>
 * Mirrors {@code OllamaSessionSettingsKeyEnum}. This key was the subclass
 * writing a raw literal while its own base class used the enum.
 */
public enum CodexSessionSettingsKeyEnum {
    /**
     * Codex thread id, used to resume the backend conversation across restarts.
     */
    THREAD_ID("threadId");

    private final String key;

    CodexSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
