package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

public enum OllamaPluginSettingsKeyEnum {
    MODEL("ai.ollama_local.model"),
    BASE_URL("ai.ollama_local.baseUrl"),
    REASONING_EFFORT("ai.ollama_local.reasoningEffort");

    private final String key;

    OllamaPluginSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
