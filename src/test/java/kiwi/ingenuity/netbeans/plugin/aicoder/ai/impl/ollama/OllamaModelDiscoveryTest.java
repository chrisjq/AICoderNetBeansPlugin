package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OllamaModelDiscoveryTest {

    /**
     * Serves a fixed {@code /api/tags} body and an empty {@code /v1/models} list on an ephemeral loopback port — never
     * the real Ollama, per the standing "do not hammer the box" instruction. A fresh ephemeral port per call keeps each
     * test's discovery cache entry isolated (the cache is keyed by base URL), so tests cannot pollute each other even
     * though {@link OllamaModelDiscovery}'s cache is process-wide.
     */
    private static HttpServer startFakeOllamaServer(String tagsResponseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/tags", exchange -> {
                         byte[] bytes = tagsResponseBody.getBytes(StandardCharsets.UTF_8);
                         exchange.getResponseHeaders().add("Content-Type", "application/json");
                         exchange.sendResponseHeaders(200, bytes.length);
                         try (OutputStream os = exchange.getResponseBody()) {
                             os.write(bytes);
                         }
                     });
        server.createContext("/v1/models", exchange -> {
                         byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
                         exchange.sendResponseHeaders(200, bytes.length);
                         try (OutputStream os = exchange.getResponseBody()) {
                             os.write(bytes);
                         }
                     });
        server.start();
        return server;
    }

    private static String baseUrlOf(HttpServer server) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    @Test
    void assembleModelListDropsBlanksAndDedupes() {
        String[] out = OllamaModelDiscovery.assembleModelList(
                List.of("qwen2.5-coder:7b", " qwen2.5-coder:7b ", "", "qwen2.5-coder:14b"));
        assertArrayEquals(new String[]{"qwen2.5-coder:7b", "qwen2.5-coder:14b"}, out);
    }

    @Test
    void parseModelIdsExtractsIdsInOrder() {
        String body = "{\"data\":[{\"id\":\"qwen2.5-coder:7b\"},{\"id\":\"qwen2.5-coder:14b\"}]}";
        assertEquals(List.of("qwen2.5-coder:7b", "qwen2.5-coder:14b"),
                     OllamaModelDiscovery.parseModelIds(body));
    }

    @Test
    void parseModelIdsReturnsEmptyWhenMissing() {
        assertEquals(List.of(), OllamaModelDiscovery.parseModelIds("{}"));
        assertEquals(List.of(), OllamaModelDiscovery.parseModelIds("{\"data\":[]}"));
    }

    @Test
    void extractCapabilityHintWarnsWhenToolsCapabilityMissing() {
        String body = "{\"capabilities\":[\"completion\"]}";
        assertEquals("Selected model may not support structured tool calls in Ollama; JSON-in-content fallback will be used.",
                     OllamaModelDiscovery.extractCapabilityHint(body));
    }

    @Test
    void extractCapabilityHintReturnsNullWhenToolsCapabilityPresent() {
        String body = "{\"capabilities\":[\"completion\",\"tools\"]}";
        assertNull(OllamaModelDiscovery.extractCapabilityHint(body));
    }

    /**
     * Realistic payload shape, matching what was verified live against a real Ollama server on 2026-09-19
     * (qwen2.5-coder:14b, no "thinking" entry).
     */
    @Test
    void parseModelCapabilitiesExtractsPerModelCapabilities() {
        String body = "{\"models\":[{\"name\":\"qwen2.5-coder:14b\",\"model\":\"qwen2.5-coder:14b\","
                + "\"capabilities\":[\"completion\",\"tools\",\"insert\"]}]}";
        assertEquals(Map.of("qwen2.5-coder:14b", List.of("completion", "tools", "insert")),
                     OllamaModelDiscovery.parseModelCapabilities(body));
    }

    @Test
    void parseModelCapabilitiesHandlesMultipleModelsAndMissingCapabilities() {
        String body = "{\"models\":["
                + "{\"name\":\"qwen2.5-coder:14b\",\"capabilities\":[\"completion\"]},"
                + "{\"name\":\"deepseek-v3.1:671b\",\"capabilities\":[\"completion\",\"tools\",\"thinking\"]},"
                + "{\"name\":\"no-caps-model\"}]}";
        Map<String, List<String>> parsed = OllamaModelDiscovery.parseModelCapabilities(body);
        assertEquals(List.of("completion"), parsed.get("qwen2.5-coder:14b"));
        assertTrue(parsed.get("deepseek-v3.1:671b").contains("thinking"));
        assertEquals(List.of(), parsed.get("no-caps-model"));
    }

    @Test
    void parseModelCapabilitiesReturnsEmptyWhenMissing() {
        assertEquals(Map.of(), OllamaModelDiscovery.parseModelCapabilities("{}"));
        assertEquals(Map.of(), OllamaModelDiscovery.parseModelCapabilities("{\"models\":[]}"));
    }

    @Test
    void discoverAsyncPopulatesTheCapabilityCacheFromApiTags() throws Exception {
        String body = "{\"models\":[{\"name\":\"qwen2.5-coder:14b\",\"capabilities\":[\"completion\",\"tools\",\"insert\"]}]}";
        HttpServer server = startFakeOllamaServer(body);
        try {
            String baseUrl = baseUrlOf(server);
            CountDownLatch done = new CountDownLatch(1);
            OllamaModelDiscovery.discoverAsync(baseUrl, models -> done.countDown(), hint -> {
                                       });

            assertTrue(done.await(5, TimeUnit.SECONDS), "discovery did not complete");
            assertTrue(OllamaModelDiscovery.isModelKnown(baseUrl, "qwen2.5-coder:14b"));
            assertFalse(OllamaModelDiscovery.modelSupportsThinking(baseUrl, "qwen2.5-coder:14b"),
                        "this model's capabilities array has no \"thinking\" entry");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void discoverAsyncDetectsAThinkingCapableModel() throws Exception {
        String body = "{\"models\":[{\"name\":\"deepseek-v3.1:671b\",\"capabilities\":[\"completion\",\"tools\",\"thinking\"]}]}";
        HttpServer server = startFakeOllamaServer(body);
        try {
            String baseUrl = baseUrlOf(server);
            CountDownLatch done = new CountDownLatch(1);
            OllamaModelDiscovery.discoverAsync(baseUrl, models -> done.countDown(), hint -> {
                                       });

            assertTrue(done.await(5, TimeUnit.SECONDS), "discovery did not complete");
            assertTrue(OllamaModelDiscovery.modelSupportsThinking(baseUrl, "deepseek-v3.1:671b"));
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void isModelKnownIsFalseForAModelNoDiscoveryHasEverReported() {
        assertFalse(OllamaModelDiscovery.isModelKnown(
                "http://127.0.0.1:1", "a-model-that-was-never-discovered-" + System.nanoTime()));
        assertFalse(OllamaModelDiscovery.modelSupportsThinking(
                "http://127.0.0.1:1", "a-model-that-was-never-discovered-" + System.nanoTime()));
    }
}
