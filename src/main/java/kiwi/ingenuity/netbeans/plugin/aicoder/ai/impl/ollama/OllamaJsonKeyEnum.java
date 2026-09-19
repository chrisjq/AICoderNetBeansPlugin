package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

/**
 * JSON keys for Ollama's OpenAI-compatible model and native capability APIs. Similar spellings in other API enums
 * remain separate server contracts.
 */
public enum OllamaJsonKeyEnum {

    /**
     * Array of OpenAI-compatible model entries.
     */
    DATA("data"),
    /**
     * Identifier of one model entry.
     */
    ID("id"),
    /**
     * Array of Ollama model capabilities.
     */
    CAPABILITIES("capabilities"),
    /**
     * Model selected in the native /api/show request.
     */
    MODEL("model"),
    /**
     * Array of per-model entries in the native GET /api/tags response.
     */
    MODELS("models"),
    /**
     * A model's identifier in a native /api/tags entry. Distinct from {@link #ID} (the OpenAI-compatible /v1/models
     * shape) and unrelated to the tool-schema "name" note below — this is Ollama's own native model list, a different
     * JSON object from either.
     */
    NAME("name");
    // No tool-schema NAME constant here. The tool-schema "name" that
    // OllamaAiProcessManager reads belongs to OUR schemas, written by
    // McpToolSchemas with ToolSchemaKeyEnum, and is read back through that same
    // enum. Declaring it here would be a second source of truth for one
    // contract: renaming ToolSchemaKeyEnum.NAME would still compile while
    // Ollama silently stopped recognising any tool. NAME above is a completely
    // different contract (Ollama's own /api/tags model list), so it is safe.

    private final String key;

    OllamaJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
