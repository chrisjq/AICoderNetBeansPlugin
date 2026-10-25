package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

/**
 * Where the context window came from, in words for the user (the gauge tooltip and the Detected panel).
 */
public final class WindowSourceText {

    private WindowSourceText() {
    }

    /**
     * @return a short phrase for the source; "assumed" when the source is unknown
     */
    public static String friendly(WindowReading.Source source) {
        if (source == null) {
            return "assumed";
        }
        return switch (source) {
            case OVERRIDE ->
                "override";
            case OLLAMA_PS ->
                "Ollama loaded model";
            case OLLAMA_NUM_CTX ->
                "Ollama model settings";
            case LMSTUDIO_LOADED ->
                "LM Studio loaded model";
            case LLAMACPP_N_CTX ->
                "llama.cpp settings";
            case VLLM_MAX_MODEL_LEN ->
                "vLLM settings";
            case V1_MODELS ->
                "server model metadata";
            case SERVER_ERROR ->
                "server error";
            case UPPER_BOUND ->
                "model upper bound";
            case ASSUMED ->
                "assumed";
        };
    }
}
