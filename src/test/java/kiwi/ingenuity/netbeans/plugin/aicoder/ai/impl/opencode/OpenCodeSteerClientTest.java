package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link OpenCodeSteerClient}. The pure methods are asserted directly; the HTTP methods run against a real
 * loopback {@code HttpServer} rather than a mock, so status handling and body encoding are exercised end to end.
 */
class OpenCodeSteerClientTest {

    /**
     * Stand-in for the per-process secret. The fake server below does not enforce auth — these tests cover status
     * handling and body encoding, not opencode's authentication, which was verified against the real agent
     * (unauthenticated /doc and POST both answer 401; with credentials, 200).
     */
    private static final String TEST_PASSWORD = "test-password";

    private HttpServer server;
    private int port;
    private OpenCodeSteerClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        client = new OpenCodeSteerClient();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // -- pickFreePort ----------------------------------------------------
    @Test
    void pickFreePortReturnsUsablePort() throws Exception {
        int p = OpenCodeSteerClient.pickFreePort();
        assertTrue(p > 0 && p <= 65535, "Port out of range: " + p);
    }

    @Test
    void pickFreePortIsNotColliding() throws Exception {
        int p = OpenCodeSteerClient.pickFreePort();
        try (var ss = new java.net.ServerSocket(p)) {
            // success -- port was free right after pick
        }
    }

    // -- docDeclaresSteerRoute -------------------------------------------
    @Test
    void docDeclaresSteerRouteWithTemplatedPath() {
        String doc = "{\"openapi\":\"3.0.0\",\"paths\":{"
                + "\"/api/session/{sessionID}/prompt\":{"
                + "\"post\":{\"summary\":\"steer\"}}}}";
        assertTrue(OpenCodeSteerClient.docDeclaresSteerRoute(doc));
    }

    @Test
    void docDeclaresSteerRouteWithAlternativeBraceStyle() {
        String doc = "{\"paths\":{"
                + "\"/api/session/{session_id}/prompt\":{"
                + "\"post\":{}}}}";
        assertTrue(OpenCodeSteerClient.docDeclaresSteerRoute(doc));
    }

    @Test
    void docDeclaresSteerRouteWithGenericPlaceholder() {
        String doc = "{\"paths\":{"
                + "\"/api/session/{id}/prompt\":{"
                + "\"post\":{}}}}";
        assertTrue(OpenCodeSteerClient.docDeclaresSteerRoute(doc));
    }

    @Test
    void docDeclaresSteerRouteFalseWhenRouteAbsent() {
        String doc = "{\"paths\":{"
                + "\"/api/session/{sessionID}/messages\":{"
                + "\"post\":{}}}}";
        assertFalse(OpenCodeSteerClient.docDeclaresSteerRoute(doc));
    }

    @Test
    void docDeclaresSteerRouteFalseWhenPathsEmpty() {
        String doc = "{\"paths\":{}}";
        assertFalse(OpenCodeSteerClient.docDeclaresSteerRoute(doc));
    }

    @Test
    void docDeclaresSteerRouteFalseOnEmptyInput() {
        assertFalse(OpenCodeSteerClient.docDeclaresSteerRoute(""));
    }

    @Test
    void docDeclaresSteerRouteFalseOnNonJson() {
        assertFalse(OpenCodeSteerClient.docDeclaresSteerRoute("not json at all"));
    }

    @Test
    void docDeclaresSteerRouteFalseOnNull() {
        assertFalse(OpenCodeSteerClient.docDeclaresSteerRoute(null));
    }

    // -- buildSteerBody --------------------------------------------------
    @Test
    void buildSteerBodyProducesCorrectStructure() {
        String body = OpenCodeSteerClient.buildSteerBody("hello");
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        assertTrue(parsed.has("prompt"));
        JsonObject prompt = parsed.getAsJsonObject("prompt");
        assertTrue(prompt.has("text"));
        assertEquals("hello", prompt.get("text").getAsString());
        assertEquals("steer", parsed.get("delivery").getAsString());
    }

    @Test
    void buildSteerBodyEscapesDoubleQuotes() {
        String body = OpenCodeSteerClient.buildSteerBody("say \"hi\"");
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("say \"hi\"", parsed.getAsJsonObject("prompt").get("text").getAsString());
    }

    @Test
    void buildSteerBodyEscapesNewlines() {
        String body = OpenCodeSteerClient.buildSteerBody("line1\nline2\r\nline3");
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("line1\nline2\r\nline3",
                parsed.getAsJsonObject("prompt").get("text").getAsString());
    }

    @Test
    void buildSteerBodyEscapesBackslash() {
        String body = OpenCodeSteerClient.buildSteerBody("path\\to\\file");
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("path\\to\\file",
                parsed.getAsJsonObject("prompt").get("text").getAsString());
    }

    @Test
    void buildSteerBodyHandlesNonAscii() {
        String body = OpenCodeSteerClient.buildSteerBody("Japanese \u65e5\u672c\u8a9e \u00e9\u00e8\u00ea");
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("Japanese \u65e5\u672c\u8a9e \u00e9\u00e8\u00ea",
                parsed.getAsJsonObject("prompt").get("text").getAsString());
    }

    @Test
    void buildSteerBodyEmptyString() {
        String body = OpenCodeSteerClient.buildSteerBody("");
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("", parsed.getAsJsonObject("prompt").get("text").getAsString());
    }

