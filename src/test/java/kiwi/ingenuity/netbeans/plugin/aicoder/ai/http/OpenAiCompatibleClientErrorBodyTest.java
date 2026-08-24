package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the error-body redaction in {@link OpenAiCompatibleClient#chat}: a non-2xx reply body can echo request content
 * (and occasionally auth material), so it must pass through {@code McpHookServerUtil.redactAllSecrets} before landing
 * in the {@link IOException} message. The fake upstream embeds the LIVE session secret of a session registered in
 * {@link SessionRegistry} — exactly what the value-based matcher looks for. Reverting the wrapper at the throw site
 * leaks the secret into the exception and turns this test red.
 */
class OpenAiCompatibleClientErrorBodyTest {

    private static final String SESSION_ID = "redact-openai-ses";

    private String secret;

    @BeforeEach
    void registerSession() {
        secret = registerLiveSession(SESSION_ID);
    }

    @AfterEach
    void unregisterSession() {
        SessionRegistry.unregister(SESSION_ID);
    }

    private static String registerLiveSession(String id) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, true, null, null, null);
        AiSession session = new AiSession(id, "RedactionProbe", null, AiTypeEnum.OLLAMA_LOCAL, null,
                settings, Instant.now(), Instant.now());
        SessionRegistry.register(new AbstractAiSession(session) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Map getMcpToolHandlers() {
                return Map.of();
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }
        });
        return session.secret();
    }

    @Test
    void errorBody_echoingSessionSecret_isRedactedInException() throws Exception {
        assertTrue(secret != null && !secret.isBlank(), "probe session must have a real secret");

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        byte[] body = ("{\"error\":\"session " + secret + " went sideways\"}")
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                    + ":" + server.getAddress().getPort();
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(HttpClient.newHttpClient());
            ChatRequest request = new ChatRequest(baseUrl, null, "test-model",
                    List.of(new ChatMessage(ChatRole.USER, "hi", null, null)), null);

            IOException failure = assertThrows(IOException.class, () -> client.chat(request, s -> {
            }));

            assertTrue(failure.getMessage().contains("HTTP 500"),
                    "the status line context must survive: " + failure.getMessage());
            assertFalse(failure.getMessage().contains(secret),
                    "the error body must be redacted before it reaches the exception");
        }
        finally {
            server.stop(0);
        }
    }
}
