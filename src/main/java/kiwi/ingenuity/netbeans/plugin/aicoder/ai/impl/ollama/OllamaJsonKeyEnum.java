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
    MODEL("model");
    // Deliberately no NAME constant. The tool-schema "name" that
    // OllamaAiProcessManager reads belongs to OUR schemas, written by
    // McpToolSchemas with ToolSchemaKeyEnum, and is read back through that same
    // enum. Declaring it here would be a second source of truth for one
    // contract: renaming ToolSchemaKeyEnum.NAME would still compile while
    // Ollama silently stopped recognising any tool.

    private final String key;

    OllamaJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
