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
