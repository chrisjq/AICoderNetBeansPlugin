package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Ollama's concrete broker. The generic behaviour is entirely in the base class; only the context limit is
 * provider-specific.
 */
public class OllamaChatContextBroker extends AbstractChatContextBroker {

    private static final int UNKNOWN_CONTEXT_LIMIT = 0;
    private static final long DISCOVERY_REFRESH_SECONDS = 60L;
    private static final int DERIVED_TRIM_PERCENT = 80;
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(3);
    private static final java.net.http.HttpClient NATIVE_HTTP_CLIENT
            = java.net.http.HttpClient.newHttpClient();

    private final Function<String, CompletableFuture<String>> nativeApi;
    private final ScheduledExecutorService discoveryExecutor;
    private volatile int discoveredContextLimit = UNKNOWN_CONTEXT_LIMIT;
    private volatile boolean discoveryStarted;
    private volatile String targetModel;
    private volatile Consumer<Integer> contextLimitListener;

    public OllamaChatContextBroker(String sessionId, ContextBrokerSettings settings) {
        this(sessionId, settings, OllamaChatContextBroker::fetchNativeApi);
    }

    OllamaChatContextBroker(String sessionId, ContextBrokerSettings settings,
            Function<String, CompletableFuture<String>> nativeApi) {
        super(sessionId, settings);
        this.nativeApi = nativeApi;
        this.discoveryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ollama-context-discovery-" + sessionId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts an asynchronous refresh loop. The native /api/ps endpoint is used deliberately: /api/show
     * reports the model maximum, while /api/ps reports the context window actually loaded by the running
     * server.
     */
    public void startContextDiscovery(String configuredBaseUrl) {
        startContextDiscovery(configuredBaseUrl, null);
    }

    public synchronized void startContextDiscovery(String configuredBaseUrl, String modelName) {
        if (discoveryStarted || configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            return;
        }
        discoveryStarted = true;
        targetModel = modelName;
        String endpoint = nativeApiEndpoint(configuredBaseUrl);
        refreshContextLimit(endpoint);
        discoveryExecutor.scheduleWithFixedDelay(
                () -> refreshContextLimit(endpoint),
                DISCOVERY_REFRESH_SECONDS, DISCOVERY_REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    public void close() {
        discoveryExecutor.shutdownNow();
    }

    public void setContextLimitListener(Consumer<Integer> listener) {
        contextLimitListener = listener;
    }

    @Override
    protected int contextLimit() {
        return discoveredContextLimit;
    }

    @Override
    protected int trimThreshold() {
        if (settings.tokenThreshold() > 0) {
            return settings.tokenThreshold();
        }
        int discovered = discoveredContextLimit;
        // Leave room for estimation error and the next assistant response; a
        // full runtime window is already too late for llama.cpp context-shift.
        return discovered > 0
                ? (int) ((long) discovered * DERIVED_TRIM_PERCENT / 100L)
                : super.trimThreshold();
    }

    static String nativeApiEndpoint(String configuredBaseUrl) {
        String base = configuredBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        return base + "/api/ps";
    }

    private void refreshContextLimit(String endpoint) {
        try {
            nativeApi.apply(endpoint)
                    .thenAccept(this::applyDiscoveryResponse)
                    .exceptionally(ex -> null);
        } catch (RuntimeException ex) {
            // Discovery is advisory. Keep the last known value on transport failure.
        }
    }

    private void applyDiscoveryResponse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray models = root.getAsJsonArray("models");
            int resolved;
            if (models == null || models.isEmpty()) {
                resolved = UNKNOWN_CONTEXT_LIMIT;
            } else {
                JsonObject selected = null;
                long smallestContextLimit = Long.MAX_VALUE;
                for (JsonElement model : models) {
                    if (!model.isJsonObject()) {
                        continue;
                    }
                    JsonObject candidate = model.getAsJsonObject();
                    String name = candidate.has("model") ? candidate.get("model").getAsString()
                            : candidate.has("name") ? candidate.get("name").getAsString() : null;
                    if (!(targetModel == null || targetModel.isBlank() || targetModel.equals(name))) {
                        continue;
                    }
                    if (targetModel == null || targetModel.isBlank()) {
                        selected = candidate;
                        break;
                    }
                    JsonElement candidateLimit = candidate.get("context_length");
                    if (candidateLimit != null && candidateLimit.isJsonPrimitive()
                            && candidateLimit.getAsLong() > 0 && candidateLimit.getAsLong() < smallestContextLimit) {
                        // Duplicate runners can have different windows; earlier trimming is safer than silently
                        // assuming the larger runner is the one serving requests.
                        selected = candidate;
                        smallestContextLimit = candidateLimit.getAsLong();
                    }
                }
                if (selected == null || !selected.has("context_length")) {
                    return;
                }
                JsonElement value = selected.get("context_length");
                if (!value.isJsonPrimitive() || value.getAsLong() <= 0) {
                    return;
                }
                long limit = value.getAsLong();
                resolved = limit <= Integer.MAX_VALUE ? (int) limit : Integer.MAX_VALUE;
            }
            discoveredContextLimit = resolved;
            Consumer<Integer> listener = contextLimitListener;
            if (listener != null) {
                listener.accept(resolved);
            }
        } catch (RuntimeException ex) {
            // Malformed responses must never affect trimming or the caller.
        }
    }

    private static CompletableFuture<String> fetchNativeApi(String endpoint) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(DISCOVERY_TIMEOUT)
                .GET()
                .build();
        return NATIVE_HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }
}
