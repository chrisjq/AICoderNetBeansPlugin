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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ProbeParsers;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
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
                         Map<String, List<String>> reasoningLevelsByModel) {

        public Result(Kind kind, int modelCount, List<String> models, String apiBase) {
            this(kind, modelCount, models, apiBase, null, Map.of());
        }

        public Result {
            models = models == null ? List.of() : List.copyOf(models);
            apiBase = apiBase == null ? "" : apiBase;
            reasoningLevelsByModel = copyReasoningLevels(reasoningLevelsByModel);
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
        String apiBase = BaseUrls.root(BaseUrls.normalise(rawUrl));
        CompletableFuture<Result> result = new CompletableFuture<>();
        try {
            BackendExecutors.probePool().execute(() -> runProbe(apiBase, keyOrNull, headers, limits, result));
        }
        catch (RejectedExecutionException ex) {
            result.complete(notAvailable(apiBase));
        }
        return result;
    }

    private void runProbe(String apiBase, char[] keyOrNull, List<ExtraHeader> headers,
                          OpenAiCompatibleLimits limits, CompletableFuture<Result> result) {
        lastProbeThreadName = Thread.currentThread().getName();
        try {
            result.complete(exchange(apiBase, keyOrNull, headers, limits));
        }
        catch (Throwable ex) {
            result.complete(notAvailable(apiBase));
        }
    }

    private static Result exchange(String apiBase, char[] keyOrNull, List<ExtraHeader> headers,
                                   OpenAiCompatibleLimits limits) {
        try {
            HttpResponse<byte[]> models = send(apiBase + "/v1/models", keyOrNull, headers, limits);
            Result result = classify(models, apiBase);
            if (result.kind() != Kind.CONNECTED || !BaseUrls.isLocal(apiBase)) {
                return result;
            }
            ServerKind serverKind = detectKind(apiBase, models.body(), keyOrNull, headers, limits);
            return new Result(result.kind(), result.modelCount(), result.models(), result.apiBase(), serverKind,
                              reasoningLevelsByModel(models.body(), serverKind, result.models()));
        }
        catch (Throwable ex) {
            return notAvailable(apiBase);
        }
    }

    private static HttpResponse<byte[]> send(String url, char[] keyOrNull, List<ExtraHeader> headers,
                                             OpenAiCompatibleLimits limits) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(limits.probeTimeout());
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

    private static ServerKind detectKind(String apiBase, byte[] modelsBody, char[] keyOrNull,
                                         List<ExtraHeader> headers, OpenAiCompatibleLimits limits) {
        JsonObject tags = getJson(apiBase + "/api/tags", keyOrNull, headers, limits);
        if (tags != null && ProbeParsers.ollamaTags(tags).signature()) {
            return ServerKind.OLLAMA;
        }
        JsonObject models = parseObject(modelsBody);
        if (models != null && (ProbeParsers.lmStudioV1(models, "").signature()
                || ProbeParsers.lmStudioV0(models, "").signature())) {
            return ServerKind.LMSTUDIO;
        }
        JsonObject props = getJson(apiBase + "/props", keyOrNull, headers, limits);
        if (props != null && ProbeParsers.llamaCppProps(props).signature()) {
            return ServerKind.LLAMACPP;
        }
        if (models != null && ProbeParsers.v1Models(models, "").vllmSignature()) {
            return ServerKind.VLLM;
        }
        return null;
    }

    private static Map<String, List<String>> reasoningLevelsByModel(byte[] modelsBody, ServerKind serverKind,
                                                                    List<String> modelIds) {
        if (serverKind != ServerKind.LMSTUDIO || modelIds == null || modelIds.isEmpty()) {
            return Map.of();
        }
        JsonObject models = parseObject(modelsBody);
        if (models == null) {
            return Map.of();
        }
        Map<String, List<String>> levelsByModel = new LinkedHashMap<>();
        for (String modelId : modelIds) {
            List<String> levels = ProbeParsers.lmStudioV1(models, modelId).reasoningLevels();
            if (levels != null && !levels.isEmpty()) {
                levelsByModel.put(modelId, levels);
            }
        }
        return levelsByModel;
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
