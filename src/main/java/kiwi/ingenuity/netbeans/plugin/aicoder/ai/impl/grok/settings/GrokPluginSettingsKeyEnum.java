package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

public enum GrokPluginSettingsKeyEnum {
    MODEL("ai.grok.model"),
    EXECUTABLE("ai.grok.executable");

    private final String key;

    GrokPluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
