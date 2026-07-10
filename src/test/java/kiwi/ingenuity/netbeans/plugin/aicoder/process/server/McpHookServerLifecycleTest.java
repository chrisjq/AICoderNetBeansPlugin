package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * {@link McpHookServer} stop()/init() robustness: a stopped server must always
 * report {@code isStopped()} and never NPE on a double stop, a stop after a
 * failed init, or a stop whose underlying {@code httpServer.stop} throws.
 */
class McpHookServerLifecycleTest {

    @Test
    void stopTwiceDoesNotThrow() throws Exception {
        McpHookServer s = new McpHookServer(0);
        s.init();
        s.start();
        assertDoesNotThrow(s::stop);
        assertDoesNotThrow(s::stop);
        assertTrue(s.isStopped());
    }

    @Test
    void stopAfterFailedInitDoesNotThrow() throws Exception {
        // Occupy a loopback port so init()'s HttpServer.create fails to bind.
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = occupied.getLocalPort();
            McpHookServer s = new McpHookServer(port);
            assertThrows(IOException.class, s::init);
            assertDoesNotThrow(s::stop);
            assertTrue(s.isStopped());
        }
    }

    @Test
    void stoppedObservableWhenHttpServerStopThrows() throws Exception {
        McpHookServer s = new McpHookServer(0);
        s.init();
        s.start();
        // Swap in an httpServer whose stop(int) throws, to prove stopped is set
        // first (the original bug set it only after a successful stop).
        Field f = McpHookServer.class.getDeclaredField("httpServer");
        f.setAccessible(true);
        f.set(s, throwingHttpServer());

        assertDoesNotThrow(s::stop);
        assertTrue(s.isStopped(), "stopped must be observable even when httpServer.stop throws");
    }

    @Test
    void getBaseUrlAndPortSurviveStop() throws Exception {
        McpHookServer s = new McpHookServer(0);
        s.init();
        String baseUrl = s.getBaseUrl();
        int port = s.getPort();
        assertNotNull(baseUrl);
        s.start();
        s.stop();
        // stop() nulls the underlying httpServer; captured values must survive.
        assertEquals(baseUrl, s.getBaseUrl(), "baseUrl must remain valid after stop()");
        assertEquals(port, s.getPort(), "port must remain valid after stop()");
    }

    /**
     * A minimal HttpServer stub whose stop(int) throws; all other operations
     * are unsupported (never called by McpHookServer.stop()).
     */
    private static HttpServer throwingHttpServer() {
        return new HttpServer() {
            @Override
            public void stop(int delay) {
                throw new RuntimeException("boom");
            }

            @Override
            public void bind(InetSocketAddress addr, int backlog) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void start() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setExecutor(Executor executor) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Executor getExecutor() {
                throw new UnsupportedOperationException();
            }

            @Override
            public HttpContext createContext(String path, HttpHandler handler) {
                throw new UnsupportedOperationException();
            }

            @Override
            public HttpContext createContext(String path) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void removeContext(String path) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void removeContext(HttpContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InetSocketAddress getAddress() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
