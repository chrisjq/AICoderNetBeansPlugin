package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;

/**
 * Manages the lifecycle of the single shared {@link McpHookServer}. A dedicated
 * daemon supervisor thread owns ALL registration logic; callers only fire
 * events. The single thread gives a total order over every mutation, so there
 * are no locks in the registry beyond the event queue and one lazy-spawn guard.
 * <p>
 * {@link #register} enqueues a REGISTER and returns a future the caller waits on;
 * {@link #deregister} is fully fire-and-forget. The supervisor dedupes, tracks
 * per-type first/last-of-type (derived from its own registration map), installs
 * and tears down hooks/endpoints, and reconciles the server against desired
 * state: registrations non-empty → server up, empty → server down. Its queue
 * poll timeout doubles as a health tick that resurrects a dead/unresponsive
 * server, so MCP no longer stays down until an IDE restart.
 */
public final class McpServerRegistry {

    private static final Logger LOG = Logger.getLogger(McpServerRegistry.class.getName());

    /** Event queue drained by the supervisor. Tiny, rare events — 128 is ample. */
    private static final BlockingQueue<McpRegistryEvent> QUEUE = new ArrayBlockingQueue<>(128);

    /**
     * Desired state, touched ONLY by the supervisor thread (single-writer, so no
     * concurrency needed): sessionId → its registrar. Non-empty → server up.
     * Per-type counts are derived from this map in event order.
     */
    private static final Map<String, AiMcpRegistrar> registrations = new HashMap<>();

    /** The running server, published by the supervisor; null when down. */
    private static volatile McpHookServer sharedServer = null;

    private static final Object SUPERVISOR_LOCK = new Object();
    private static Thread supervisor = null;          // guarded by SUPERVISOR_LOCK
    private static volatile boolean shutdown = false; // supervisor stop signal

    /**
     * Test seam: when non-null, overrides the configured hook-server port. Tests
     * set this to 0 to bind an ephemeral port. Never set in production.
     */
    static volatile Integer portOverride = null;

    /**
     * Test seam: supervisor queue-poll / health-tick interval, in milliseconds.
     * Shorten in tests to assert health-tick behaviour quickly. Production keeps
     * the 5-second default.
     */
    static volatile long pollIntervalMillis = 5000;

    private McpServerRegistry() {
    }

    // ---- Public API (thin — callers only fire events) ----

