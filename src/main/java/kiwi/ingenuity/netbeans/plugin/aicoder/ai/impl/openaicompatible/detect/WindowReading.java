package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

/**
 * One candidate for a model's context window, with where it came from and whether it is confirmed (spec §3.6).
 *
 * <p>
 * {@link Source} is listed in the ladder's precedence order. Steps 1-7 confirm the window; upper bounds and the assumed
 * fallback do not, so a caller knows whether it may rely on the value or must leave the window unconfirmed.
 */
public record WindowReading(int tokens, Source source, boolean confirmed) {

    /**
     * Where a window reading came from, most authoritative first.
     */
    public enum Source {
        /**
         * The user's explicit override in session settings (0 = auto never produces this).
         */
        OVERRIDE,
        /**
         * Ollama {@code /api/ps} loaded {@code context_length}.
         */
        OLLAMA_PS,
        /**
         * Ollama {@code /api/show} {@code num_ctx} in {@code parameters}, before the model is loaded.
         */
        OLLAMA_NUM_CTX,
        /**
         * LM Studio loaded context length.
         */
        LMSTUDIO_LOADED,
        /**
         * llama.cpp {@code default_generation_settings.n_ctx}.
         */
        LLAMACPP_N_CTX,
        /**
         * vLLM {@code max_model_len}.
         */
        VLLM_MAX_MODEL_LEN,
        /**
         * A {@code /v1/models} window field ({@code context_length}, {@code context_window}, {@code max_model_len},
         * {@code meta.n_ctx}).
         */
        V1_MODELS,
        /**
         * Learned from an overflow error (session decision, spec §3.6 step 7).
         */
        SERVER_ERROR,
        /**
         * An upper bound (training length / {@code max_context_length}) scaled by the training factor.
         */
        UPPER_BOUND,
        /**
         * The assumed window when nothing else is known.
         */
        ASSUMED
    }
}
