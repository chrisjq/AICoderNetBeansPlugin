package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class OllamaModelDiscovery {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Map<String, List<String>> MODEL_CACHE = new ConcurrentHashMap<>();
    /**
     * Per-server, per-model {@code capabilities} arrays from the native {@code GET /api/tags} (verified live
     * 2026-09-19: a thinking-capable model's entry includes {@code "thinking"}; {@code qwen2.5-coder:14b} reported
     * {@code ["completion","tools","insert"]} — no thinking support). Keyed the same way as {@link #MODEL_CACHE}
     * (normalized base URL), since different sessions can point at different Ollama servers.
     */
    private static final Map<String, Map<String, List<String>>> CAPABILITY_CACHE = new ConcurrentHashMap<>();

    static String[] assembleModelList(List<String> discoveredIds) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (discoveredIds != null) {
            for (String id : discoveredIds) {
                if (id != null && !id.isBlank()) {
                    out.add(id.trim());
                }
            }
        }
        return out.toArray(String[]::new);
    }

    static List<String> parseModelIds(String responseBody) {
        List<String> ids = new ArrayList<>();
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonElement data = root.get(OllamaJsonKeyEnum.DATA.key());
        if (data == null || !data.isJsonArray()) {
            return ids;
        }
        for (JsonElement element : data.getAsJsonArray()) {
            if (element.isJsonObject() && element.getAsJsonObject().has(OllamaJsonKeyEnum.ID.key())) {
                ids.add(element.getAsJsonObject().get(OllamaJsonKeyEnum.ID.key()).getAsString());
            }
        }
        return ids;
    }

    /**
     * Parses the native {@code GET /api/tags} response into a per-model capabilities map. Unknown/malformed entries are
     * skipped rather than failing the whole batch — one bad entry must not lose every other model's data.
     */
    static Map<String, List<String>> parseModelCapabilities(String responseBody) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonElement models = root.get(OllamaJsonKeyEnum.MODELS.key());
        if (models == null || !models.isJsonArray()) {
            return out;
        }
        for (JsonElement element : models.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            JsonElement nameEl = obj.get(OllamaJsonKeyEnum.NAME.key());
            if (nameEl == null || !nameEl.isJsonPrimitive()) {
                continue;
            }
            List<String> caps = new ArrayList<>();
            JsonElement capsEl = obj.get(OllamaJsonKeyEnum.CAPABILITIES.key());
            if (capsEl != null && capsEl.isJsonArray()) {
                for (JsonElement c : capsEl.getAsJsonArray()) {
                    if (c.isJsonPrimitive()) {
                        caps.add(c.getAsString());
                    }
                }
            }
            out.put(nameEl.getAsString(), caps);
        }
        return out;
    }

    /**
     * The raw {@code capabilities} array the last successful {@code GET /api/tags} reported for {@code model} on
     * {@code baseUrl}'s server, or empty if that model has not (yet) been reported — either discovery has not completed
     * for this server, or its {@code /api/tags} does not list this exact model string.
     */
    public static List<String> capabilitiesFor(String baseUrl, String model) {
        if (model == null || model.isBlank()) {
            return List.of();
        }
        Map<String, List<String>> caps = CAPABILITY_CACHE.get(normalizeBaseUrl(baseUrl));
        return caps != null ? caps.getOrDefault(model, List.of()) : List.of();
    }

    /**
     * Whether discovery has POSITIVELY confirmed {@code model} supports thinking (its {@code /api/tags} entry's
     * {@code capabilities} includes {@code "thinking"}). False both when the model genuinely cannot think and when
     * discovery has not reported on it yet — see {@link #isModelKnown} to tell those two apart, which matters because
     * only the former is safe grounds to clear a user's stored value (spec §1 rule 3a).
     */
    public static boolean modelSupportsThinking(String baseUrl, String model) {
        return capabilitiesFor(baseUrl, model).contains("thinking");
    }

    /**
     * Whether {@code model} appeared at all in the last successful {@code /api/tags} response for {@code baseUrl} —
     * i.e. whether {@link #modelSupportsThinking}'s answer is a confirmed fact rather than "no data yet". Callers must
     * not clear a stored reasoning-effort value on the strength of an unknown model; the 4xx retry
     * ({@code OllamaAiProcessManager}) is the backstop for that case instead.
     */
    public static boolean isModelKnown(String baseUrl, String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        Map<String, List<String>> caps = CAPABILITY_CACHE.get(normalizeBaseUrl(baseUrl));
        return caps != null && caps.containsKey(model);
    }

    static String extractCapabilityHint(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonElement capabilities = root.get(OllamaJsonKeyEnum.CAPABILITIES.key());
        if (capabilities != null && capabilities.isJsonArray()) {
            for (JsonElement element : capabilities.getAsJsonArray()) {
                if (element.isJsonPrimitive()
                        && "tools".equalsIgnoreCase(element.getAsString())) {
                    return null;
                }
            }
            return "Selected model may not support structured tool calls in Ollama; JSON-in-content fallback will be used.";
        }
        return null;
    }

    public static void discoverAsync(String baseUrl, Consumer<String[]> onModels,
                                     Consumer<String> onHint) {
        String normalized = normalizeBaseUrl(baseUrl);
        List<String> cached = MODEL_CACHE.get(normalized);
        if (cached != null && onModels != null) {
            onModels.accept(cached.toArray(String[]::new));
        }
        Thread t = new Thread(() -> {
            List<String> ids = null;
            try {
                ids = fetchModelIds(normalized);
                MODEL_CACHE.put(normalized, ids);
            }
            catch (Exception ignored) {
            }
            // Independent try/catch: a capabilities fetch failure must not stop the model list from being reported,
            // and vice versa. Cached BEFORE onModels fires below — the info bar refreshes its thinking-effort combo
            // from that event, and needs the capability cache already populated when it does, or it would filter
            // against stale/empty data on the very discovery cycle meant to fix that.
            try {
                CAPABILITY_CACHE.put(normalized, fetchModelCapabilities(normalized));
            }
            catch (Exception ignored) {
            }
            if (ids != null && onModels != null) {
                onModels.accept(ids.toArray(String[]::new));
            }
            if (onHint != null) {
                onHint.accept(null);
            }
        }, "ollama-model-discovery");
        t.setDaemon(true);
        t.start();
    }

    public static void probeCapabilityAsync(String baseUrl, String model,
                                            Consumer<String> onHint) {
        if (model == null || model.isBlank() || onHint == null) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                onHint.accept(fetchCapabilityHint(normalizeBaseUrl(baseUrl), model));
            }
            catch (Exception ignored) {
                onHint.accept(null);
            }
        }, "ollama-capability-discovery");
        t.setDaemon(true);
        t.start();
    }

    private static List<String> fetchModelIds(String baseUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models"))
                .timeout(Duration.ofMillis(OllamaTimeoutEnum.OLLAMA_MODEL_DISCOVERY_MILLIS.millis()))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request,
                                                         HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return java.util.Arrays.asList(assembleModelList(parseModelIds(response.body())));
    }

    private static Map<String, List<String>> fetchModelCapabilities(String baseUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofMillis(OllamaTimeoutEnum.OLLAMA_MODEL_DISCOVERY_MILLIS.millis()))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request,
                                                         HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return parseModelCapabilities(response.body());
    }

    private static String fetchCapabilityHint(String baseUrl, String model)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty(OllamaJsonKeyEnum.MODEL.key(), model);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/show"))
                .timeout(Duration.ofMillis(OllamaTimeoutEnum.OLLAMA_MODEL_DISCOVERY_MILLIS.millis()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request,
                                                         HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        return extractCapabilityHint(response.body());
    }

    static String normalizeBaseUrl(String baseUrl) {
        String normalized = (baseUrl == null || baseUrl.isBlank())
                            ? "http://localhost:11434"
                            : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private OllamaModelDiscovery() {
    }
}