    // -- steerUrl --------------------------------------------------------
    @Test
    void steerUrlBuildsCorrectPath() {
        String url = OpenCodeSteerClient.steerUrl(4096, "ses_abc123");
        assertEquals("http://127.0.0.1:4096/api/session/ses_abc123/prompt", url);
    }

    @Test
    void steerUrlBuildsCorrectPathForRealId() {
        String url = OpenCodeSteerClient.steerUrl(4096, "ses_fcd33f985ffe1EItKyU10dnjaf");
        assertEquals("http://127.0.0.1:4096/api/session/ses_fcd33f985ffe1EItKyU10dnjaf/prompt", url);
    }

    @Test
    void steerUrlRejectsSlashAndSpace() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenCodeSteerClient.steerUrl(8080, "ses/a b/c"));
    }

    @Test
    void steerUrlRejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenCodeSteerClient.steerUrl(8080, "../../admin"));
    }

    @Test
    void steerUrlRejectsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenCodeSteerClient.steerUrl(8080, null));
    }

    // -- probeSteerCapability (live HTTP) --------------------------------
    @Test
    void probeSteerCapabilityTrueWhenRoutePresent() {
        String docJson = "{\"openapi\":\"3.0.0\",\"paths\":{"
                + "\"/api/session/{sessionID}/prompt\":{"
                + "\"post\":{}}}}";
        server.createContext("/doc", ex -> {
            byte[] body = docJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        assertTrue(client.probeSteerCapability(port, TEST_PASSWORD));
    }

    @Test
    void probeSteerCapabilityFalseWhenRouteAbsent() {
        String docJson = "{\"paths\":{}}";
        server.createContext("/doc", ex -> {
            byte[] body = docJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        assertFalse(client.probeSteerCapability(port, TEST_PASSWORD));
    }

    @Test
    void probeSteerCapabilityFalseOn404() {
        server.createContext("/doc", ex -> {
            ex.sendResponseHeaders(404, -1);
        });
        assertFalse(client.probeSteerCapability(port, TEST_PASSWORD));
    }

    @Test
    void probeSteerCapabilityFalseOnMalformedJson() {
        server.createContext("/doc", ex -> {
            byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        assertFalse(client.probeSteerCapability(port, TEST_PASSWORD));
    }

    @Test
    void probeSteerCapabilityFalseOnConnectionRefused() {
        assertFalse(client.probeSteerCapability(1, TEST_PASSWORD));
    }

    // -- sendSteer (live HTTP) -------------------------------------------
    /**
     * The agent must receive the right path and body. Awaited rather than read straight after the call: the request
     * is dispatched asynchronously, so asserting immediately would race the server thread and pass or fail on timing.
     */
    @Test
    void sendSteerPostsSteerBodyToTheSessionPromptRoute() throws InterruptedException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        server.createContext("/api/session/ses_test123/prompt", ex -> {
            receivedPath.set(ex.getRequestURI().toString());
            try (var is = ex.getRequestBody()) {
                receivedBody.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
            ex.sendResponseHeaders(200, -1);
            received.countDown();
        });

        assertTrue(client.sendSteer(port, "ses_test123", "test message", TEST_PASSWORD));

        assertTrue(received.await(5, TimeUnit.SECONDS), "server never received the steer");
        assertEquals("/api/session/ses_test123/prompt", receivedPath.get());
        JsonObject parsed = JsonParser.parseString(receivedBody.get()).getAsJsonObject();
        assertEquals("test message", parsed.getAsJsonObject("prompt").get("text").getAsString());
        assertEquals("steer", parsed.get("delivery").getAsString());
    }

    /**
     * The result means DISPATCHED, not delivered — so a rejection still reports true.
     *
     * <p>This looks wrong until you know why: the real endpoint does not answer at all while a turn is running, which
     * is the only time a steer is worth sending. Waiting for a status meant blocking for the whole turn, and bounding
     * that wait meant closing the connection and possibly discarding a steer the agent had already accepted. Delivery
     * is confirmed by the peer acting on it, never by this return value.
     */
    @Test
    void sendSteerReportsDispatchEvenWhenTheServerRejectsIt() throws InterruptedException {
        CountDownLatch received = new CountDownLatch(1);
        server.createContext("/api/session/ses_x/prompt", ex -> {
            ex.sendResponseHeaders(422, -1);
            received.countDown();
        });
        assertTrue(client.sendSteer(port, "ses_x", "text", TEST_PASSWORD));
        assertTrue(received.await(5, TimeUnit.SECONDS), "server never received the steer");
    }

    /**
     * Even a connection that cannot be established reports dispatch, because the failure surfaces asynchronously
     * after this method has returned. Documented as a known limitation rather than hidden: the caller treats a steer
     * as best-effort and the message is still delivered by the end-of-turn inbox flush regardless.
     */
    @Test
    void sendSteerCannotReportAConnectionFailureSynchronously() {
        assertTrue(client.sendSteer(1, "ses_x", "text", TEST_PASSWORD));
    }

    /**
     * A malformed session id is the one failure that IS knowable synchronously — it never reaches the network.
     */
    @Test
    void sendSteerReportsFailureForAnUnusableSessionId() {
        assertFalse(client.sendSteer(port, "ses/../../admin", "text", TEST_PASSWORD));
    }
}
