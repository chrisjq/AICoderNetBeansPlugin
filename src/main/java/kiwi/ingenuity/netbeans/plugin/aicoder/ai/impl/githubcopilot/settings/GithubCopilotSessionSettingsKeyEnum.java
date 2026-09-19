package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

/**
 * Per-session config keys specific to {@link GithubCopilotSessionSettings}. The model itself uses the shared
 * {@code AiModelSessionSettingsKeyEnum.MODEL} key already handled by the {@code AiModelSessionSettings} base class.
 */
public enum GithubCopilotSessionSettingsKeyEnum {
    REASONING_EFFORT("reasoningEffort");

    private final String key;

    GithubCopilotSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
