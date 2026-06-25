package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle tests for {@link McpServerRegistry}. Uses an ephemeral port
 * (portOverride = 0) and a fake registrar so no real AI CLI is invoked and no
 * fixed port is bound.
 */
class McpServerRegistryTest {

    /**
     * Records which lifecycle callbacks the registry invoked.
     */
    private static final class FakeRegistrar extends AiMcpRegistrar {

        final List<String> events = new ArrayList<>();
        boolean hooksResult = true;

        FakeRegistrar(String sessionId, AiTypeEnum type) {
            super(sessionId, type);
        }

        @Override
        public void addMcpEndpoint(String endpointUrl) {
            events.add("add");
        }

        @Override
        public void removeMcpEndpoint() {
            events.add("remove");
        }

        @Override
        public boolean registerHooks(String serverBaseUrl) {
            events.add("registerHooks");
            return hooksResult;
        }

        @Override
        public void unregisterHooks() {
            events.add("unregisterHooks");
        }

        long count(String event) {
            return events.stream().filter(event::equals).count();
        }
    }

    @BeforeEach
    void setUp() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    void firstSessionStartsServerAndRegistersTypeOnce() {
        FakeRegistrar claude = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(claude));
        assertNotNull(McpServerRegistry.getServer());
        assertEquals(1, claude.count("registerHooks"));
        assertEquals(1, claude.count("add"));
    }

    @Test
    void secondSessionOfSameTypeDoesNotReRegister() {
        FakeRegistrar c1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        FakeRegistrar c2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(c1));
        assertTrue(McpServerRegistry.register(c2));
        assertEquals(0, c2.count("registerHooks"));
        assertEquals(0, c2.count("add"));
    }

    @Test
    void hooksRegisteredPerTypeRegardlessOfStartOrder() {
        // Regression: a Copilot session starting first must not stop a later
        // Claude session from registering Claude's diff-intercept hook.
        FakeRegistrar copilot = new FakeRegistrar("g1", AiTypeEnum.GitHubCoPilot);
        FakeRegistrar claude = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(copilot));
        assertTrue(McpServerRegistry.register(claude));
        assertEquals(1, copilot.count("registerHooks"));
        assertEquals(1, claude.count("registerHooks"));
    }

    @Test
    void lastSessionOfTypeTearsDownThatTypeOnly() {
        FakeRegistrar claude = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        FakeRegistrar copilot = new FakeRegistrar("g1", AiTypeEnum.GitHubCoPilot);
        assertTrue(McpServerRegistry.register(claude));
        assertTrue(McpServerRegistry.register(copilot));
        // Measure only teardown-phase callbacks. register() also calls
        // removeMcpEndpoint() once as idempotent stale-cleanup before adding,
        // so clear the recorded events after the registration phase.
        claude.events.clear();
        copilot.events.clear();

        McpServerRegistry.deregister(claude);
        // Claude torn down; server still running for Copilot.
        assertEquals(1, claude.count("remove"));
        assertEquals(1, claude.count("unregisterHooks"));
        assertNotNull(McpServerRegistry.getServer());
        assertEquals(0, copilot.count("unregisterHooks"));

        McpServerRegistry.deregister(copilot);
        assertEquals(1, copilot.count("remove"));
        assertEquals(1, copilot.count("unregisterHooks"));
        assertNull(McpServerRegistry.getServer());
    }

    @Test
    void typeTornDownOnlyAfterAllItsSessionsClose() {
        FakeRegistrar c1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        FakeRegistrar c2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(c1));
        assertTrue(McpServerRegistry.register(c2));
        // Measure only teardown-phase callbacks (register() also calls
        // removeMcpEndpoint() once as idempotent stale-cleanup).
        c1.events.clear();
        c2.events.clear();

        McpServerRegistry.deregister(c1);
        // One Claude session remains: no teardown for c1, server stays up.
        assertEquals(0, c1.count("remove"));
        assertEquals(0, c1.count("unregisterHooks"));
        assertNotNull(McpServerRegistry.getServer());

        McpServerRegistry.deregister(c2);
        assertEquals(1, c2.count("remove"));
        assertEquals(1, c2.count("unregisterHooks"));
        assertNull(McpServerRegistry.getServer());
    }

    @Test
    void failedHookRegistrationRollsBackAndStopsServer() {
        FakeRegistrar bad = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        bad.hooksResult = false;
        assertFalse(McpServerRegistry.register(bad));
        assertEquals(0, bad.count("add"));
        assertNull(McpServerRegistry.getServer());
    }

    @Test
    void serverServesAgainAfterFullTeardownAndRestart() throws Exception {
        // Reproduces "MCP server doesn't start again after all AIs close":
        // verify the server actually SERVES HTTP both before and after a full
        // teardown/restart cycle (the listener starts lazily in registerSession).
        FakeRegistrar r1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(r1));
        McpHookServer s1 = McpServerRegistry.getServer();
        assertNotNull(s1);
        s1.registerSession("c1", "claude", List.of(), false);
        assertEquals(200, probeInitialize(s1), "server should serve before teardown");

        McpServerRegistry.deregister(r1);
        assertNull(McpServerRegistry.getServer());

        FakeRegistrar r2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(r2));
        McpHookServer s2 = McpServerRegistry.getServer();
        assertNotNull(s2);
        s2.registerSession("c2", "claude", List.of(), false);
        assertEquals(200, probeInitialize(s2), "server should serve again after restart");
    }

    @Test
    void serverRebindsSameFixedPortAfterTeardown() throws Exception {
        // Production uses a FIXED port and must rebind the SAME port after all
        // AIs close. The ephemeral-port test can't catch a same-port rebind
        // failure, so pin a known-free port and cycle it.
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        McpServerRegistry.portOverride = port;

        FakeRegistrar r1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(r1));
        McpServerRegistry.getServer().registerSession("c1", "claude", List.of(), false);
        McpServerRegistry.deregister(r1);

        FakeRegistrar r2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(r2), "must rebind the same fixed port after teardown");
        McpHookServer s2 = McpServerRegistry.getServer();
        assertNotNull(s2);
        s2.registerSession("c2", "claude", List.of(), false);
        assertEquals(200, probeInitialize(s2), "server should serve again on the same port");
    }

    @Test
    void registerReplacesAStoppedServerInstance() throws Exception {
        // If the shared server ever ends up stopped-but-not-nulled (e.g. an
        // exception during teardown skipped the null assignment), register()
        // must replace it rather than hand back a dead server — otherwise the
        // MCP server stays down until NetBeans restarts.
        FakeRegistrar r1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(r1));
        McpHookServer s1 = McpServerRegistry.getServer();
        s1.registerSession("c1", "claude", List.of(), false);
        s1.stop(); // leaked: stopped but registry still references it
        assertTrue(s1.isStopped());

        FakeRegistrar r2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(r2));
        McpHookServer s2 = McpServerRegistry.getServer();
        assertNotNull(s2);
        assertFalse(s2.isStopped(), "register() should replace a stopped server");
        s2.registerSession("c2", "claude", List.of(), false);
        assertEquals(200, probeInitialize(s2), "replacement server should serve");
    }

    /**
     * POSTs a minimal MCP initialize and returns the HTTP status code.
     */
    private static int probeInitialize(McpHookServer server) throws Exception {
        java.net.URL url = java.net.URI.create(server.getBaseUrl() + "/mcp/claude").toURL();
        java.net.HttpURLConnection con = (java.net.HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(2000);
        con.setReadTimeout(2000);
        con.setDoOutput(true);
        con.getOutputStream().write(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int code = con.getResponseCode();
        con.disconnect();
        return code;
    }

    @Test
    void reRegisteringTypeAfterTeardownReinstallsEndpoint() {
        FakeRegistrar c1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(c1));
        McpServerRegistry.deregister(c1);
        assertNull(McpServerRegistry.getServer());

        FakeRegistrar c2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(McpServerRegistry.register(c2));
        assertEquals(1, c2.count("registerHooks"));
        assertEquals(1, c2.count("add"));
        assertNotNull(McpServerRegistry.getServer());
    }
}
