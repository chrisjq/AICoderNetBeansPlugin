package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * Lifecycle tests for the rev-2 supervisor {@link McpServerRegistry}, where the
 * supervisor owns ALL registration logic. {@code register()} returns a future
 * (waited on via {@link #register}); {@code deregister()} and its per-type
 * teardown are fire-and-forget on the supervisor thread, so teardown/stop
 * assertions poll ({@link #awaitCount}/{@link #awaitServerStopped}). Liveness
 * is a plain TCP connect to avoid the surefire/NbPreferences MCP-HTTP path.
 */
class McpServerRegistryTest {

    // ---- helpers ----
    private static boolean register(FakeRegistrar r) {
        try {
            return McpServerRegistry.register(r).get(5, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            return false;
        }
    }

    private static void awaitCount(FakeRegistrar r, String event, long expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && r.count(event) != expected) {
            Thread.sleep(20);
        }
        assertEquals(expected, r.count(event), "count of '" + event + "'");
    }

    private static boolean awaitServerRunning(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            McpHookServer s = McpServerRegistry.getServer();
            if (s != null && !s.isStopped()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static boolean awaitServerStopped(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (McpServerRegistry.getServer() == null) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static boolean isServing(McpHookServer server) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), 1000);
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        McpServerRegistry.pollIntervalMillis = 100; // snappy health ticks for tests
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
        McpServerRegistry.pollIntervalMillis = 60000;
    }

    @Test
    void firstSessionStartsServerAndRegistersTypeOnce() {
        FakeRegistrar claude = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(register(claude));
        assertNotNull(McpServerRegistry.getServer());
        assertEquals(1, claude.count("registerHooks"));
        assertEquals(1, claude.count("add"));
    }

    @Test
    void secondSessionOfSameTypeDoesNotReRegister() {
        FakeRegistrar c1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        FakeRegistrar c2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(register(c1));
        assertTrue(register(c2));
        assertEquals(0, c2.count("registerHooks"));
        assertEquals(0, c2.count("add"));
    }

    @Test
    void duplicateSessionRegisterDedupes() {
        FakeRegistrar c1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(register(c1));
        // Same sessionId again: dedupe — completes true, no extra hooks/endpoint.
        FakeRegistrar dup = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(register(dup));
        assertEquals(0, dup.count("registerHooks"));
        assertEquals(0, dup.count("add"));
        assertNotNull(McpServerRegistry.getServer());
    }

    @Test
    void hooksRegisteredPerTypeRegardlessOfStartOrder() {
        FakeRegistrar copilot = new FakeRegistrar("g1", AiTypeEnum.GitHubCoPilot);
        FakeRegistrar claude = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(register(copilot));
        assertTrue(register(claude));
        assertEquals(1, copilot.count("registerHooks"));
        assertEquals(1, claude.count("registerHooks"));
    }

    @Test
    void lastSessionOfTypeTearsDownThatTypeOnly() throws Exception {
        FakeRegistrar claude = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        FakeRegistrar copilot = new FakeRegistrar("g1", AiTypeEnum.GitHubCoPilot);
        assertTrue(register(claude));
        assertTrue(register(copilot));
        claude.events.clear();
        copilot.events.clear();

        McpServerRegistry.deregister(claude); // fire-and-forget
        awaitCount(claude, "unregisterHooks", 1, 3000);
        assertEquals(1, claude.count("remove"));
        assertEquals(0, copilot.count("unregisterHooks"));
        assertNotNull(McpServerRegistry.getServer()); // still up for Copilot

        McpServerRegistry.deregister(copilot);
        awaitCount(copilot, "unregisterHooks", 1, 3000);
        assertEquals(1, copilot.count("remove"));
        assertTrue(awaitServerStopped(3000));
    }

    @Test
    void typeTornDownOnlyAfterAllItsSessionsClose() throws Exception {
        FakeRegistrar c1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        FakeRegistrar c2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(register(c1));
        assertTrue(register(c2));
        c1.events.clear();
        c2.events.clear();

        McpServerRegistry.deregister(c1);
        McpServerRegistry.deregister(c2);
        // Both processed in order: c1 is not last-of-type, c2 is.
        awaitCount(c2, "unregisterHooks", 1, 3000);
        assertEquals(0, c1.count("unregisterHooks"), "c1 was not the last of its type");
        assertEquals(1, c2.count("remove"));
        assertTrue(awaitServerStopped(3000));
    }

    @Test
    void failedHookRegistrationRollsBackAndStopsServer() throws Exception {
        FakeRegistrar bad = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        bad.hooksResult = false;
        assertFalse(register(bad));
        assertEquals(0, bad.count("add"));
        assertTrue(awaitServerStopped(3000), "failed registration must leave no running server");
    }

    @Test
    void poisonedEventDoesNotKillSupervisor() throws Exception {
        // A registrar that throws must not take the supervisor thread down.
        FakeRegistrar poison = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        poison.throwInRegisterHooks = true;
        assertFalse(register(poison), "poisoned register completes false");
        assertTrue(awaitServerStopped(3000), "its bookkeeping is undone, server stops");

        // Supervisor survived: a later good registration still works.
        FakeRegistrar good = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(register(good));
        assertNotNull(McpServerRegistry.getServer());
    }

    @Test
    void stopAllCompletesPendingRegisterFutures() throws Exception {
        // Block the supervisor inside registerHooks of the first registration so a
        // second REGISTER stays pending in the queue; stopAll must complete that
        // pending future (false) rather than leave the caller hanging.
        FakeRegistrar blocker = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        blocker.hookEntered = new CountDownLatch(1);
        blocker.hookGate = new CountDownLatch(1);
        CompletableFuture<Boolean> f1 = McpServerRegistry.register(blocker);
        assertTrue(blocker.hookEntered.await(3, TimeUnit.SECONDS), "supervisor should reach registerHooks");

        FakeRegistrar pending = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        CompletableFuture<Boolean> f2 = McpServerRegistry.register(pending); // stuck behind blocker

        McpServerRegistry.stopAll(); // interrupts blocker, drains queue completing f2

        assertFalse(f2.get(3, TimeUnit.SECONDS), "pending REGISTER future must be completed, not hang");
        blocker.hookGate.countDown(); // release in case the interrupt was missed
        f1.getNow(false); // must not hang; value irrelevant
    }

    @Test
    void serverServesAgainAfterFullTeardownAndRestart() throws Exception {
        FakeRegistrar r1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(register(r1));
        McpHookServer s1 = McpServerRegistry.getServer();
        assertNotNull(s1);
        assertTrue(isServing(s1), "server should serve before teardown");

        McpServerRegistry.deregister(r1);
        assertTrue(awaitServerStopped(3000));

        FakeRegistrar r2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(register(r2));
        McpHookServer s2 = McpServerRegistry.getServer();
        assertNotNull(s2);
        assertTrue(isServing(s2), "server should serve again after restart");
    }

    @Test
    void serverRebindsSameFixedPortAfterTeardown() throws Exception {
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            port = probe.getLocalPort();
        }
        McpServerRegistry.portOverride = port;

        FakeRegistrar r1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(register(r1));
        McpServerRegistry.deregister(r1);
        assertTrue(awaitServerStopped(3000));

        FakeRegistrar r2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(register(r2), "must rebind the same fixed port after teardown");
        McpHookServer s2 = McpServerRegistry.getServer();
        assertNotNull(s2);
        assertEquals(port, s2.getPort());
        assertTrue(isServing(s2), "server should serve again on the same port");
    }

    @Test
    void healthTickResurrectsForceStoppedServer() throws Exception {
        FakeRegistrar r1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(register(r1));
        McpHookServer s1 = McpServerRegistry.getServer();
        assertNotNull(s1);
        s1.stop(); // kill the listener behind the registry's back
        assertTrue(s1.isStopped());

        assertTrue(awaitServerRunning(3000), "health tick should resurrect the server");
        McpHookServer s2 = McpServerRegistry.getServer();
        assertNotNull(s2);
        assertFalse(s2.isStopped());
        assertTrue(isServing(s2), "resurrected server should accept connections");
    }

    @Test
    void stopAllStopsServerAndSupervisorThenRecovers() {
        FakeRegistrar r1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(register(r1));
        assertNotNull(McpServerRegistry.getServer());

        McpServerRegistry.stopAll();
        assertNull(McpServerRegistry.getServer(), "stopAll joins the supervisor and stops the server");

        FakeRegistrar r2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(register(r2));
        McpHookServer s2 = McpServerRegistry.getServer();
        assertNotNull(s2);
        assertFalse(s2.isStopped());
    }

    @Test
    void reRegisteringTypeAfterTeardownReinstallsEndpoint() throws Exception {
        FakeRegistrar c1 = new FakeRegistrar("c1", AiTypeEnum.CLAUDE);
        assertTrue(register(c1));
        McpServerRegistry.deregister(c1);
        assertTrue(awaitServerStopped(3000));

        FakeRegistrar c2 = new FakeRegistrar("c2", AiTypeEnum.CLAUDE);
        assertTrue(register(c2));
        assertEquals(1, c2.count("registerHooks"));
        assertEquals(1, c2.count("add"));
        assertNotNull(McpServerRegistry.getServer());
    }

    // ---- endpointUrlFor tests ----
    @Test
    void endpointUrlForReturnsNullWhenServerNotRunning() {
        // No sessions registered — sharedServer is null.
        assertNull(McpServerRegistry.endpointUrlFor(AiTypeEnum.OPENCODE));
        assertNull(McpServerRegistry.endpointUrlFor(null));
    }

    @Test
    void endpointUrlForReturnsMcpUrlWhenServerIsUp() {
        FakeRegistrar r = new FakeRegistrar("oc1", AiTypeEnum.OPENCODE);
        assertTrue(register(r));
        String url = McpServerRegistry.endpointUrlFor(AiTypeEnum.OPENCODE);
        assertNotNull(url);
        assertEquals(McpServerRegistry.getServer().getBaseUrl() + "/mcp/opencode", url);
    }

    @Test
    void endpointUrlForReturnsNonNullForSecondSessionOfType() {
        // First session gets addMcpEndpoint called; second does NOT (typeCount guard).
        // endpointUrlFor must still return the correct URL for both sessions.
        FakeRegistrar oc1 = new FakeRegistrar("oc1", AiTypeEnum.OPENCODE);
        assertTrue(register(oc1));
        assertEquals(1, oc1.count("add"), "first session must have addMcpEndpoint called");

        FakeRegistrar oc2 = new FakeRegistrar("oc2", AiTypeEnum.OPENCODE);
        assertTrue(register(oc2));
        assertEquals(0, oc2.count("add"), "second session must NOT have addMcpEndpoint called");

        String url = McpServerRegistry.endpointUrlFor(AiTypeEnum.OPENCODE);
        assertNotNull(url, "endpointUrlFor must return non-null even when the registrar's addMcpEndpoint was never called");
        assertEquals(McpServerRegistry.getServer().getBaseUrl() + "/mcp/opencode", url);
    }

    /**
     * Records which lifecycle callbacks the registry invoked. {@code events} is
     * written by the supervisor thread and read by the test thread, so it is
     * thread-safe.
     */
    private static final class FakeRegistrar extends AiMcpRegistrar {

        final List<String> events = new CopyOnWriteArrayList<>();
        volatile boolean hooksResult = true;
        volatile boolean throwInRegisterHooks = false;
        volatile CountDownLatch hookEntered = null;
        volatile CountDownLatch hookGate = null;

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
            if (throwInRegisterHooks) {
                throw new RuntimeException("boom");
            }
            if (hookGate != null) {
                if (hookEntered != null) {
                    hookEntered.countDown();
                }
                try {
                    hookGate.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
}
