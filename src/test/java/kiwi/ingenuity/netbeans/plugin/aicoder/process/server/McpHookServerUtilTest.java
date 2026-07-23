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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpHookServerUtilTest {

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
     * Tool-use logging now runs for in-process callers too, and Ollama's bridge
     * injects credentials into every call's arguments — so the session secret
     * must never reach the IDE log. sessionId stays: it is not secret and is
     * needed to correlate entries.
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
            args.addProperty("secretKey", "super-secret-value");
            args.addProperty("filePath", "/tmp/Foo.java");

            McpHookServerUtil.logToolUse("OllamaTest", "GetFileContent", args);

            assertEquals(1, captured.size());
            String line = captured.get(0);
            assertFalse(line.contains("super-secret-value"), "secret leaked into the log: " + line);
            assertTrue(line.contains("secretKey[***]"));
            assertTrue(line.contains("sessionId[sid-1234]"), "sessionId should remain for correlation");
            assertTrue(line.contains("/tmp/Foo.java"), "ordinary arguments must still be logged");
        }
        finally {
            logger.removeHandler(capture);
            PluginSettings.setLogToolUse(previous);
        }
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
