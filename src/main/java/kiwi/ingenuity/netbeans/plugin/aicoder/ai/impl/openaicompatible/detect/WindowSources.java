package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

/**
 * The window readings a detected-facts snapshot can offer the ladder (spec §3.6). Each getter returns {@code null} when
 * that source produced nothing yet, so a source that has not answered is indistinguishable from one that does not apply
 * — the caller decides using {@link #kindName()} and {@link #v1ModelsHasWindowField()}.
 *
 * <p>
 * This is deliberately a narrow interface so {@code WindowLadder} does not depend on the full {@code DetectedFacts}
 * record; that record (or a binder) is expected to implement it. The kind is exposed as its enum name so this task does
 * not depend on {@code ServerKind}, which is written in parallel.
 */
public interface WindowSources {

    /**
     * The detected server kind's name (e.g. {@code "OLLAMA"}, {@code "GENERIC"}), or {@code null} if unknown.
     */
    String kindName();

    /**
     * Step 2a: Ollama {@code /api/ps} loaded {@code context_length}, or {@code null}.
     */
    default Integer ollamaPsContextLength() {
        return null;
    }

    /**
     * Step 2b: Ollama {@code /api/show} {@code num_ctx}, or {@code null}.
     */
    default Integer ollamaNumCtx() {
        return null;
    }

    /**
     * Step 3: LM Studio loaded context length, or {@code null}.
     */
    default Integer lmStudioLoadedContextLength() {
        return null;
    }

    /**
     * Step 4: llama.cpp {@code n_ctx}, or {@code null}.
     */
    default Integer llamaCppNCtx() {
        return null;
    }

    /**
     * Step 5: vLLM {@code max_model_len}, or {@code null}.
     */
    default Integer vllmMaxModelLen() {
        return null;
    }

    /**
     * Step 6: a {@code /v1/models} window field, or {@code null}.
     */
    default Integer v1ModelsWindow() {
        return null;
    }

    /**
     * Step 8: Ollama training context length (an upper bound), or {@code null}.
     */
    default Integer ollamaTrainingContextLength() {
        return null;
    }

    /**
     * Step 8: LM Studio {@code max_context_length} (an upper bound), or {@code null}.
     */
    default Integer lmStudioMaxContextLength() {
        return null;
    }

    /**
     * Step 8: llama.cpp {@code meta.n_ctx_train} (an upper bound), or {@code null}.
     */
    default Integer llamaCppTrainContextLength() {
        return null;
    }

    /**
     * True when the {@code /v1/models} response carried any window field, whether or not it was usable. Distinct from
     * {@link #v1ModelsWindow()} so a GENERIC server that answered but had no window field is still "no source".
     */
    default boolean v1ModelsHasWindowField() {
        return v1ModelsWindow() != null;
    }
}
