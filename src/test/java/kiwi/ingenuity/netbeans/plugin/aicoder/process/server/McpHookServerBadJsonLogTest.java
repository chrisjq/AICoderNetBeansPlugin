package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the redaction of the hook server's "Hook: bad JSON" warning: a malformed POST body can contain the session
 * secretKey verbatim (an MCP client pasting a broken request that still carried credentials), so the log site must
 * route the body through {@code McpHookServerUtil.redactAllSecrets}. The probe registers a live session and embeds its
 * secret in deliberately-broken JSON; reverting the wrapper logs the secret raw and turns this test red. The dispatch
 * is driven by invoking the private {@code handle} on a stub exchange, so no socket is opened and no connection pool is
 * touched.
 */
class McpHookServerBadJsonLogTest {

    private static final String SESSION_ID = "redact-hookbadjson-ses";

    private String secret;
    private WarningCapture warnings;

    @BeforeEach
    void registerSessionAndAttachCapture() {
        secret = registerLiveSession(SESSION_ID);
        warnings = new WarningCapture();
        Logger.getLogger(McpHookServer.class.getName()).addHandler(warnings);
    }

    @AfterEach
    void detachCaptureAndUnregister() {
        Logger.getLogger(McpHookServer.class.getName()).removeHandler(warnings);
        SessionRegistry.unregister(SESSION_ID);
    }

    private static String registerLiveSession(String id) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, true, null, null, null);
        AiSession session = new AiSession(id, "RedactionProbe", null, AiTypeEnum.CLAUDE, null, settings,
                Instant.now(), Instant.now());
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

    private static class WarningCapture extends Handler {

        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        boolean anyContains(String fragment) {
            return records.stream().map(WarningCapture::render)
                    .anyMatch(text -> text.contains(fragment));
        }

        private static String render(LogRecord record) {
            String text = String.valueOf(record.getMessage());
            Object[] params = record.getParameters();
            if (params != null) {
                for (Object param : params) {
                    text += " " + param;
                }
            }
            return text;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /**
     * Minimal POST exchange whose body is fixed bytes; captures the sent response code.
     */
    private static final class PostExchange extends HttpExchange {

        private final byte[] requestBody;
        private final Headers headers = new Headers();
        private int responseCode = -1;

        PostExchange(String body) {
            this.requestBody = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Headers getRequestHeaders() {
            return headers;
        }

        @Override
        public Headers getResponseHeaders() {
            return headers;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("http://localhost/");
        }

        @Override
        public String getRequestMethod() {
            return "POST";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(requestBody);
        }

        @Override
        public OutputStream getResponseBody() {
            return new ByteArrayOutputStream();
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            responseCode = rCode;
        }

        int sentResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }

    @Test
    void badJsonBody_carryingSessionSecret_isLoggedRedacted() throws Exception {
        assertTrue(secret != null && !secret.isBlank(), "probe session must have a real secret");

        McpHookServer server = new McpHookServer(0);
        Method handle = McpHookServer.class.getDeclaredMethod("handle", HttpExchange.class);
        handle.setAccessible(true);

        PostExchange exchange = new PostExchange("{\"oops " + secret);
        handle.invoke(server, exchange);

        assertEquals(400, exchange.sentResponseCode(), "broken JSON must be rejected cleanly");
        assertTrue(warnings.anyContains("Hook: bad JSON"),
                "the malformed POST must still be reported");
        assertFalse(warnings.anyContains(secret),
                "the raw session secret must never reach the log record");
    }
}
