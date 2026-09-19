package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

public enum GithubCopilotPluginSettingsKeyEnum {
    EXECUTABLE("ai.githubcopilot.executable"),
    MODEL("ai.githubcopilot.model"),
    REASONING_EFFORT("ai.githubcopilot.reasoningEffort");

    private final String key;

    GithubCopilotPluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
