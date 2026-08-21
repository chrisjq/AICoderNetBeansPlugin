package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings;

/**
 * OpenCode-specific field names inside a session's persisted {@code config}
 * object, alongside the shared ones in {@code AiSessionSettingsKeyEnum}.
 * <p>
 * <b>On-disk values — do not change them.</b> A rename does not fail; the field
 * reads back absent and the session quietly reverts to defaults, losing the
 * saved mode, effort or resumable ACP session id.
 * <p>
 * Mirrors {@code OllamaSessionSettingsKeyEnum}; these three keys were the
 * subclass writing raw literals while its own base class used the enum.
 */
public enum OpenCodeSessionSettingsKeyEnum {
    /**
     * Selected OpenCode agent/mode, e.g. build or plan.
     */
    MODE("mode"),
    /**
     * Reasoning-effort option, where the model exposes one.
     */
    EFFORT("effort"),
    /**
     * ACP session id, used to resume the backend conversation across restarts.
     */
    ACP_SESSION_ID("acpSessionId");

    private final String key;

    OpenCodeSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
