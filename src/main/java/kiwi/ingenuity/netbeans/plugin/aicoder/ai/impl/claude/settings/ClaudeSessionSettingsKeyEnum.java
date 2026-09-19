package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

/**
 * Per-session config keys specific to {@link ClaudeSessionSettings}. The model itself uses the shared
 * {@code AiModelSessionSettingsKeyEnum.MODEL} key already handled by the {@code AiModelSessionSettings} base class.
 */
public enum ClaudeSessionSettingsKeyEnum {
    EFFORT("effort");

    private final String key;

    ClaudeSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
