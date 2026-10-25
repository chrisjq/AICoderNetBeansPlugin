package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ProbeParsersTest {

    @Test
    @Timeout(5)
    void ollamaTagsReadsSignatureAndNames() throws Exception {
        ProbeParsers.OllamaTagsResult result = ProbeParsers.ollamaTags(fixture("ollama-tags.json"));
        assertTrue(result.signature());
        assertEquals(List.of("llama3.2:latest", "qwen2.5-coder:7b"), result.modelNames());
    }

    @Test
    @Timeout(5)
    void ollamaTagsSignatureFalseOnOpenAiAndLmStudio() throws Exception {
        assertFalse(ProbeParsers.ollamaTags(fixture("openai-models.json")).signature());
        assertEquals(List.of(), ProbeParsers.ollamaTags(fixture("openai-models.json")).modelNames());
        assertFalse(ProbeParsers.ollamaTags(fixture("lmstudio-v1.json")).signature());
        assertFalse(ProbeParsers.ollamaTags(new JsonObject()).signature());
        assertFalse(ProbeParsers.ollamaTags(null).signature());
    }

    @Test
    @Timeout(5)
    void ollamaTagsSignatureFalseWhenDetailsMissing() {
        ProbeParsers.OllamaTagsResult result = ProbeParsers.ollamaTags(json("""
                {"models":[{"name":"plain","model":"plain"}]}
                """));
        assertFalse(result.signature());
        assertEquals(List.of("plain"), result.modelNames());
    }

    @Test
    @Timeout(5)
    void ollamaTagsFallsBackToModelWhenNameMissing() {
        ProbeParsers.OllamaTagsResult result = ProbeParsers.ollamaTags(json("""
                {"models":[{"model":"only-model","details":{}}]}
                """));
        assertTrue(result.signature());
        assertEquals(List.of("only-model"), result.modelNames());
    }

    @Test
    @Timeout(5)
    void ollamaShowReadsToolsThinkingTrainingAndNumCtx() throws Exception {
        ProbeParsers.OllamaShowResult result = ProbeParsers.ollamaShow(fixture("ollama-show.json"));
        assertTrue(result.tools());
        assertTrue(result.thinking());
        assertEquals(131072, result.trainingContextLength());
        assertEquals(8192, result.numCtx());
    }

    @Test
    @Timeout(5)
    void ollamaShowMissingFieldsAreFalseAndNull() {
        ProbeParsers.OllamaShowResult empty = ProbeParsers.ollamaShow(new JsonObject());
        assertFalse(empty.tools());
        assertFalse(empty.thinking());
        assertNull(empty.trainingContextLength());
        assertNull(empty.numCtx());
        ProbeParsers.OllamaShowResult none = ProbeParsers.ollamaShow(json("""
                {"capabilities":["completion"],"parameters":"stop <|eot_id|>","model_info":{"llama.embedding_length":4096}}
                """));
        assertFalse(none.tools());
        assertFalse(none.thinking());
        assertNull(none.trainingContextLength());
        assertNull(none.numCtx());
    }

    @Test
    @Timeout(5)
    void ollamaShowTakesMaxTrainingContextLengthAndParsesNumCtxCaseInsensitively() {
        ProbeParsers.OllamaShowResult result = ProbeParsers.ollamaShow(json("""
                {"capabilities":["tools"],"parameters":"NUM_CTX 4096","model_info":{
                  "context_length": 2048,
                  "llama.context_length": 8192,
                  "other.window": 99999
                }}
                """));
        assertTrue(result.tools());
        assertFalse(result.thinking());
        assertEquals(8192, result.trainingContextLength());
        assertEquals(4096, result.numCtx());
    }

    @Test
    @Timeout(5)
    void ollamaPsMatchesBareNameToLatest() throws Exception {
        JsonObject ps = fixture("ollama-ps.json");
        assertEquals(8192, ProbeParsers.ollamaPs(ps, "llama3.2"));
        assertEquals(8192, ProbeParsers.ollamaPs(ps, "llama3.2:latest"));
    }

    @Test
    @Timeout(5)
    void ollamaPsReturnsNullWhenModelMissing() throws Exception {
        JsonObject ps = fixture("ollama-ps.json");
        assertNull(ProbeParsers.ollamaPs(ps, "qwen2.5-coder"));
        assertNull(ProbeParsers.ollamaPs(ps, "llama3.2:7b"));
        assertNull(ProbeParsers.ollamaPs(fixture("openai-models.json"), "llama3.2"));
        assertNull(ProbeParsers.ollamaPs(new JsonObject(), "llama3.2"));
        assertNull(ProbeParsers.ollamaPs(null, "llama3.2"));
    }

    @Test
    @Timeout(5)
    void ollamaPsMatchesListedBareNameToRequestedLatest() {
        assertEquals(2048, ProbeParsers.ollamaPs(json("""
                {"models":[{"name":"mistral","context_length":2048}]}
                """), "mistral:latest"));
    }

    @Test
    @Timeout(5)
    void lmStudioV1ReadsLoadedUnloadedAndTools() throws Exception {
        JsonObject body = fixture("lmstudio-v1.json");
        ProbeParsers.LmStudioResult loaded = ProbeParsers.lmStudioV1(body, "loaded-model");
        assertTrue(loaded.signature());
        assertEquals(List.of("loaded-model", "unloaded-model"), loaded.modelIds());
        assertEquals(8192, loaded.loadedContextLength());
        assertEquals(131072, loaded.maxContextLength());
        assertEquals(Boolean.TRUE, loaded.tools());

        ProbeParsers.LmStudioResult unloaded = ProbeParsers.lmStudioV1(body, "unloaded-model");
        assertTrue(unloaded.signature());
        assertNull(unloaded.loadedContextLength());
        assertEquals(32768, unloaded.maxContextLength());
        assertEquals(Boolean.FALSE, unloaded.tools());
    }

    @Test
    @Timeout(5)
    void lmStudioV1SignatureFalseOnV0AndOpenAi() throws Exception {
        assertFalse(ProbeParsers.lmStudioV1(fixture("lmstudio-v0.json"), "loaded-model").signature());
        assertFalse(ProbeParsers.lmStudioV1(fixture("openai-models.json"), "gpt-4o").signature());
        assertFalse(ProbeParsers.lmStudioV1(new JsonObject(), "x").signature());
    }

    @Test
    @Timeout(5)
    void lmStudioV1UnknownModelLeavesWindowAndToolsNull() throws Exception {
        ProbeParsers.LmStudioResult result = ProbeParsers.lmStudioV1(fixture("lmstudio-v1.json"), "missing");
        assertTrue(result.signature());
        assertEquals(List.of("loaded-model", "unloaded-model"), result.modelIds());
        assertNull(result.loadedContextLength());
        assertNull(result.maxContextLength());
        assertNull(result.tools());
    }

    @Test
    @Timeout(5)
    void lmStudioV0ReadsLoadedWindowAndIgnoresUnloaded() throws Exception {
        JsonObject body = fixture("lmstudio-v0.json");
        ProbeParsers.LmStudioResult loaded = ProbeParsers.lmStudioV0(body, "loaded-model");
        assertTrue(loaded.signature());
        assertEquals(List.of("loaded-model", "unloaded-model"), loaded.modelIds());
        assertEquals(4096, loaded.loadedContextLength());
        assertEquals(131072, loaded.maxContextLength());
        assertNull(loaded.tools());

        ProbeParsers.LmStudioResult unloaded = ProbeParsers.lmStudioV0(body, "unloaded-model");
        assertTrue(unloaded.signature());
        assertNull(unloaded.loadedContextLength());
        assertEquals(32768, unloaded.maxContextLength());
        assertNull(unloaded.tools());
    }

    @Test
    @Timeout(5)
    void lmStudioV0SignatureFalseOnV1AndOpenAi() throws Exception {
        assertFalse(ProbeParsers.lmStudioV0(fixture("lmstudio-v1.json"), "loaded-model").signature());
        assertFalse(ProbeParsers.lmStudioV0(fixture("openai-models.json"), "gpt-4o").signature());
        assertFalse(ProbeParsers.lmStudioV0(new JsonObject(), "x").signature());
    }

    @Test
    @Timeout(5)
    void lmStudioV0IgnoresLoadedContextLengthWhenStateIsNotLoaded() {
        ProbeParsers.LmStudioResult result = ProbeParsers.lmStudioV0(json("""
                {"data":[{"id":"m","state":"not-loaded","max_context_length":32768,"loaded_context_length":4096}]}
                """), "m");
        assertTrue(result.signature());
        assertNull(result.loadedContextLength());
        assertEquals(32768, result.maxContextLength());
    }

    @Test
    @Timeout(5)
    void llamaCppPropsReadsNCtx() throws Exception {
        ProbeParsers.LlamaCppPropsResult result = ProbeParsers.llamaCppProps(fixture("llamacpp-props.json"));
        assertTrue(result.signature());
        assertEquals(4096, result.nCtx());
    }

    @Test
    @Timeout(5)
    void llamaCppPropsSignatureFalseWithoutNCtx() throws Exception {
        assertFalse(ProbeParsers.llamaCppProps(fixture("openai-models.json")).signature());
        assertNull(ProbeParsers.llamaCppProps(fixture("openai-models.json")).nCtx());
        assertFalse(ProbeParsers.llamaCppProps(json("""
                {"default_generation_settings":{}}
                """)).signature());
        assertFalse(ProbeParsers.llamaCppProps(new JsonObject()).signature());
        assertFalse(ProbeParsers.llamaCppProps(null).signature());
    }

    @Test
    @Timeout(5)
    void v1ModelsReadsVllmSignatureAndMaxModelLen() throws Exception {
        ProbeParsers.V1ModelsResult result = ProbeParsers.v1Models(fixture("vllm-models.json"),
                                                                   "meta-llama/Llama-3-8B");
        assertTrue(result.vllmSignature());
        assertEquals(List.of("meta-llama/Llama-3-8B"), result.ids());
        assertEquals(8192, result.maxModelLen());
        assertNull(result.contextLength());
        assertNull(result.contextWindow());
        assertNull(result.metaNCtx());
        assertNull(result.metaNCtxTrain());
        assertNull(result.maxContextLength());
    }

    @Test
    @Timeout(5)
    void v1ModelsSignatureFalseOnOpenAi() throws Exception {
        ProbeParsers.V1ModelsResult result = ProbeParsers.v1Models(fixture("openai-models.json"), "gpt-4o");
        assertFalse(result.vllmSignature());
        assertEquals(List.of("gpt-4o", "gpt-4o-mini"), result.ids());
        assertNull(result.contextLength());
        assertNull(result.contextWindow());
        assertNull(result.maxModelLen());
        assertNull(result.metaNCtx());
        assertNull(result.metaNCtxTrain());
        assertNull(result.maxContextLength());
    }

    @Test
    @Timeout(5)
    void v1ModelsReadsEveryWindowFieldFromMatchedModel() {
        ProbeParsers.V1ModelsResult result = ProbeParsers.v1Models(json("""
                {"data":[{
                  "id":"m",
                  "context_length":111,
                  "context_window":222,
                  "max_model_len":333,
                  "max_context_length":444,
                  "meta":{"n_ctx":555,"n_ctx_train":666}
                }]}
                """), "m");
        assertTrue(result.vllmSignature());
        assertEquals(List.of("m"), result.ids());
        assertEquals(111, result.contextLength());
        assertEquals(222, result.contextWindow());
        assertEquals(333, result.maxModelLen());
        assertEquals(555, result.metaNCtx());
        assertEquals(666, result.metaNCtxTrain());
        assertEquals(444, result.maxContextLength());
    }

    @Test
    @Timeout(5)
    void v1ModelsUnknownModelLeavesWindowFieldsNull() throws Exception {
        ProbeParsers.V1ModelsResult result = ProbeParsers.v1Models(fixture("vllm-models.json"), "other");
        assertTrue(result.vllmSignature());
        assertEquals(List.of("meta-llama/Llama-3-8B"), result.ids());
        assertNull(result.contextLength());
        assertNull(result.contextWindow());
        assertNull(result.maxModelLen());
        assertNull(result.metaNCtx());
        assertNull(result.metaNCtxTrain());
        assertNull(result.maxContextLength());
    }

    @Test
    @Timeout(5)
    void v1ModelsWithoutMaxModelLenIsNotVllmEvenWhenOtherWindowsPresent() {
        ProbeParsers.V1ModelsResult result = ProbeParsers.v1Models(json("""
                {"data":[{"id":"m","context_length":4096,"context_window":8192,
                           "max_context_length":32768,"meta":{"n_ctx":2048,"n_ctx_train":131072}}]}
                """), "m");
        assertFalse(result.vllmSignature());
        assertEquals(4096, result.contextLength());
        assertEquals(8192, result.contextWindow());
        assertNull(result.maxModelLen());
        assertEquals(2048, result.metaNCtx());
        assertEquals(131072, result.metaNCtxTrain());
        assertEquals(32768, result.maxContextLength());
    }

    private static JsonObject fixture(String name) throws Exception {
        try (var in = ProbeParsersTest.class.getResourceAsStream(
                "/kiwi/ingenuity/netbeans/plugin/aicoder/openaicompatible/detect/" + name)) {
            assertNotNull(in, name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
