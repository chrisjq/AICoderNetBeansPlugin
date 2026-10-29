package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ConnectionTester.Kind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ConnectionTester.Result;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ConnectionTesterTest {

    private static final byte[] TWO_MODELS_JSON
            = "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @Timeout(5)
    void twoModelsAreConnected() throws Exception {
        String url = start(jsonHandler(200, TWO_MODELS_JSON));
        Result result = await(new ConnectionTester().test(url, null, List.of(), OpenAiCompatibleLimits.defaults()));
        assertEquals(Kind.CONNECTED, result.kind());
        assertEquals(2, result.modelCount());
        assertEquals(List.of("a", "b"), result.models());
        assertEquals(url, result.apiBase());
        assertEquals("Connected · 2 models", result.text());
    }

    @Test
    @Timeout(5)
    void unauthorizedIsKeyRejected() throws Exception {
        String url = start((HttpExchange exchange) -> {
            drain(exchange);
            exchange.sendResponseHeaders(401, -1);
        });
        Result result = await(new ConnectionTester().test(url, "k".toCharArray(), List.of(),
                                                          OpenAiCompatibleLimits.defaults()));
        assertEquals(Kind.KEY_REJECTED, result.kind());
        assertEquals(0, result.modelCount());
        assertEquals(List.of(), result.models());
        assertEquals("API key rejected", result.text());
    }

    @Test
    @Timeout(5)
    void connectionRefusedIsNotAvailable() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            port = socket.getLocalPort();
        }
        String url = "http://127.0.0.1:" + port;
        Result result = await(new ConnectionTester().test(url, null, List.of(), OpenAiCompatibleLimits.defaults()));
        assertEquals(Kind.NOT_AVAILABLE, result.kind());
        assertEquals("N/A", result.text());
        assertEquals(url, result.apiBase());
    }

    @Test
    @Timeout(5)
    void stalledBodyIsNotAvailableWithinProbeTimeout() throws Exception {
        CountDownLatch hang = new CountDownLatch(1);
        String url = start(exchange -> {
            drain(exchange);
            exchange.sendResponseHeaders(200, 8);
            try {
                hang.await(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            OpenAiCompatibleLimits limits = OpenAiCompatibleLimits.defaults()
                    .withProbeTimeout(Duration.ofMillis(300));
            Result result = new ConnectionTester()
                    .test(url, null, List.of(), limits)
                    .get(limits.probeTimeout().plusSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(Kind.NOT_AVAILABLE, result.kind());
            assertEquals("N/A", result.text());
        }
        finally {
            hang.countDown();
        }
    }

    @Test
    @Timeout(10)
    void bodyOverTheCapIsNotAvailable() throws Exception {
        byte[] oversized = new byte[2 * 1024 * 1024];
        String url = start(exchange -> {
            drain(exchange);
            exchange.sendResponseHeaders(200, oversized.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(oversized);
            }
            catch (IOException ignored) {
            }
        });
        Result result = await(new ConnectionTester().test(url, null, List.of(), OpenAiCompatibleLimits.defaults()));
        assertEquals(Kind.NOT_AVAILABLE, result.kind());
        assertEquals("N/A", result.text());
    }

    @Test
    @Timeout(5)
    void authorizationHeaderIsSentOnlyWhenAKeyIsGiven() throws Exception {
        List<String> seen = new ArrayList<>();
        String url = start(exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst("Authorization"));
            jsonHandler(200, TWO_MODELS_JSON).handle(exchange);
        });
        await(new ConnectionTester().test(url, null, List.of(), OpenAiCompatibleLimits.defaults()));
        await(new ConnectionTester().test(url, "secret".toCharArray(), List.of(), OpenAiCompatibleLimits.defaults()));
        assertEquals(2, seen.size());
        assertNull(seen.get(0));
        assertEquals("Bearer secret", seen.get(1));
    }

    @Test
    @Timeout(5)
    void requestRunsOnAProbePoolThread() throws Exception {
        String url = start(jsonHandler(200, TWO_MODELS_JSON));
        ConnectionTester tester = new ConnectionTester();
        await(tester.test(url, null, List.of(), OpenAiCompatibleLimits.defaults()));
        assertNotNull(tester.lastProbeThreadName);
        assertTrue(tester.lastProbeThreadName.startsWith("aicoder-probe-"), tester.lastProbeThreadName);
    }

    @Test
    @Timeout(5)
    void lmStudioReasoningLevelsAreReportedForEachModel() throws Exception {
        byte[] lmStudioModels = ("{\"data\":[{\"id\":\"splash\"},{\"id\":\"full\"}],"
                + "\"models\":[{\"key\":\"splash\",\"max_context_length\":4096,"
                + "\"capabilities\":{\"reasoning\":{\"allowed_options\":[\"off\",\"on\"]}}},"
                + "{\"key\":\"full\",\"max_context_length\":4096,"
                + "\"capabilities\":{\"reasoning\":{\"allowed_options\":[\"off\",\"low\",\"medium\",\"xhigh\",\"on\"]}}}]}").getBytes(StandardCharsets.UTF_8);
        String url = start(jsonHandler(200, lmStudioModels));

        Result result = await(new ConnectionTester().test(url, null, List.of(), OpenAiCompatibleLimits.defaults()));

        assertEquals(ServerKind.LMSTUDIO, result.serverKind());
        assertEquals(List.of("off", "on"), result.reasoningLevelsByModel().get("splash"));
        assertEquals(List.of("off", "low", "medium", "xhigh", "on"),
                     result.reasoningLevelsByModel().get("full"));
    }

    @Test
    @Timeout(5)
    void localOllamaTagsEnrichesTheConnectedTextWithTheKind() throws Exception {
        byte[] tags = ("{\"models\":[{\"name\":\"llama3.2:latest\",\"details\":{\"family\":\"llama\"}},"
                + "{\"name\":\"qwen2.5-coder:7b\",\"details\":{}}]}").getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/models", jsonHandler(200, TWO_MODELS_JSON));
        server.createContext("/api/tags", jsonHandler(200, tags));
        server.start();
        String url = urlOf(server);

        Result result = await(new ConnectionTester().test(url, null, List.of(), OpenAiCompatibleLimits.defaults()));

        assertEquals(Kind.CONNECTED, result.kind());
        assertEquals(2, result.modelCount());
        assertEquals("Connected · Ollama · 2 models", result.text());
        assertEquals(ServerKind.OLLAMA, result.serverKind());
    }

    @Test
    @Timeout(5)
    void lmStudioApiProbeUsesAuthorizationAndReturnsFullFacts() throws Exception {
        List<String> authorizations = new ArrayList<>();
        byte[] lmModels = ("{\"data\":[{\"id\":\"splash\"}],\"models\":[{\"key\":\"splash\","
                + "\"max_context_length\":4096,\"capabilities\":{\"tools\":true,\"reasoning\":{"
                + "\"allowed_options\":[\"low\",\"high\"]}}}]}").getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/models", jsonHandler(200, TWO_MODELS_JSON));
        server.createContext("/api/v1/models", exchange -> {
                         authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                         jsonHandler(200, lmModels).handle(exchange);
                     });
        server.start();

        Result result = await(new ConnectionTester().test(urlOf(server), "secret".toCharArray(), List.of(),
                                                          OpenAiCompatibleLimits.defaults()));

        assertEquals(List.of("Bearer secret"), authorizations);
        assertEquals(ServerKind.LMSTUDIO, result.serverKind());
        assertEquals(List.of("splash"), result.facts().models());
        assertEquals(4096, result.facts().lmStudioMaxContextLength());
        assertEquals(Boolean.TRUE, result.facts().capabilities().tools());
        assertEquals(List.of("low", "high"), result.reasoningLevelsByModel().get("splash"));
    }

    @Test
    @Timeout(5)
    void ollamaProbesPopulateCapabilitiesAndContext() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/models", jsonHandler(200, TWO_MODELS_JSON));
        server.createContext("/api/tags", jsonHandler(200,
                                                      "{\"models\":[{\"name\":\"discovered:latest\",\"details\":{}}]}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/api/ps", jsonHandler(200,
                                                    "{\"models\":[{\"name\":\"discovered:latest\",\"context_length\":4096}]}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/api/show", exchange -> {
                         assertEquals("POST", exchange.getRequestMethod());
                         assertEquals("Bearer secret", exchange.getRequestHeaders().getFirst("Authorization"));
                         jsonHandler(200, ("{\"capabilities\":[\"tools\",\"thinking\"],\"parameters\":\"num_ctx 2048\","
                                     + "\"model_info\":{\"llama.context_length\":8192}}").getBytes(StandardCharsets.UTF_8)).handle(exchange);
                     });
        server.start();

        Result result = await(new ConnectionTester().test(urlOf(server), ServerTypeEnum.OLLAMA, "chosen:latest",
                                                          "secret".toCharArray(), List.of(),
                                                          OpenAiCompatibleLimits.defaults()));

        assertEquals(ServerKind.OLLAMA, result.serverKind());
        assertEquals(List.of("discovered:latest"), result.facts().models());
        assertEquals(Boolean.TRUE, result.facts().capabilities().tools());
        assertEquals(Boolean.TRUE, result.facts().capabilities().thinking());
        assertEquals(4096, result.facts().ollamaPsContextLength());
        assertEquals(2048, result.facts().ollamaNumCtx());
        assertEquals(8192, result.facts().ollamaTrainingContextLength());
    }

    @Test
    @Timeout(5)
    void llamaCppPropsEnrichesTheDetectedWindow() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/models", jsonHandler(200, TWO_MODELS_JSON));
        server.createContext("/props", jsonHandler(200,
                                                   "{\"default_generation_settings\":{\"n_ctx\":8192}}".getBytes(StandardCharsets.UTF_8)));
        server.start();

        Result result = await(new ConnectionTester().test(urlOf(server), null, List.of(),
                                                          OpenAiCompatibleLimits.defaults()));

        assertEquals(ServerKind.LLAMACPP, result.serverKind());
        assertEquals(8192, result.facts().llamaCppNCtx());
        assertEquals(result.apiBase() + "/v1", result.facts().apiBase());
    }

    @Test
    @Timeout(5)
    void genericServerStillReturnsACompleteDetectedSnapshot() throws Exception {
        String url = start(jsonHandler(200, TWO_MODELS_JSON));

        Result result = await(new ConnectionTester().test(url, null, List.of(), OpenAiCompatibleLimits.defaults()));

        assertEquals(ServerKind.GENERIC, result.serverKind());
        assertEquals(ServerKind.GENERIC, result.facts().kind());
        assertEquals(List.of("a", "b"), result.facts().models());
        assertNotNull(result.facts().timestamp());
        assertEquals(url + "/v1", result.facts().apiBase());
    }

    @Test
    @Timeout(5)
    void explicitLmStudioDoesNotProbeOllamaEndpoints() throws Exception {
        List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
                         paths.add(exchange.getRequestURI().getPath());
                         if ("/v1/models".equals(exchange.getRequestURI().getPath())) {
                             jsonHandler(200, TWO_MODELS_JSON).handle(exchange);
                         }
                         else if ("/api/v1/models".equals(exchange.getRequestURI().getPath())) {
                             jsonHandler(200, "{\"models\":[{\"key\":\"a\",\"max_context_length\":4096}]}".getBytes(StandardCharsets.UTF_8))
                                     .handle(exchange);
                         }
                         else {
                             exchange.sendResponseHeaders(404, -1);
                         }
                     });
        server.start();

        Result result = await(new ConnectionTester().test(urlOf(server), ServerTypeEnum.LM_STUDIO, "a",
                                                          null, List.of(), OpenAiCompatibleLimits.defaults()));

        assertEquals(ServerKind.LMSTUDIO, result.serverKind());
        assertFalse(paths.contains("/api/tags"));
        assertFalse(paths.contains("/api/show"));
    }

    private String start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/models", handler);
        server.start();
        return urlOf(server);
    }

    private static String urlOf(HttpServer server) {
        InetSocketAddress address = server.getAddress();
        String host = address.getAddress().getHostAddress();
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + address.getPort();
    }

    private static HttpHandler jsonHandler(int status, byte[] body) {
        return exchange -> {
            drain(exchange);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        };
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
    }

    private static Result await(CompletableFuture<Result> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }
}
