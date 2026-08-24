package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpHookServerUtilTest {

    private static String registerLiveSessionAndGetSecret(String id) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, true, null, null, null);
        AiSession session = new AiSession(id, "TestSession", null, AiTypeEnum.CLAUDE, null, settings,
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

    @Test
    void sendJsonIgnoresPeerDisconnects() {
        HttpExchange exchange = new ThrowingHttpExchange(
                new IOException("Connection reset by peer"), null);

        assertDoesNotThrow(() -> McpHookServerUtil.sendJson(exchange, 200, "{}"));
    }

    @Test
    void sendJsonPropagatesOtherIoFailures() {
        HttpExchange exchange = new ThrowingHttpExchange(
                new IOException("unexpected write failure"), null);

        assertThrows(IOException.class, () -> McpHookServerUtil.sendJson(exchange, 200, "{}"));
    }

    @Test
    void sendJsonIgnoresBrokenPipeWhileWritingBody() {
        HttpExchange exchange = new ThrowingHttpExchange(
                null, new IOException("Broken pipe"));

        assertDoesNotThrow(() -> McpHookServerUtil.sendJson(exchange, 200, "{}"));
    }

    /**
     * Tool-use logging now runs for in-process callers too, and Ollama's bridge injects credentials into every call's
     * arguments — so the session secret must never reach the IDE log. sessionId stays: it is not secret and is needed
     * to correlate entries.
     */
    @Test
    void logToolUseRedactsTheSecretKey() {
        boolean previous = PluginSettings.isLogToolUse();
        Logger logger = Logger.getLogger(McpHookServerUtil.class.getName());
        List<String> captured = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            PluginSettings.setLogToolUse(true);
            JsonObject args = new JsonObject();
            args.addProperty("sessionId", "sid-1234");
            args.addProperty(McpToolPropertyEnum.SECRET_KEY.key(), "super-secret-value");
            args.addProperty("filePath", "/tmp/Foo.java");

            McpHookServerUtil.logToolUse("OllamaTest", "GetFileContent", args);

            assertEquals(1, captured.size());
            String line = captured.get(0);
            assertFalse(line.contains("super-secret-value"), "secret leaked into the log: " + line);
            assertTrue(line.contains(McpToolPropertyEnum.SECRET_KEY.key() + "[***]"));
            assertTrue(line.contains("sessionId[sid-1234]"), "sessionId should remain for correlation");
            assertTrue(line.contains("/tmp/Foo.java"), "ordinary arguments must still be logged");
        }
        finally {
            logger.removeHandler(capture);
            PluginSettings.setLogToolUse(previous);
        }
    }

    @Test
    void addCorsAllowsOnlyLocalBrowserOrigins() {
        assertTrue(McpHookServerUtil.isAllowedCorsOrigin("http://localhost:3000"));
        assertTrue(McpHookServerUtil.isAllowedCorsOrigin("https://127.0.0.1:8443"));
        assertTrue(McpHookServerUtil.isAllowedCorsOrigin("http://[::1]:5173"));
        assertFalse(McpHookServerUtil.isAllowedCorsOrigin("https://example.com"));
        assertFalse(McpHookServerUtil.isAllowedCorsOrigin("http://127.evil.test"));
        assertFalse(McpHookServerUtil.isAllowedCorsOrigin("http://127.0.0.999"));
        assertFalse(McpHookServerUtil.isAllowedCorsOrigin("file:///tmp/index.html"));
        assertFalse(McpHookServerUtil.isAllowedCorsOrigin("null"));
    }

    @Test
    void redactSecretsMasksWellFormedSecretKeyValue() {
        String secret = "super-secret-value";
        String body = "{\"arguments\":{\""
                + McpToolPropertyEnum.SECRET_KEY.key()
                + "\":\"" + secret + "\",\"filePath\":\"/tmp/Foo.java\"}}";

        String redacted = McpHookServerUtil.redactSecrets(body);

        assertFalse(redacted.contains(secret), "secret leaked into redacted body: " + redacted);
        assertTrue(redacted.contains("\"" + McpToolPropertyEnum.SECRET_KEY.key() + "\":\"***\""));
        assertTrue(redacted.contains("/tmp/Foo.java"), "ordinary body content must remain");
    }

    /**
     * Reproduces the live leak reported against ClaudeAiProcessManager: every tool_use block in Claude's assistant
     * stream carries this session's secretKey as one of the tool's arguments, and the "ai json" debug log used to write
     * that stream verbatim.
     */
    @Test
    void redactSecretsMasksSecretKeyInAssistantStreamToolUseBlock() {
        String secret = "live-session-secret";
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                + "\"name\":\"GetFileContent\",\"input\":{\"sessionId\":\"sid-1234\",\""
                + McpToolPropertyEnum.SECRET_KEY.key() + "\":\"" + secret + "\",\"filePath\":\"/tmp/Foo.java\"}}]}}";

        String redacted = McpHookServerUtil.redactSecrets(line);

        assertFalse(redacted.contains(secret), "secret leaked into redacted assistant stream line: " + redacted);
        assertTrue(redacted.contains("\"" + McpToolPropertyEnum.SECRET_KEY.key() + "\":\"***\""));
        assertTrue(redacted.contains("sid-1234"), "sessionId should remain for correlation");
        assertTrue(redacted.contains("/tmp/Foo.java"), "ordinary tool arguments must still be logged");
    }

    /**
     * The value-based mechanism that supersedes the earlier per-shape fixes (Ollama's double-encoded JSON, Copilot's
     * Map.toString() extensionData): a live secret is a live secret regardless of what shape surrounds it. A fixed
     * pattern only catches the shapes someone anticipated — three separate leak sites in one day used three different
     * shapes, which is exactly this blind spot.
     */
    @Test
    void redactAllSecretsMasksARegisteredSecretRegardlessOfSurroundingShape() {
        String id = "redact-value-test-session";
        String secret = registerLiveSessionAndGetSecret(id);
        try {
            String nativeJson = "{\"input\":{\"sessionId\":\"sid-1\",\"secretKey\":\"" + secret + "\"}}";
            String escapedJson = "{\"function\":{\"arguments\":\"{\\\"secretKey\\\":\\\"" + secret + "\\\"}\"}}";
            String mapToString = "extensionData={serverName=aicoder-nb-ki-plugin, args={sessionId=sid-1, secretKey="
                    + secret + "}}";
            String bareOccurrence = "unexpected token " + secret + " while validating the request";

            for (String text : List.of(nativeJson, escapedJson, mapToString, bareOccurrence)) {
                String redacted = McpHookServerUtil.redactAllSecrets(text);
                assertFalse(redacted.contains(secret), "secret leaked through shape [" + text + "] -> " + redacted);
                assertTrue(redacted.contains("***"), "expected a mask in: " + redacted);
            }
        }
        finally {
            SessionRegistry.unregister(id);
        }
    }

    /**
     * Value-based redaction cannot mask a secret it does not know about — a session that never registered, already
     * unregistered, or a value this plugin never issued. redactAllSecrets must still catch a secretKey-shaped pattern
     * through the {@link McpHookServerUtil#redactSecrets} backstop.
     */
    @Test
    void redactAllSecretsStillCatchesAnUnknownSecretViaThePatternBackstop() {
        String secret = "not-a-registered-session-secret";
        String body = "{\"secretKey\":\"" + secret + "\"}";

        String redacted = McpHookServerUtil.redactAllSecrets(body);

        assertFalse(redacted.contains(secret), "unknown secret leaked: " + redacted);
    }

    @Test
    void maskIfLongEnoughIgnoresBlankOrShortValues() {
        assertEquals("hello world", McpHookServerUtil.maskIfLongEnough("hello world", ""));
        assertEquals("hello world", McpHookServerUtil.maskIfLongEnough("hello world", "short"));
        assertEquals("hello world", McpHookServerUtil.maskIfLongEnough("hello world", null));
    }

    @Test
    void maskIfLongEnoughMasksAGenuinelyLongSecret() {
        String secret = "a-genuinely-long-enough-secret-value";

        assertEquals("prefix *** suffix", McpHookServerUtil.maskIfLongEnough("prefix " + secret + " suffix", secret));
    }

    @Test
    void redactSecretsMasksTruncatedSecretKeyValue() {
        String secret = "super-secret-value";
        String body = "{\"arguments\":{\""
                + McpToolPropertyEnum.SECRET_KEY.key()
                + "\":\"" + secret + ",\"filePath\":\"/tmp/Foo.java\"}}";

        String redacted = McpHookServerUtil.redactSecrets(body);

        assertFalse(redacted.contains(secret), "secret leaked into redacted malformed body: " + redacted);
        assertTrue(redacted.contains("\"" + McpToolPropertyEnum.SECRET_KEY.key() + "\":\"***"));
        assertTrue(redacted.contains("/tmp/Foo.java"), "ordinary body content after the malformed secret must remain");
    }

    private static final class ThrowingHttpExchange extends HttpExchange {

        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final IOException headerFailure;
        private final IOException bodyFailure;
        private int responseCode = -1;

        private ThrowingHttpExchange(IOException headerFailure, IOException bodyFailure) {
            this.headerFailure = headerFailure;
            this.bodyFailure = bodyFailure;
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("http://localhost/test");
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
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody() {
            if (bodyFailure == null) {
                return new ByteArrayOutputStream();
            }
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw bodyFailure;
                }
            };
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
            responseCode = rCode;
            if (headerFailure != null) {
                throw headerFailure;
            }
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
}
