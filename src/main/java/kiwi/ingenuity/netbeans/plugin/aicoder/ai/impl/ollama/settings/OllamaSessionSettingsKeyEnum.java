package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

public enum OllamaSessionSettingsKeyEnum {
    BASE_URL("baseUrl"),
    REASONING_EFFORT("reasoningEffort"),
    USE_NATIVE_TOOL_CALLING("useNativeToolCalling");

    private final String key;

    OllamaSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
