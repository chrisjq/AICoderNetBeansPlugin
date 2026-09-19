package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

public enum CodexPluginSettingsKeyEnum {
    EXECUTABLE("ai.codex.executable"),
    MODEL("ai.codex.model"),
    EFFORT("ai.codex.effort");

    private final String key;

    CodexPluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
