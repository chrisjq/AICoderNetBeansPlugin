package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

public enum GithubCopilotPluginSettingsKeyEnum {
    EXECUTABLE("ai.githubcopilot.executable"),
    MODEL("ai.githubcopilot.model");

    private final String key;

    GithubCopilotPluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
