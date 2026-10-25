package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ProbeClient.Kind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ProbeClient.ProbeResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ProbeClientTest {

    private interface Script {

        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class FakeServer implements AutoCloseable {

        final HttpServer server;
        final ExecutorService pool = Executors.newCachedThreadPool();
        final List<String> authorization = new CopyOnWriteArrayList<>();
        final List<String> methods = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> contentTypes = new CopyOnWriteArrayList<>();

        FakeServer(Script script) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(pool);
            server.createContext("/", exchange -> {
                             authorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                             contentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
                             methods.add(exchange.getRequestMethod());
                             bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                             try {
                                 script.handle(exchange);
                             }
                             catch (Exception ignored) {
                                 // a closed or interrupted fake exchange ends the script
                             }
                             finally {
                                 exchange.close();
                             }
                         });
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    private static OpenAiCompatibleLimits limits() {
        return OpenAiCompatibleLimits.defaults().withProbeTimeout(Duration.ofMillis(300));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static ProbeResult get(FakeServer server, char[] key) throws Exception {
        return new ProbeClient().get(server.url("/v1/models"), key, limits()).get(5, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(10)
    void a200WithJsonIsOkAndCarriesTheParsedBody() throws Exception {
        try (FakeServer server = new FakeServer(exchange -> reply(exchange, 200, "{\"data\":[{\"id\":\"a\"}]}"))) {
            ProbeResult result = get(server, null);

            assertEquals(Kind.OK, result.kind());
            assertEquals(200, result.status());
            assertNotNull(result.json());
            assertEquals("a", result.json().getAsJsonObject().getAsJsonArray("data").get(0).getAsJsonObject()
                         .get("id").getAsString());
            assertEquals("GET", server.methods.get(0));
        }
    }

    @Test
    @Timeout(10)
    void a200WithANonJsonBodyIsOkWithNoJson() throws Exception {
        try (FakeServer server = new FakeServer(exchange -> reply(exchange, 200, "<html>hello</html>"))) {
            ProbeResult result = get(server, null);

            assertEquals(Kind.OK, result.kind());
            assertNull(result.json());
        }
    }

    @Test
    @Timeout(10)
    void statusesMapToTheirKinds() throws Exception {
        int[][] expected = {{401, 0}, {403, 0}, {404, 1}, {405, 1}, {429, 2}, {500, 3}, {400, 3}};
        Kind[] kinds = {Kind.UNAUTHORIZED, Kind.NOT_FOUND, Kind.RATE_LIMITED, Kind.FAILED};
        for (int[] pair : expected) {
            try (FakeServer server = new FakeServer(exchange -> reply(exchange, pair[0], "{\"error\":\"x\"}"))) {
                ProbeResult result = get(server, null);

                assertEquals(kinds[pair[1]], result.kind(), "status " + pair[0]);
                assertEquals(pair[0], result.status());
            }
        }
    }

    @Test
    @Timeout(10)
    void headersThenAStalledBodyIsFailedWithinTheProbeTimeout() throws Exception {
        try (FakeServer server = new FakeServer(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"data\":[".getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(5000);
        })) {
            long start = System.nanoTime();
            ProbeResult result = get(server, null);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(Kind.FAILED, result.kind());
            assertTrue(elapsedMs < 1300, "took " + elapsedMs + " ms");
        }
    }

    @Test
    @Timeout(10)
    void silenceBeforeTheHeadersIsUnreachableWithinTheProbeTimeout() throws Exception {
        try (FakeServer server = new FakeServer(exchange -> Thread.sleep(5000))) {
            long start = System.nanoTime();
            ProbeResult result = get(server, null);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(Kind.UNREACHABLE, result.kind());
            assertTrue(elapsedMs < 1300, "took " + elapsedMs + " ms");
        }
    }

    @Test
    @Timeout(10)
    void anOversizedBodyIsFailed() throws Exception {
        byte[] big = new byte[2 << 20];
        Arrays.fill(big, (byte) 'x');
        try (FakeServer server = new FakeServer(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, big.length);
            exchange.getResponseBody().write(big);
        })) {
            ProbeResult result = get(server, null);

            assertEquals(Kind.FAILED, result.kind());
        }
    }

    @Test
    @Timeout(10)
    void aRefusedConnectionIsUnreachable() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        ProbeResult result = new ProbeClient().get("http://127.0.0.1:" + port + "/v1/models", null, limits())
                .get(5, TimeUnit.SECONDS);

        assertEquals(Kind.UNREACHABLE, result.kind());
        assertEquals(0, result.status());
    }

    @Test
    @Timeout(10)
    void aBadUrlIsFailed() throws Exception {
        ProbeResult result = new ProbeClient().get("not a url", null, limits()).get(5, TimeUnit.SECONDS);

        assertEquals(Kind.FAILED, result.kind());
    }

    @Test
    @Timeout(10)
    void theBearerHeaderIsSentOnlyWhenAKeyIsGiven() throws Exception {
        try (FakeServer server = new FakeServer(exchange -> reply(exchange, 200, "{}"))) {
            get(server, null);
            get(server, "sk-9".toCharArray());
            get(server, new char[0]);

            assertEquals(List.of("null", "Bearer sk-9", "null"), server.authorization);
        }
    }

    @Test
    @Timeout(10)
    void aPostSendsItsJsonBody() throws Exception {
        try (FakeServer server = new FakeServer(exchange -> reply(exchange, 200, "{\"ok\":true}"))) {
            ProbeResult result = new ProbeClient()
                    .post(server.url("/api/show"), "{\"model\":\"m\"}", "k".toCharArray(), limits())
                    .get(5, TimeUnit.SECONDS);

            assertEquals(Kind.OK, result.kind());
            assertEquals("POST", server.methods.get(0));
            assertEquals("{\"model\":\"m\"}", server.bodies.get(0));
            assertEquals("application/json", server.contentTypes.get(0));
            assertEquals("Bearer k", server.authorization.get(0));
            assertTrue(result.json().getAsJsonObject().get("ok").getAsBoolean());
        }
    }

    @Test
    @Timeout(10)
    void theProbeRunsOnTheProbePool() throws Exception {
        try (FakeServer server = new FakeServer(exchange -> reply(exchange, 200, "{}"))) {
            ProbeClient client = new ProbeClient();
            client.get(server.url("/v1/models"), null, limits()).get(5, TimeUnit.SECONDS);

            assertNotNull(client.lastProbeThreadName);
            assertTrue(client.lastProbeThreadName.startsWith("aicoder-probe-"), client.lastProbeThreadName);
        }
    }
}
