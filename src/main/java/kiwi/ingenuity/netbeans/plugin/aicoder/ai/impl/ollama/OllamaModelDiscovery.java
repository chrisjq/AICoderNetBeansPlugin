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
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Map<String, List<String>> MODEL_CACHE = new ConcurrentHashMap<>();

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
            try {
                List<String> ids = fetchModelIds(normalized);
                MODEL_CACHE.put(normalized, ids);
                if (onModels != null) {
                    onModels.accept(ids.toArray(String[]::new));
                }
            }
            catch (Exception ignored) {
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
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return java.util.Arrays.asList(assembleModelList(parseModelIds(response.body())));
    }

    private static String fetchCapabilityHint(String baseUrl, String model)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty(OllamaJsonKeyEnum.MODEL.key(), model);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/show"))
                .timeout(TIMEOUT)
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
