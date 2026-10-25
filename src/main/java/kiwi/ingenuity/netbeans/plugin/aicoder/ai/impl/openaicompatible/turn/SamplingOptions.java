package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

/**
 * Optional sampling controls for an OpenAI-compatible request. Null values defer to the server defaults.
 */
public record SamplingOptions(Double temperature, Double topP, Integer maxOutputTokens, String reasoningEffort) {

    private static final SamplingOptions NONE = new SamplingOptions(null, null, null, null);

    public static SamplingOptions none() {
        return NONE;
    }
}
