package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

public enum GrokPluginSettingsKeyEnum {
    API_KEY("ai.grok.apikey"),
    MODEL("ai.grok.model");

    private final String key;

    GrokPluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
