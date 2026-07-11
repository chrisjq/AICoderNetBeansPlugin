package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

/**
 * Enumerates configuration keys for AiModelSessionSettings. Defines JSON config
 * keys specific to model-based AI session settings.
 */
public enum AiModelSessionSettingsKeyEnum {
    /**
     * The primary AI model to use
     */
    MODEL("model"),;

    /**
     * The JSON key string
     */
    private final String key;

    AiModelSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
