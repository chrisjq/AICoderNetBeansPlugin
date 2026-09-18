package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings;

public enum PiPluginSettingsKeyEnum {
    EXECUTABLE("ai.pi.executable"),
    MODEL("ai.pi.model"),
    THINKING_LEVEL("ai.pi.thinkingLevel"),
    DISCOVERED_MODELS("ai.pi.discoveredModels"),
    VERIFIED_VERSION("ai.pi.verifiedVersion");

    private final String key;

    PiPluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