    /**
     * Enqueue a REGISTER and return immediately. The returned future completes
     * with {@code true} once the supervisor has the server running and this
     * type's hooks/endpoint installed, or {@code false} on any failure. Callers
     * (the process managers) wait on it with their own timeout and keep their
     * existing FAILED-StatusEvent + abort behaviour on false/timeout.
     */
    public static CompletableFuture<Boolean> register(AiMcpRegistrar registrar) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        synchronized (SUPERVISOR_LOCK) {
            if (supervisor != null && supervisor.isAlive()) {
                if (shutdown) {
                    // A stopAll is retiring the current supervisor; do not hand it
                    // new work. The caller aborts; a later register (after the old
                    // thread dies) spawns a fresh supervisor.
                    future.complete(false);
                    return future;
                }
            }
            else {
                shutdown = false;
                spawnSupervisor();
            }
            // Spawn + enqueue are atomic under the lock, so a REGISTER can never
            // land in a queue whose supervisor has already exited (finding 1).
            if (!enqueue(McpRegistryEvent.register(registrar, future))) {
                future.complete(false);
            }
        }
        return future;
    }

    /**
     * Enqueue a DEREGISTER and return immediately (fire-and-forget). No-op if no
     * supervisor is running (nothing is registered).
     */
    public static void deregister(AiMcpRegistrar registrar) {
        synchronized (SUPERVISOR_LOCK) {
            if (supervisor != null && supervisor.isAlive() && !shutdown) {
                enqueue(McpRegistryEvent.deregister(registrar.getSessionId(), registrar.getAiType()));
            }
        }
    }

    /**
     * Force everything down (Installer.uninstalled). Signals shutdown, enqueues
     * STOP_ALL, interrupts and briefly joins the supervisor, then drains the
     * queue completing any pending REGISTER futures with false so no caller ever
     * hangs. Never nulls the supervisor reference while its thread is still
     * alive; a later {@link #register} lazily spawns a fresh one with clean
     * state.
     */
    public static void stopAll() {
        synchronized (SUPERVISOR_LOCK) {
            Thread t = supervisor;
            if (t != null && t.isAlive()) {
                shutdown = true;
                enqueue(McpRegistryEvent.stopAll());
                t.interrupt();
                try {
                    t.join(2000);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Complete any events the supervisor did not drain, so callers waiting
            // on a REGISTER future never hang (finding 1).
            drainQueueCompletingFutures();
            if (t == null || !t.isAlive()) {
                supervisor = null;
                // Defensive teardown for the no-supervisor / already-exited case.
                // Safe only when no supervisor thread is touching this state.
                stopServerQuietly();
                registrations.clear();
            }
            // else: a zombie is still alive after the join timeout — leave the
            // reference and shared state to it; it will exit on the shutdown flag
            // and clean up in its finally block. ensureSupervisor won't spawn a
            // second thread while this one is alive (finding 2).
        }
    }

    /**
     * Returns the shared server, or null if no sessions are currently registered
     * (or the supervisor has not yet brought it up).
     */
    public static McpHookServer getServer() {
        return sharedServer;
    }

    // ---- Enqueue helper (finding 4: never a silent drop) ----

    private static boolean enqueue(McpRegistryEvent event) {
        if (!QUEUE.offer(event)) {
            LOG.log(Level.WARNING, "MCP registry queue full; dropping {0} event", event.type());
            return false;
        }
        return true;
    }

    private static void drainQueueCompletingFutures() {
        McpRegistryEvent e;
        while ((e = QUEUE.poll()) != null) {
            if (e.type() == McpRegistryEventTypeEnum.REGISTER && e.result() != null) {
                e.result().complete(false);
            }
        }
    }

    // ---- Supervisor thread ----

    /** Must be called under SUPERVISOR_LOCK, only when no supervisor is alive. */
    private static void spawnSupervisor() {
        Thread t = new Thread(McpServerRegistry::supervisorLoop, "mcp-registry-supervisor");
        t.setDaemon(true);
        supervisor = t;
        t.start();
    }

    private static void supervisorLoop() {
        try {
            while (!shutdown) {
                McpRegistryEvent event;
                try {
                    event = QUEUE.poll(pollIntervalMillis, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException e) {
                    // interrupt() is a shutdown signal; the loop condition handles it.
                    continue;
                }
                if (shutdown) {
                    break;
                }
                if (event == null) {
                    reconcile(true); // health tick
                    continue;
                }
                handleEvent(event);
            }
        }
        finally {
            // Shutdown cleanup so a freshly spawned supervisor starts clean.
            stopServerQuietly();
            registrations.clear();
        }
    }

    /**
     * Handle one event. Wrapped in catch(Throwable) so a poisoned event can never
     * kill the supervisor (finding 3): the in-flight REGISTER future is completed
     * false, the error logged SEVERE, and the loop continues.
     */
    private static void handleEvent(McpRegistryEvent event) {
        try {
            switch (event.type()) {
                case REGISTER ->
                    handleRegister(event);
                case DEREGISTER ->
                    handleDeregister(event);
                case STOP_ALL ->
                    shutdown = true; // loop exits, finally cleans up
            }
        }
        catch (Throwable t) {
            LOG.log(Level.SEVERE, "MCP supervisor failed handling " + event.type() + " event", t);
            if (event.type() == McpRegistryEventTypeEnum.REGISTER && event.result() != null) {
                event.result().complete(false);
            }
        }
    }

    private static void handleRegister(McpRegistryEvent event) {
        String key = event.key();
        AiTypeEnum type = event.aiType();
        CompletableFuture<Boolean> future = event.result();

        if (registrations.containsKey(key)) {
            LOG.log(Level.WARNING, "Session {0} already registered; ignoring duplicate", key);
            future.complete(true);
            return;
        }

        registrations.put(key, event.registrar());
        reconcile(false); // bring the server up
        McpHookServer server = sharedServer;
        if (server == null || server.isStopped()) {
            registrations.remove(key);
            reconcile(false); // stop if now unused
            future.complete(false);
            return;
        }

        // First session of this AI type installs its hooks + shared endpoint. Hooks
        // are per-type (Claude writes a PreToolUse diff hook, Copilot is a no-op).
        // These are ~5 s subprocess calls, now on the supervisor thread — accepted.
        if (typeCount(type) == 1) {
            boolean ok;
            try {
                ok = event.registrar().registerHooks(server.getBaseUrl());
                if (ok) {
                    event.registrar().removeMcpEndpoint();
                    event.registrar().addMcpEndpoint(server.getBaseUrl() + "/mcp/" + type.key());
                }
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Hook/endpoint registration failed for AI type " + type, e);
                ok = false;
            }
            if (!ok) {
                registrations.remove(key); // undo this event's bookkeeping
                reconcile(false);          // stop if now unused
                future.complete(false);
                return;
            }
        }
        future.complete(true);
    }

    private static void handleDeregister(McpRegistryEvent event) {
        String key = event.key();
        AiTypeEnum type = event.aiType();
        AiMcpRegistrar registrar = registrations.remove(key);

        McpHookServer server = sharedServer;
        if (server != null) {
            server.unregisterSession(key);
        }
        // Last session of this AI type tears down its endpoint + hooks. Use the
        // registrar stored at REGISTER time.
        if (registrar != null && typeCount(type) == 0) {
            try {
                registrar.removeMcpEndpoint();
                registrar.unregisterHooks();
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Hook/endpoint teardown failed for AI type " + type, e);
            }
        }
        reconcile(false); // stop the server once no sessions remain
    }

    /** Count of currently-registered sessions of the given AI type (supervisor thread only). */
    private static long typeCount(AiTypeEnum type) {
        return registrations.values().stream().filter(r -> r.getAiType() == type).count();
    }

    /**
     * Enforces the one invariant: registrations non-empty → a running server;
     * empty → no server. On a health tick, also replaces a server that no longer
     * accepts connections. Any startup failure is logged and retried on the next
     * tick (self-heal). Runs only on the supervisor thread and never throws.
     */
    private static void reconcile(boolean healthTick) {
        McpHookServer s = sharedServer;
        boolean needServer = !registrations.isEmpty();
        if (needServer) {
            boolean dead = s == null || s.isStopped() || (healthTick && !isResponsive(s));
            if (dead) {
                if (s != null) {
                    stopServerQuietly();
                }
                try {
                    int port = portOverride != null ? portOverride : PluginSettings.getHookServerPort();
                    McpHookServer fresh = createServerWithRetry(port);
                    fresh.start();
                    sharedServer = fresh;
                }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "MCP supervisor could not start hook server; will retry on next tick", e);
                    sharedServer = null;
                }
            }
        }
        else if (s != null) {
            stopServerQuietly();
        }
    }

    private static void stopServerQuietly() {
        McpHookServer s = sharedServer;
        if (s != null) {
            try {
                s.stop();
            }
            catch (Exception e) {
                LOG.log(Level.FINE, "McpHookServer.stop threw", e);
            }
            sharedServer = null;
        }
    }

    /**
     * Cheap liveness probe: can we open a TCP connection to the server's port?
     * Used only on health ticks to catch a referenced-but-dead listener.
     */
    private static boolean isResponsive(McpHookServer server) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), 500);
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    /**
     * Create the hook server, retrying briefly on bind failure. After a previous
     * server is stopped the OS may not release the socket instantly; a short
     * bounded retry rides out that window so a restart on the same port succeeds.
     */
    private static McpHookServer createServerWithRetry(int port) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                McpHookServer s = new McpHookServer(port);
                s.init();
                return s;
            }
            catch (IOException e) {
                last = e;
                LOG.log(Level.FINE, "MCP server bind attempt {0} on port {1} failed: {2}",
                        new Object[]{attempt + 1, port, e.getMessage()});
                try {
                    Thread.sleep(150);
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }
}
