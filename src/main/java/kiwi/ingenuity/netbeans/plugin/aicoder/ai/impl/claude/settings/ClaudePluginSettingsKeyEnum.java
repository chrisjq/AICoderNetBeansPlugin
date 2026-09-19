package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

public enum ClaudePluginSettingsKeyEnum {
    EXECUTABLE("ai.claude.executable"),
    MODEL("ai.claude.model"),
    EFFORT("ai.claude.effort");

    private final String key;

    ClaudePluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
