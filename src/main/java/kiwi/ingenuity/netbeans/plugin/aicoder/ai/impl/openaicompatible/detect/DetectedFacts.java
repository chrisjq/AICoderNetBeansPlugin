package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable detection snapshot for one config generation (spec §3.5). Written only by detection. A request reads it
 * once, together with {@link SessionDecisions}, and discards a snapshot whose {@link #generation()} is not the
 * session's current generation.
 * <p>
 * {@link #generation(String, String, String)} is {@code root + newline + model + newline + keyHash}. {@code withX}
 * returns a copy. This record implements {@link WindowSources} so the window ladder can read it without a second type.
 * {@code probesUnauthorized} is true when every probe in the cycle was refused with a 401, so the server needs a key.
 */
public record DetectedFacts(String generation, ServerKind kind, String apiBase, Boolean streaming,
                            Capabilities capabilities, List<WindowReading> windowReadings, boolean v1WindowFieldPresent,
                            Integer ollamaTrainingContextLength, Integer lmStudioMaxContextLength, Integer llamaCppTrainContextLength,
                            List<String> models, Instant timestamp, boolean probesUnauthorized) implements WindowSources {

    /**
     * Tools and thinking support. {@code null} means the probe has not said.
     */
    public record Capabilities(Boolean tools, Boolean thinking, List<String> reasoningLevels) {

        public Capabilities(Boolean tools, Boolean thinking) {
            this(tools, thinking, null);
        }

        public Capabilities {
            reasoningLevels = reasoningLevels == null ? null : List.copyOf(reasoningLevels);
        }

        public static Capabilities unknown() {
            return new Capabilities(null, null, null);
        }
    }

    public DetectedFacts {
        capabilities = capabilities == null ? Capabilities.unknown() : capabilities;
        windowReadings = copyReadings(windowReadings);
        models = copyModels(models);
    }

    /**
     * A snapshot with only the generation set. Detection fills the rest with {@code withX}.
     */
    public static DetectedFacts of(String generation) {
        return new DetectedFacts(generation, null, null, null, null, null, false, null, null, null, null, null, false);
    }

    /**
     * @return the config generation, {@code root}, {@code model} and {@code keyHash} joined by newlines
     */
    public static String generation(String root, String model, String keyHash) {
        return text(root) + "\n" + text(model) + "\n" + text(keyHash);
    }

    public DetectedFacts withGeneration(String generation) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withKind(ServerKind kind) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withApiBase(String apiBase) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withStreaming(Boolean streaming) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withCapabilities(Capabilities capabilities) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withWindowReadings(List<WindowReading> windowReadings) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withV1WindowFieldPresent(boolean v1WindowFieldPresent) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withOllamaTrainingContextLength(Integer ollamaTrainingContextLength) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withLmStudioMaxContextLength(Integer lmStudioMaxContextLength) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withLlamaCppTrainContextLength(Integer llamaCppTrainContextLength) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withModels(List<String> models) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withTimestamp(Instant timestamp) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    public DetectedFacts withProbesUnauthorized(boolean probesUnauthorized) {
        return new DetectedFacts(generation, kind, apiBase, streaming, capabilities, windowReadings,
                                 v1WindowFieldPresent, ollamaTrainingContextLength, lmStudioMaxContextLength,
                                 llamaCppTrainContextLength, models, timestamp, probesUnauthorized);
    }

    @Override
    public String kindName() {
        return kind == null ? null : kind.name();
    }

    @Override
    public Integer ollamaPsContextLength() {
        return reading(WindowReading.Source.OLLAMA_PS);
    }

    @Override
    public Integer ollamaNumCtx() {
        return reading(WindowReading.Source.OLLAMA_NUM_CTX);
    }

    @Override
    public Integer lmStudioLoadedContextLength() {
        return reading(WindowReading.Source.LMSTUDIO_LOADED);
    }

    @Override
    public Integer llamaCppNCtx() {
        return reading(WindowReading.Source.LLAMACPP_N_CTX);
    }

    @Override
    public Integer vllmMaxModelLen() {
        return reading(WindowReading.Source.VLLM_MAX_MODEL_LEN);
    }

    @Override
    public Integer v1ModelsWindow() {
        return reading(WindowReading.Source.V1_MODELS);
    }

    @Override
    public boolean v1ModelsHasWindowField() {
        return v1WindowFieldPresent || v1ModelsWindow() != null;
    }

    private Integer reading(WindowReading.Source source) {
        for (WindowReading reading : windowReadings) {
            if (reading.source() == source && reading.tokens() > 0) {
                return reading.tokens();
            }
        }
        return null;
    }

    private static List<WindowReading> copyReadings(List<WindowReading> readings) {
        if (readings == null || readings.isEmpty()) {
            return List.of();
        }
        List<WindowReading> copy = new ArrayList<>();
        for (WindowReading reading : readings) {
            if (reading != null) {
                copy.add(reading);
            }
        }
        return List.copyOf(copy);
    }

    private static List<String> copyModels(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>();
        for (String name : names) {
            if (name != null) {
                copy.add(name);
            }
        }
        return List.copyOf(copy);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
