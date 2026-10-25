package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

/**
 * JSON field names (and Ollama capability tokens) used by probe parsers. Values are the wire spellings.
 */
public enum ServerFieldEnum {
    MODELS("models"),
    DETAILS("details"),
    NAME("name"),
    MODEL("model"),
    CAPABILITIES("capabilities"),
    MODEL_INFO("model_info"),
    CONTEXT_LENGTH("context_length"),
    PARAMETERS("parameters"),
    DATA("data"),
    KEY("key"),
    ID("id"),
    STATE("state"),
    MAX_CONTEXT_LENGTH("max_context_length"),
    LOADED_INSTANCES("loaded_instances"),
    CONFIG("config"),
    TRAINED_FOR_TOOL_USE("trained_for_tool_use"),
    LOADED_CONTEXT_LENGTH("loaded_context_length"),
    DEFAULT_GENERATION_SETTINGS("default_generation_settings"),
    N_CTX("n_ctx"),
    CONTEXT_WINDOW("context_window"),
    MAX_MODEL_LEN("max_model_len"),
    META("meta"),
    N_CTX_TRAIN("n_ctx_train"),
    TOOLS("tools"),
    THINKING("thinking");

    private final String key;

    ServerFieldEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
