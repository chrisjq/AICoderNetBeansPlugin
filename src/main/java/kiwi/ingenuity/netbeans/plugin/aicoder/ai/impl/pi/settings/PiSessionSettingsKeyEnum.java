package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings;

/**
 * Per-session config keys specific to {@link PiSessionSettings}. The model itself uses the shared
 * {@code AiModelSessionSettingsKeyEnum.MODEL} key already handled by the {@code AiModelSessionSettings} base class.
 */
public enum PiSessionSettingsKeyEnum {
    PI_SESSION_ID("piSessionId"),
    THINKING_LEVEL("thinkingLevel");

    private final String key;

    PiSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
