package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ProbeParsers;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;

/**
 * Simple {@code GET {root}/v1/models} probe for the Test connection control (spec §3.4). The whole exchange, headers
 * and body, is bounded by {@link OpenAiCompatibleLimits#probeTimeout()} and the body is capped at
 * {@link OpenAiCompatibleLimits#probeBodyCapBytes()}.
 */
public final class ConnectionTester {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.getDefault())
            .build();

    /**
     * Name of the probe-pool thread that last ran {@link #test}; visible to tests in this package.
     */
    volatile String lastProbeThreadName;

    public enum Kind {
        CONNECTED,
        KEY_REJECTED,
        NOT_AVAILABLE
    }

    public record Result(Kind kind, int modelCount, List<String> models, String apiBase, ServerKind serverKind,
                         Map<String, List<String>> reasoningLevelsByModel, DetectedFacts facts) {

        public Result(Kind kind, int modelCount, List<String> models, String apiBase) {
            this(kind, modelCount, models, apiBase, null, Map.of(), null);
        }

        public Result(Kind kind, int modelCount, List<String> models, String apiBase, ServerKind serverKind,
                      Map<String, List<String>> reasoningLevelsByModel) {
            this(kind, modelCount, models, apiBase, serverKind, reasoningLevelsByModel, null);
        }

        public Result {
            models = models == null ? List.of() : List.copyOf(models);
            apiBase = apiBase == null ? "" : apiBase;
            reasoningLevelsByModel = copyReasoningLevels(reasoningLevelsByModel);
            facts = facts == null ? DetectedFacts.of("test-connection")
                    .withApiBase(apiBase)
                    .withKind(serverKind == null ? ServerKind.GENERIC : serverKind)
                    .withModels(models)
                    .withTimestamp(Instant.now()) : facts;
        }

        public String text() {
            return switch (kind) {
                case CONNECTED ->
                    connectedText();
                case KEY_REJECTED ->
                    "API key rejected";
                case NOT_AVAILABLE ->
                    "N/A";
            };
        }

        private String connectedText() {
            if (serverKind != null && serverKind != ServerKind.GENERIC) {
                return "Connected · " + serverKind.displayName() + " · " + modelCount + " models";
            }
            return "Connected · " + modelCount + " models";
        }

        private static Map<String, List<String>> copyReasoningLevels(Map<String, List<String>> levelsByModel) {
            if (levelsByModel == null || levelsByModel.isEmpty()) {
                return Map.of();
            }
            Map<String, List<String>> copy = new LinkedHashMap<>();
            levelsByModel.forEach((model, levels) -> {
                if (model != null && !model.isBlank() && levels != null && !levels.isEmpty()) {
                    copy.put(model, List.copyOf(levels));
                }
            });
            return Map.copyOf(copy);
        }
    }

    public CompletableFuture<Result> test(String rawUrl, char[] keyOrNull, List<ExtraHeader> headers,
                                          OpenAiCompatibleLimits limits) {
        return test(rawUrl, ServerTypeEnum.AUTO, "", keyOrNull, headers, limits);
    }

    /**
     * Tests an endpoint using the model and server type currently selected by the user. Those values let the optional
     * vendor probes return facts for the model the session will actually use instead of an arbitrary discovered model.
     */
    public CompletableFuture<Result> test(String rawUrl, ServerTypeEnum serverType, String selectedModel,
                                          char[] keyOrNull, List<ExtraHeader> headers, OpenAiCompatibleLimits limits) {
        String apiBase = BaseUrls.root(BaseUrls.normalise(rawUrl));
        CompletableFuture<Result> result = new CompletableFuture<>();
        try {
            BackendExecutors.probePool().execute(() -> runProbe(apiBase, serverType, selectedModel, keyOrNull, headers,
                                                                limits, result));
        }
        catch (RejectedExecutionException ex) {
            result.complete(notAvailable(apiBase));
        }
        return result;
    }

    private void runProbe(String apiBase, ServerTypeEnum serverType, String selectedModel, char[] keyOrNull,
                          List<ExtraHeader> headers, OpenAiCompatibleLimits limits, CompletableFuture<Result> result) {
        lastProbeThreadName = Thread.currentThread().getName();
        try {
            result.complete(exchange(apiBase, serverType, selectedModel, keyOrNull, headers, limits));
        }
        catch (Throwable ex) {
            result.complete(notAvailable(apiBase));
        }
    }

    private static Result exchange(String apiBase, ServerTypeEnum serverType, String selectedModel,
                                   char[] keyOrNull, List<ExtraHeader> headers, OpenAiCompatibleLimits limits) {
        try {
            HttpResponse<byte[]> models = send(apiBase + "/v1/models", keyOrNull, headers, limits);
            Result result = classify(models, apiBase);
            if (result.kind() != Kind.CONNECTED) {
                return result;
            }
            return detectOnce(apiBase, serverType, selectedModel, models.body(), keyOrNull, headers, limits,
                              result.models());
        }
        catch (Throwable ex) {
            return notAvailable(apiBase);
        }
    }

    private static HttpResponse<byte[]> send(String url, char[] keyOrNull, List<ExtraHeader> headers,
                                             OpenAiCompatibleLimits limits) {
        return send(url, null, keyOrNull, headers, limits);
    }

    private static HttpResponse<byte[]> send(String url, String jsonBody, char[] keyOrNull,
                                             List<ExtraHeader> headers, OpenAiCompatibleLimits limits) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(limits.probeTimeout());
        if (jsonBody == null) {
            builder.GET();
        }
        else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        if (keyOrNull != null && keyOrNull.length > 0) {
            builder.header("Authorization", "Bearer " + new String(keyOrNull));
        }
        if (headers != null) {
            for (ExtraHeader header : headers) {
                builder.header(header.name(), header.value());
            }
        }
        long timeoutMs = Math.max(1L, limits.probeTimeout().toMillis());
        CompletableFuture<HttpResponse<byte[]>> send = HTTP_CLIENT.sendAsync(
                builder.build(), cappedBody(limits.probeBodyCapBytes()));
        CompletableFuture<HttpResponse<byte[]>> bounded = send.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        bounded.whenComplete((response, error) -> {
            if (error != null) {
                send.cancel(true);
            }
        });
        return bounded.join();
    }

    /**
     * Runs the same non-invasive GET probes as the poller so a successful Test connection can populate the complete
     * detected view before a session starts. Endpoint failures are deliberately ignored: the initial v1-models response
     * already established that the server is reachable.
     */
    private static Result detectOnce(String apiBase, ServerTypeEnum serverType, String requestedModel, byte[] v1Body,
                                     char[] keyOrNull, List<ExtraHeader> headers, OpenAiCompatibleLimits limits,
                                     List<String> initialModels) {
        JsonObject v1 = parseObject(v1Body);
        ServerKind configuredKind = configuredKind(serverType);
        boolean auto = serverType == null || serverType == ServerTypeEnum.AUTO;
        boolean hosted = BaseUrls.isHosted(apiBase, keyOrNull != null && keyOrNull.length > 0);
        boolean probeOllama = configuredKind == ServerKind.OLLAMA || (!hosted && auto);
        boolean probeLmStudio = configuredKind == ServerKind.LMSTUDIO || (!hosted && auto);
        boolean probeLlamaCpp = configuredKind == ServerKind.LLAMACPP || (!hosted && auto);

        JsonObject tags = probeOllama ? getJson(apiBase + "/api/tags", keyOrNull, headers, limits) : null;
        ProbeParsers.OllamaTagsResult parsedTags = tags == null ? null : ProbeParsers.ollamaTags(tags);
        String selectedModel = requestedModel != null && !requestedModel.isBlank() ? requestedModel
                               : parsedTags != null && !parsedTags.modelNames().isEmpty() ? parsedTags.modelNames().get(0)
                                 : initialModels == null || initialModels.isEmpty() ? "" : initialModels.get(0);
        JsonObject ps = probeOllama ? getJson(apiBase + "/api/ps", keyOrNull, headers, limits) : null;
        JsonObject show = probeOllama && !selectedModel.isBlank()
                          ? postJson(apiBase + "/api/show", "{\"name\":\"" + jsonString(selectedModel) + "\"}", keyOrNull, headers, limits)
                          : null;
        JsonObject lmV1 = probeLmStudio ? getJson(apiBase + "/api/v1/models", keyOrNull, headers, limits) : null;
        JsonObject lmV0 = probeLmStudio ? getJson(apiBase + "/api/v0/models", keyOrNull, headers, limits) : null;
        JsonObject props = probeLlamaCpp ? getJson(apiBase + "/props", keyOrNull, headers, limits) : null;
        ProbeParsers.LmStudioResult parsedLmV1 = lmV1 == null ? null : ProbeParsers.lmStudioV1(lmV1, selectedModel);
        if (probeLmStudio && (parsedLmV1 == null || !parsedLmV1.signature())) {
            ProbeParsers.LmStudioResult fromV1 = v1 == null ? null : ProbeParsers.lmStudioV1(v1, selectedModel);
            if (fromV1 != null && fromV1.signature()) {
                parsedLmV1 = fromV1;
            }
        }
        ProbeParsers.LmStudioResult parsedLmV0 = lmV0 == null ? null : ProbeParsers.lmStudioV0(lmV0, selectedModel);
        ProbeParsers.LmStudioResult parsedLm = parsedLmV1 != null && parsedLmV1.signature() ? parsedLmV1 : parsedLmV0;
        if (parsedLm != null && !parsedLm.modelIds().contains(selectedModel) && !parsedLm.modelIds().isEmpty()) {
            selectedModel = parsedLm.modelIds().get(0);
            parsedLmV1 = lmV1 != null ? ProbeParsers.lmStudioV1(lmV1, selectedModel)
                         : probeLmStudio && v1 != null ? ProbeParsers.lmStudioV1(v1, selectedModel) : parsedLmV1;
            parsedLmV0 = lmV0 == null ? parsedLmV0 : ProbeParsers.lmStudioV0(lmV0, selectedModel);
            parsedLm = parsedLmV1 != null && parsedLmV1.signature() ? parsedLmV1 : parsedLmV0;
        }
        ProbeParsers.OllamaShowResult parsedShow = show == null ? null : ProbeParsers.ollamaShow(show);
        Integer ollamaPs = ps == null ? null : ProbeParsers.ollamaPs(ps, selectedModel);
        if (ollamaPs == null && parsedTags != null && !parsedTags.modelNames().isEmpty() && ps != null) {
            ollamaPs = ProbeParsers.ollamaPs(ps, parsedTags.modelNames().get(0));
        }
        ProbeParsers.LlamaCppPropsResult parsedProps = props == null ? null : ProbeParsers.llamaCppProps(props);
        ProbeParsers.V1ModelsResult parsedV1 = v1 == null ? null : ProbeParsers.v1Models(v1, selectedModel);
        ServerKind detectedKind = firstKind(parsedTags != null && parsedTags.signature(),
                                            parsedLm != null && parsedLm.signature(),
                                            parsedProps != null && parsedProps.signature(),
                                            parsedV1 != null && parsedV1.vllmSignature());
        ServerKind kind = detectedKind;
        List<String> models = modelsFor(kind, initialModels, parsedTags, parsedLm, parsedV1);
        JsonObject lmReasoningBody = lmV1 != null ? lmV1 : parsedLm != null && probeLmStudio && v1 != null ? v1 : null;
        Map<String, List<String>> reasoning = reasoningLevelsByModel(lmReasoningBody, lmV0, models);
        List<WindowReading> readings = windowReadings(parsedShow, ollamaPs, parsedLm, parsedProps, parsedV1);
        boolean v1FieldPresent = hasV1WindowField(parsedV1);
        Boolean tools = parsedShow == null ? parsedLm == null ? null : parsedLm.tools() : Boolean.valueOf(parsedShow.tools());
        Boolean thinking = parsedShow == null ? null : parsedShow.thinking();
        DetectedFacts facts = DetectedFacts.of("test-connection")
                .withKind(kind)
                .withApiBase(apiBase + "/v1")
                .withStreaming(null)
                .withCapabilities(new DetectedFacts.Capabilities(tools, thinking, reasoning.get(selectedModel)))
                .withWindowReadings(readings)
                .withV1WindowFieldPresent(v1FieldPresent)
                .withOllamaTrainingContextLength(parsedShow == null ? null : parsedShow.trainingContextLength())
                .withLmStudioMaxContextLength(parsedLm == null ? null : parsedLm.maxContextLength())
                .withLlamaCppTrainContextLength(parsedV1 == null ? null : parsedV1.metaNCtxTrain())
                .withModels(models)
                .withTimestamp(Instant.now());
        return new Result(Kind.CONNECTED, models.size(), models, apiBase, kind, reasoning, facts);
    }

    private static ServerKind configuredKind(ServerTypeEnum serverType) {
        if (serverType == null) {
            return null;
        }
        return switch (serverType) {
            case OLLAMA ->
                ServerKind.OLLAMA;
            case LM_STUDIO ->
                ServerKind.LMSTUDIO;
            case LLAMA_CPP ->
                ServerKind.LLAMACPP;
            case VLLM ->
                ServerKind.VLLM;
            case AUTO, OTHER ->
                null;
        };
    }

    private static ServerKind firstKind(boolean ollama, boolean lmStudio, boolean llama, boolean vllm) {
        if (ollama) {
            return ServerKind.OLLAMA;
        }
        if (lmStudio) {
            return ServerKind.LMSTUDIO;
        }
        if (llama) {
            return ServerKind.LLAMACPP;
        }
        if (vllm) {
            return ServerKind.VLLM;
        }
        return ServerKind.GENERIC;
    }

    private static List<String> modelsFor(ServerKind kind, List<String> initialModels,
                                          ProbeParsers.OllamaTagsResult tags, ProbeParsers.LmStudioResult lm,
                                          ProbeParsers.V1ModelsResult v1) {
        if (kind == ServerKind.OLLAMA && tags != null && !tags.modelNames().isEmpty()) {
            return tags.modelNames();
        }
        if (kind == ServerKind.LMSTUDIO && lm != null && !lm.modelIds().isEmpty()) {
            return lm.modelIds();
        }
        if (v1 != null && !v1.ids().isEmpty()) {
            return v1.ids();
        }
        return initialModels == null ? List.of() : initialModels;
    }

    private static Map<String, List<String>> reasoningLevelsByModel(JsonObject lmV1, JsonObject lmV0,
                                                                    List<String> modelIds) {
        if ((lmV1 == null && lmV0 == null) || modelIds == null || modelIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> levelsByModel = new LinkedHashMap<>();
        for (String modelId : modelIds) {
            ProbeParsers.LmStudioResult parsed = lmV1 == null ? null : ProbeParsers.lmStudioV1(lmV1, modelId);
            if (parsed == null || !parsed.signature()) {
                parsed = lmV0 == null ? null : ProbeParsers.lmStudioV0(lmV0, modelId);
            }
            if (parsed != null && parsed.reasoningLevels() != null && !parsed.reasoningLevels().isEmpty()) {
                levelsByModel.put(modelId, parsed.reasoningLevels());
            }
        }
        return levelsByModel;
    }

    private static List<WindowReading> windowReadings(ProbeParsers.OllamaShowResult show, Integer ollamaPs,
                                                      ProbeParsers.LmStudioResult lm,
                                                      ProbeParsers.LlamaCppPropsResult props,
                                                      ProbeParsers.V1ModelsResult v1) {
        List<WindowReading> readings = new ArrayList<>();
        if (positive(ollamaPs)) {
            readings.add(new WindowReading(ollamaPs, WindowReading.Source.OLLAMA_PS, true));
        }
        if (show != null && positive(show.numCtx())) {
            readings.add(new WindowReading(show.numCtx(), WindowReading.Source.OLLAMA_NUM_CTX, true));
        }
        if (lm != null && positive(lm.loadedContextLength())) {
            readings.add(new WindowReading(lm.loadedContextLength(), WindowReading.Source.LMSTUDIO_LOADED, true));
        }
        if (props != null && positive(props.nCtx())) {
            readings.add(new WindowReading(props.nCtx(), WindowReading.Source.LLAMACPP_N_CTX, true));
        }
        Integer vllm = v1 == null ? null : v1.maxModelLen();
        if (positive(vllm)) {
            readings.add(new WindowReading(vllm, WindowReading.Source.VLLM_MAX_MODEL_LEN, true));
        }
        Integer v1Window = firstPositive(v1 == null ? null : v1.contextLength(),
                                         v1 == null ? null : v1.contextWindow(), vllm,
                                         v1 == null ? null : v1.metaNCtx());
        if (v1Window != null) {
            readings.add(new WindowReading(v1Window, WindowReading.Source.V1_MODELS, true));
        }
        return readings;
    }

    private static boolean hasV1WindowField(ProbeParsers.V1ModelsResult v1) {
        return v1 != null && (v1.contextLength() != null || v1.contextWindow() != null || v1.maxModelLen() != null
                || v1.metaNCtx() != null || v1.metaNCtxTrain() != null || v1.maxContextLength() != null);
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static Integer firstPositive(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (positive(value)) {
                return value;
            }
        }
        return null;
    }

    private static JsonObject getJson(String url, char[] keyOrNull, List<ExtraHeader> headers,
                                      OpenAiCompatibleLimits limits) {
        try {
            HttpResponse<byte[]> response = send(url, keyOrNull, headers, limits);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            return parseObject(response.body());
        }
        catch (Throwable ex) {
            return null;
        }
    }

    private static JsonObject postJson(String url, String body, char[] keyOrNull, List<ExtraHeader> headers,
                                       OpenAiCompatibleLimits limits) {
        try {
            HttpResponse<byte[]> response = send(url, body, keyOrNull, headers, limits);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            return parseObject(response.body());
        }
        catch (Throwable ex) {
            return null;
        }
    }

    private static String jsonString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static JsonObject parseObject(byte[] body) {
        try {
            JsonElement element = JsonParser.parseString(
                    new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    private static Result classify(HttpResponse<byte[]> response, String apiBase) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            return new Result(Kind.KEY_REJECTED, 0, List.of(), apiBase);
        }
        if (status < 200 || status >= 300) {
            return notAvailable(apiBase);
        }
        try {
            List<String> ids = parseModelIds(response.body());
            return new Result(Kind.CONNECTED, ids.size(), ids, apiBase);
        }
        catch (RuntimeException ex) {
            return notAvailable(apiBase);
        }
    }

    private static List<String> parseModelIds(byte[] body) {
        String json = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement data = root.get("data");
        if (data == null || !data.isJsonArray()) {
            throw new IllegalArgumentException("missing data array");
        }
        List<String> ids = new ArrayList<>();
        for (JsonElement element : data.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonElement id = element.getAsJsonObject().get("id");
            if (id != null && id.isJsonPrimitive()) {
                ids.add(id.getAsString());
            }
        }
        return ids;
    }

    private static Result notAvailable(String apiBase) {
        return new Result(Kind.NOT_AVAILABLE, 0, List.of(), apiBase);
    }

    private static HttpResponse.BodyHandler<byte[]> cappedBody(long capBytes) {
        return info -> {
            long contentLength = info.headers().firstValue("Content-Length").map(value -> {
                try {
                    return Long.parseLong(value.trim());
                }
                catch (NumberFormatException ex) {
                    return -1L;
                }
            }).orElse(-1L);
            return new CappedBodySubscriber(capBytes, contentLength > capBytes);
        };
    }

    /**
     * Accumulates at most {@code capBytes}. Completes exceptionally once the body (or Content-Length) is over the cap
     * so the probe can report {@link Kind#NOT_AVAILABLE} without buffering the rest.
     */
    private static final class CappedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final long capBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;

        CappedBodySubscriber(long capBytes, boolean alreadyOversize) {
            this.capBytes = capBytes;
            if (alreadyOversize) {
                future.completeExceptionally(new BodyTooLargeException());
            }
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return future;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (future.isDone()) {
                subscription.cancel();
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (future.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int remaining = item.remaining();
                if (received + remaining > capBytes) {
                    failOversize();
                    return;
                }
                byte[] chunk = new byte[remaining];
                item.get(chunk);
                buffer.write(chunk, 0, remaining);
                received += remaining;
            }
        }

        private void failOversize() {
            if (subscription != null) {
                subscription.cancel();
            }
            future.completeExceptionally(new BodyTooLargeException());
        }

        @Override
        public void onError(Throwable throwable) {
            future.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            future.complete(buffer.toByteArray());
        }
    }

    private static final class BodyTooLargeException extends IOException {

        BodyTooLargeException() {
            super("probe body exceeded cap");
        }
    }
}
