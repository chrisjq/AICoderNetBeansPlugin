package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.ASSUMED;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.LLAMACPP_N_CTX;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.LMSTUDIO_LOADED;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.OLLAMA_NUM_CTX;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.OLLAMA_PS;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.OVERRIDE;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.SERVER_ERROR;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.UPPER_BOUND;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.V1_MODELS;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading.Source.VLLM_MAX_MODEL_LEN;

/**
 * Picks the model's context window from the sources in spec §3.6 precedence order, and separately tracks whether a
 * shrink between real readings should be trusted (only after two consecutive smaller readings for the same model).
 *
 * <p>
 * Precedence, first available wins: the user's override; Ollama {@code /api/ps}; Ollama {@code num_ctx}; LM Studio
 * loaded; llama.cpp {@code n_ctx}; vLLM {@code max_model_len}; a window learned from an overflow error; a
 * {@code /v1/models} field; an upper bound × training factor; the assumed window. A learned error value outranks a
 * {@code /v1/models} field (a later real reading from steps 2-5 replaces the learned value, but a step-6 value does
 * not). Only steps 1-7 count as confirmed.
 */
public final class WindowLadder {

    private static final String GENERIC = "GENERIC";

    /**
     * The default number of consecutive smaller readings before a shrink is trusted (spec §8.2).
     */
    public static final int DEFAULT_READINGS_BEFORE_SHRINK = 2;

    private final int readingsBeforeShrink;
    private String model;
    private Integer current;
    private int smallerReadings;

    /**
     * A fresh shrink tracker with no remembered reading, trusting a shrink after the default two readings.
     */
    public WindowLadder() {
        this(DEFAULT_READINGS_BEFORE_SHRINK);
    }

    /**
     * @param readingsBeforeShrink how many consecutive smaller readings for the same model are needed before the
     * window shrinks; 1 or less trusts a shrink at once
     */
    public WindowLadder(int readingsBeforeShrink) {
        this.readingsBeforeShrink = Math.max(1, readingsBeforeShrink);
    }

    /**
     * @param override the user's context-window override, or {@code null}/0 for auto
     * @param sources the detected readings, or {@code null} when nothing was detected yet
     * @param learnedFromError a window learned from an overflow error for the current model, or {@code null}
     * @param assumed the assumed window used when nothing else is available
     * @param trainingFactor multiplier applied to an upper bound (e.g. 0.9)
     *
     * @return the winning reading, never {@code null}
     */
    public static WindowReading resolve(Integer override, WindowSources sources, Integer learnedFromError,
                                        int assumed, double trainingFactor) {
        if (positive(override) != null) {
            return new WindowReading(override, OVERRIDE, true);
        }
        if (sources != null) {
            Integer ps = positive(sources.ollamaPsContextLength());
            if (ps != null) {
                return new WindowReading(ps, OLLAMA_PS, true);
            }
            Integer numCtx = positive(sources.ollamaNumCtx());
            if (numCtx != null) {
                return new WindowReading(numCtx, OLLAMA_NUM_CTX, true);
            }
            Integer loaded = positive(sources.lmStudioLoadedContextLength());
            if (loaded != null) {
                return new WindowReading(loaded, LMSTUDIO_LOADED, true);
            }
            Integer llamaCpp = positive(sources.llamaCppNCtx());
            if (llamaCpp != null) {
                return new WindowReading(llamaCpp, LLAMACPP_N_CTX, true);
            }
            Integer vllm = positive(sources.vllmMaxModelLen());
            if (vllm != null) {
                return new WindowReading(vllm, VLLM_MAX_MODEL_LEN, true);
            }
        }
        Integer learned = positive(learnedFromError);
        if (learned != null) {
            return new WindowReading(learned, SERVER_ERROR, true);
        }
        if (sources != null) {
            Integer v1 = positive(sources.v1ModelsWindow());
            if (v1 != null) {
                return new WindowReading(v1, V1_MODELS, true);
            }
            Integer bound = firstPositive(sources.ollamaTrainingContextLength(),
                                          sources.lmStudioMaxContextLength(),
                                          sources.llamaCppTrainContextLength());
            if (bound != null) {
                int scaled = (int) Math.round(bound * trainingFactor);
                return new WindowReading(Math.max(1, scaled), UPPER_BOUND, false);
            }
        }
        return new WindowReading(Math.max(1, assumed), ASSUMED, false);
    }

    /**
     * @return true when the kind has any window source at all, so automatic compaction can run. False for a GENERIC
     * server whose {@code /v1/models} carried no window field — its only possible source.
     */
    public static boolean hasAnySource(WindowSources sources) {
        if (sources == null || sources.kindName() == null) {
            return false;
        }
        if (GENERIC.equalsIgnoreCase(sources.kindName())) {
            return sources.v1ModelsHasWindowField();
        }
        return true;
    }

    /**
     * Records a real reading for {@code model} and returns the window to actually use, applying the two-readings rule:
     * a smaller value than the current one only replaces it when a second consecutive smaller reading arrives for the
     * same model (spec §3.6). A change of model, or any non-smaller reading, resets the pending shrink.
     *
     * @return the window to use after this reading
     */
    public int record(String model, int reading) {
        if (this.model == null || !this.model.equals(model)) {
            this.model = model;
            this.current = reading;
            this.smallerReadings = 0;
            return reading;
        }
        if (reading < current) {
            smallerReadings++;
            if (smallerReadings < readingsBeforeShrink) {
                return current;
            }
            current = reading;
            smallerReadings = 0;
            return reading;
        }
        current = reading;
        smallerReadings = 0;
        return reading;
    }

    /**
     * @return the window currently in force, or {@code null} before the first reading.
     */
    public Integer current() {
        return current;
    }

    /**
     * Forgets the remembered model and reading (spec §3.7 settings change).
     */
    public void reset() {
        model = null;
        current = null;
        smallerReadings = 0;
    }

    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private static Integer firstPositive(Integer... values) {
        for (Integer value : values) {
            Integer positive = positive(value);
            if (positive != null) {
                return positive;
            }
        }
        return null;
    }
}
