package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

public enum ClaudePluginSettingsKeyEnum {
    EXECUTABLE("ai.claude.executable"),
    MODEL("ai.claude.model");

    private final String key;

    ClaudePluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
