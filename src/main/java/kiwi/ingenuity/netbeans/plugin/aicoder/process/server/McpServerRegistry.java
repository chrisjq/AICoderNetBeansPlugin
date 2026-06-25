package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;

/**
 * Manages the lifecycle of the single shared McpHookServer. The server starts
 * on the first {@link #register} call and stops after the last
 * {@link #deregister} call. AI-type-specific endpoint registration and hook
 * configuration is delegated to the {@link AiMcpRegistrar} supplied by the
 * caller, keeping this class free of any AI-implementation details.
 * <p>
 * Endpoints and hooks are tracked per AI type: the first session of a type
 * registers that type's endpoint and hooks; the last session of a type tears
 * them down. The shared server itself starts on the first session overall and
 * stops on the last session overall.
 */
public final class McpServerRegistry {

    private static final Logger LOG = Logger.getLogger(McpServerRegistry.class.getName());
    private static final Object LOCK = new Object();
    private static final Object HOOKS_LOCK = new Object();
    private static McpHookServer sharedServer = null;
    private static int sessionCount = 0;
    private static volatile int hookGeneration = 0;
    private static final Set<String> registeredIds = new HashSet<>();
    private static final Map<AiTypeEnum, Integer> typeCounts = new HashMap<>();

    /**
     * Test seam: when non-null, overrides the configured hook-server port.
     * Tests set this to 0 to bind an ephemeral port and avoid collisions with a
     * real running server. Never set in production.
     */
    static volatile Integer portOverride = null;

