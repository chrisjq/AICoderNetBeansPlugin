package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings;

public enum OpenCodePluginSettingsKeyEnum {
    EXECUTABLE("ai.opencode.executable"),
    MODEL("ai.opencode.model"),
    MODE("ai.opencode.mode");

    private final String key;

    OpenCodePluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
