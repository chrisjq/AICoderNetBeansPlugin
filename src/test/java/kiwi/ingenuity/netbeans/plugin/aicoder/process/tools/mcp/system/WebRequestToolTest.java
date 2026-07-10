package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WebRequestToolTest {

    private static HttpServer server;
    private static String baseUrl;

    private static final class FakeSession extends AbstractAiSession {

        FakeSession(AiSession s) {
            super(s);
        }

        @Override
        public String getId() {
            return getAiSession().id();
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", WebRequestToolTest::handleEcho);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesPostRequestWithHeadersAndBody() throws Exception {
        WebRequestTool tool = new WebRequestTool();
        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), baseUrl + "/echo");
        args.addProperty(WebRequestParamEnum.METHOD.key(), "POST");
        args.addProperty(WebRequestParamEnum.BODY.key(), "hello world");
        JsonObject headers = new JsonObject();
        headers.addProperty("X-Test", "demo");
        args.add(WebRequestParamEnum.HEADERS.key(), headers);
        args.addProperty(WebRequestParamEnum.MAX_CHARS.key(), 1000);

        String raw = tool.handle(args(args), new FakeSession(AiSession.create(null, AiTypeEnum.CLAUDE)));
        JsonObject out = JsonParser.parseString(raw).getAsJsonObject();

        assertEquals(200, out.get("status").getAsInt());
        assertEquals("POST", out.get("method").getAsString());
        assertFalse(out.get("truncated").getAsBoolean());
        String body = out.get("body").getAsString();
        assertTrue(body.contains("method=POST"));
        assertTrue(body.contains("header=demo"));
        assertTrue(body.contains("body=hello world"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        WebRequestTool tool = new WebRequestTool();
        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), "file:///tmp/test.txt");

        Exception ex = assertThrows(Exception.class,
                () -> tool.handle(args(args), new FakeSession(AiSession.create(null, AiTypeEnum.CLAUDE))));
        assertTrue(ex.getMessage().contains("Only http:// and https:// URLs are supported"));
    }

    private static ToolRequestArguments args(JsonObject object) {
        return new ToolRequestArguments(object);
    }

    private static void handleEcho(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        String response = "method=" + exchange.getRequestMethod()
                + ";header=" + exchange.getRequestHeaders().getFirst("X-Test")
                + ";body=" + new String(body, StandardCharsets.UTF_8);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