    /**
     * Register a new AI session with the shared MCP server. Starts the server
     * on the first call. Registers the /mcp/{aiType} endpoint and this type's
     * hooks via the registrar the first time a session of that AI type
     * registers.
     *
     * @return true on success; false if the server could not be started or
     * hooks could not be registered
     */
    public static boolean register(AiMcpRegistrar registrar) {
        String endpointUrl;
        String baseUrl;
        boolean shouldAddEndpoint;
        synchronized (LOCK) {
            if (sharedServer == null || sharedServer.isStopped()) {
                // Recreate when there is no server, or when a previous server was
                // left stopped-but-referenced (e.g. teardown threw before nulling).
                // Reusing a stopped server would keep MCP down until a restart.
                int port = portOverride != null ? portOverride : PluginSettings.getHookServerPort();
                McpHookServer srv;
                try {
                    srv = createServerWithRetry(port);
                }
                catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not start MCP hook server on port " + port, e);
                    return false;
                }
                sharedServer = srv;
            }
            baseUrl = sharedServer.getBaseUrl();
            endpointUrl = baseUrl + "/mcp/" + registrar.getAiType().key();
            if (registeredIds.add(registrar.getSessionId())) {
                sessionCount++;
                int typeCount = typeCounts.merge(registrar.getAiType(), 1, Integer::sum);
                shouldAddEndpoint = (typeCount == 1);
            }
            else {
                LOG.log(Level.WARNING, "Session {0} already registered; skipping count increment", registrar.getSessionId());
                shouldAddEndpoint = false;
            }
        }
        // First session of this AI type: register its hooks and shared endpoint.
        // Hooks are per-type, not per-server: each AI type writes its own hook
        // config (Claude writes a PreToolUse diff-intercept hook; Copilot is a
        // no-op). Gating on the first overall session would skip Claude's hook
        // whenever a Copilot session happened to start first. registerHooks is
        // idempotent, so re-running it per type is safe.
        // MCP subprocess calls (up to 5 s each) are outside the lock so that
        // concurrent session registrations don't serialize on the lock.
        if (shouldAddEndpoint) {
            boolean hooksOk;
            synchronized (HOOKS_LOCK) {
                hooksOk = registrar.registerHooks(baseUrl);
                if (hooksOk) {
                    hookGeneration++;
                }
            }
            if (!hooksOk) {
                LOG.log(Level.WARNING, "Failed to register hooks for AI type {0}", registrar.getAiType());
                rollbackFailedRegistration(registrar);
                return false;
            }
            registrar.removeMcpEndpoint();
            try {
                registrar.addMcpEndpoint(endpointUrl);
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to add MCP endpoint " + endpointUrl, e);
                rollbackFailedRegistration(registrar);
                return false;
            }
        }
        return true;
    }

    /**
     * Roll back the in-lock bookkeeping for a session whose out-of-lock setup
     * (hook or endpoint registration) failed. Stops and clears the shared
     * server if this was the only session.
     */
    private static void rollbackFailedRegistration(AiMcpRegistrar registrar) {
        synchronized (LOCK) {
            if (registeredIds.remove(registrar.getSessionId())) {
                sessionCount--;
                decrementTypeAndGet(registrar.getAiType());
            }
            if (sessionCount <= 0 && sharedServer != null) {
                sharedServer.stop();
                sharedServer = null;
                sessionCount = 0;
                registeredIds.clear();
                typeCounts.clear();
            }
        }
    }

    /**
     * Create the hook server, retrying briefly on bind failure. After a
     * previous server is stopped (e.g. when a session ends on an auth error),
     * the OS may not release the listening socket instantly; a concurrent
     * restart can then hit "address already in use". A short bounded retry
     * rides out that window so a legitimate restart on the same port succeeds
     * instead of failing the session.
     */
    private static McpHookServer createServerWithRetry(int port) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return new McpHookServer(port);
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

    /**
     * Deregister a session. When the last session of an AI type deregisters,
     * removes that type's shared MCP endpoint and unregisters its hooks via the
     * registrar — independent of whether other AI types are still running. When
     * the last session overall deregisters, stops the shared server.
     */
    public static void deregister(AiMcpRegistrar registrar) {
        AiTypeEnum type = registrar.getAiType();
        boolean wasLastOfType = false;
        int capturedGeneration = -1;
        synchronized (LOCK) {
            boolean known = registeredIds.remove(registrar.getSessionId());
            if (sharedServer != null) {
                sharedServer.unregisterSession(registrar.getSessionId());
            }
            if (known) {
                sessionCount--;
                wasLastOfType = (decrementTypeAndGet(type) == 0);
            }
            if (sessionCount <= 0) {
                // No sessions remain: stop the shared server and reset all state.
                // Stop inside the lock so the port is released before the lock
                // exits; any concurrent register() then finds sharedServer==null
                // and a free port.
                if (sharedServer != null) {
                    sharedServer.stop();
                    sharedServer = null;
                }
                sessionCount = 0;
                registeredIds.clear();
                typeCounts.clear();
            }
            if (wasLastOfType) {
                capturedGeneration = hookGeneration;
            }
        }
        // Per-type teardown outside the LOCK (MCP subprocess calls can take
        // seconds). When the last session of this AI type goes away, remove that
        // type's endpoint and hooks via this type's registrar. This keeps
        // teardown symmetric with the per-type registration in register(): a
        // Copilot session closing last no longer leaves Claude's endpoint/hook
        // config lingering.
        if (wasLastOfType) {
            // Serialise teardown against concurrent registerHooks()/endpoint
            // adds. Skip if a session has re-registered hooks since we decided to
            // tear down (hookGeneration bumped by a concurrent register()), so we
            // never remove an endpoint or hook that a freshly-started session
            // just installed.
            synchronized (HOOKS_LOCK) {
                if (capturedGeneration == hookGeneration) {
                    registrar.removeMcpEndpoint();
                    registrar.unregisterHooks();
                }
            }
        }
    }

    /**
     * Decrement the session count for an AI type, removing the entry when it
     * reaches zero. Returns the remaining count for that type (0 if none).
     */
    private static int decrementTypeAndGet(AiTypeEnum type) {
        Integer c = typeCounts.get(type);
        if (c == null) {
            return 0;
        }
        int n = c - 1;
        if (n <= 0) {
            typeCounts.remove(type);
            return 0;
        }
        typeCounts.put(type, n);
        return n;
    }

    /**
     * Force-stop the shared server immediately, regardless of session count.
     * Call from Installer.uninstalled() as a safety net to ensure the port is
     * released on plugin unload even if session cleanup was incomplete.
     */
    public static void stopAll() {
        synchronized (LOCK) {
            if (sharedServer != null) {
                sharedServer.stop();
                sharedServer = null;
            }
            sessionCount = 0;
            registeredIds.clear();
            typeCounts.clear();
        }
    }

    /**
     * Returns the shared server, or null if no sessions are currently
     * registered.
     */
    public static McpHookServer getServer() {
        return sharedServer;
    }

    private McpServerRegistry() {
    }
}
