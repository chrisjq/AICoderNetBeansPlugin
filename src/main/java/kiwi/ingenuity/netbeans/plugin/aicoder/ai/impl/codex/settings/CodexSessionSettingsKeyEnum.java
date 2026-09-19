package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

/**
 * Codex-specific field names inside a session's persisted {@code config} object, alongside the shared ones in
 * {@code AiSessionSettingsKeyEnum}.
 * <p>
 * <b>On-disk values — do not change them.</b> A rename does not fail; the field reads back absent and the session
 * silently starts a fresh Codex thread instead of resuming the saved one, losing the backend conversation.
 * <p>
 * Mirrors {@code OllamaSessionSettingsKeyEnum}. These keys were the subclass writing raw literals while its own base
 * class used the enum.
 */
public enum CodexSessionSettingsKeyEnum {
    /**
     * Codex thread id, used to resume the backend conversation across restarts.
     */
    THREAD_ID("threadId"),
    /**
     * Reasoning-effort override, sent per turn as the {@code turn/start} parameter {@code effort}. {@code null} omits
     * the field ("model default").
     */
    EFFORT("effort");

    private final String key;

    CodexSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
