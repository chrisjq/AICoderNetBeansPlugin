package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AiMcpRegistrar} identity fields and the minimal
 * subclass contract used by {@link McpServerRegistry}.
 */
class AiMcpRegistrarTest {

    private static final class StubRegistrar extends AiMcpRegistrar {

        String lastEndpoint;
        String lastBaseUrl;
        boolean removeCalled;
        boolean unregisterCalled;
        boolean hooksOk = true;

        StubRegistrar(String sessionId, AiTypeEnum type) {
            super(sessionId, type);
        }

        @Override
        public void addMcpEndpoint(String endpointUrl) {
            lastEndpoint = endpointUrl;
        }

        @Override
        public void removeMcpEndpoint() {
            removeCalled = true;
        }

        @Override
        public boolean registerHooks(String serverBaseUrl) {
            lastBaseUrl = serverBaseUrl;
            return hooksOk;
        }

        @Override
        public void unregisterHooks() {
            unregisterCalled = true;
        }
    }

    @Test
    void constructor_storesSessionIdAndAiType() {
        StubRegistrar r = new StubRegistrar("sess-1", AiTypeEnum.GROK);
        assertEquals("sess-1", r.getSessionId());
        assertEquals(AiTypeEnum.GROK, r.getAiType());
    }

    @Test
    void lifecycleCallbacks_areInvokedWithArgs() {
        StubRegistrar r = new StubRegistrar("sess-2", AiTypeEnum.CLAUDE);

        r.addMcpEndpoint("http://127.0.0.1:6969/mcp/claude");
        assertEquals("http://127.0.0.1:6969/mcp/claude", r.lastEndpoint);

        assertTrue(r.registerHooks("http://127.0.0.1:6969"));
        assertEquals("http://127.0.0.1:6969", r.lastBaseUrl);

        r.hooksOk = false;
        assertFalse(r.registerHooks("http://127.0.0.1:1"));

        r.removeMcpEndpoint();
        r.unregisterHooks();
        assertTrue(r.removeCalled);
        assertTrue(r.unregisterCalled);
    }
}
