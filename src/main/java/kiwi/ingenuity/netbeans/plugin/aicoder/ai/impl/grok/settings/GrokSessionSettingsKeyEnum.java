package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

/**
 * Per-session config keys specific to {@link GrokSessionSettings}. The model itself uses the shared
 * {@code AiModelSessionSettingsKeyEnum.MODEL} key already handled by the {@code AiModelSessionSettings} base class.
 */
public enum GrokSessionSettingsKeyEnum {
    REASONING_EFFORT("reasoningEffort");

    private final String key;

    GrokSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
