package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.ToolLockRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import org.openide.util.Exceptions;

public class McpHookServer {

    private static final Logger LOG = Logger.getLogger(McpHookServer.class.getName());
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    // ---- HTTP connection management (com.sun.net.httpserver) ----
    // These map to the JDK server's built-in HTTP/1.1 keep-alive and idle-
    // connection handling. They are applied as system properties before the
    // server is created (see applyConnectionSettings()). Values equal the JDK
    // defaults EXCEPT IDLE_INTERVAL_SECONDS, which is raised to 5 minutes.
    // Adjust any value here to tune connection behaviour in one place.
    // NOTE: the JDK reads these once, when com.sun.net.httpserver's ServerConfig
    // first initialises (the first HttpServer created in this JVM), so they only
    // take effect if set before any other such server has been created.
    private static final int IDLE_INTERVAL_SECONDS = 5 * 60;   // JDK default 30 — idle keep-alive window
    private static final int MAX_IDLE_CONNECTIONS = 200;       // JDK default 200 — cached idle connections
    private static final int MAX_CONNECTIONS = -1;             // JDK default -1 — total connection cap (unlimited)
    private static final int MAX_REQ_TIME_SECONDS = -1;        // JDK default -1 — max time to read a request (off)
    private static final int MAX_RSP_TIME_SECONDS = -1;        // JDK default -1 — max time to write a response (off)

    private static java.nio.file.Path resolveRealPath(java.nio.file.Path p) {
        try {
            return p.toRealPath();
        }
        catch (IOException e) {
            return p.normalize();
        }
    }

    /**
     * Gate predicate: while a conversation has not loaded the full instruction
     * guide, every tool except GetInstructions is blocked. An unknown tool
     * (null) is also blocked so the AI is steered to GetInstructions first.
     */
    static boolean isToolGated(boolean instructionsLoaded, McpToolEnum tool) {
        if (instructionsLoaded) {
            return false;
        }
        return tool != McpToolEnum.GET_INSTRUCTIONS;
    }

    private final HttpServer httpServer;
    private final int port;
    private final ExecutorService executor;
    private final Set<String> activeSessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ReentrantLock mutationLock = new ReentrantLock(true);
    private final Map<String, ReentrantLock> hookLocks = new ConcurrentHashMap<>();
    private final Map<String, List<java.io.File>> sessionProjectDirs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sessionRestrictToProject = new ConcurrentHashMap<>();
    private final Map<String, String> sessionAiTypeKey = new ConcurrentHashMap<>();
    private boolean started = false;
    private volatile boolean stopped = false;

    public McpHookServer(int port) throws IOException {
        applyConnectionSettings();
        httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.port = httpServer.getAddress().getPort();
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "hook-server-" + this.port);
            t.setDaemon(true);
            return t;
        });
        httpServer.setExecutor(executor);
        httpServer.createContext("/", this::handle);
        httpServer.createContext("/mcp", this::handleMcp);
    }

    /**
     * Applies the HTTP connection-management settings above as JDK system
     * properties. Must run before the first HttpServer is created in the JVM,
     * since com.sun.net.httpserver reads them once at ServerConfig init.
     */
    private static void applyConnectionSettings() {
        System.setProperty("sun.net.httpserver.idleInterval", Integer.toString(IDLE_INTERVAL_SECONDS));
        System.setProperty("sun.net.httpserver.maxIdleConnections", Integer.toString(MAX_IDLE_CONNECTIONS));
        System.setProperty("jdk.httpserver.maxConnections", Integer.toString(MAX_CONNECTIONS));
        System.setProperty("sun.net.httpserver.maxReqTime", Integer.toString(MAX_REQ_TIME_SECONDS));
        System.setProperty("sun.net.httpserver.maxRspTime", Integer.toString(MAX_RSP_TIME_SECONDS));
    }

    // ---- Public API ----
    /**
     * Register a session's file-scope data. The shared /mcp endpoint handles
     * all sessions by validating sessionId+secretKey from tool arguments.
     *
     * @param aiTypeKey AI type key from {@code AiTypeEnum.key()}, e.g.
     * {@code "claude"}
     */
    public synchronized void registerSession(String sessionId, String aiTypeKey,
            List<java.io.File> projectDirs, boolean restrictToProjectFiles) {
        if (sessionId == null) {
            return;
        }

        if (!started) {
            httpServer.start();
            started = true;
            LOG.log(Level.INFO, "MCP hook server listening on port {0}", this.port);
        }

        sessionAiTypeKey.put(sessionId, aiTypeKey);
        sessionProjectDirs.put(sessionId, projectDirs);
        sessionRestrictToProject.put(sessionId, restrictToProjectFiles);
        LockManager.getInstance().releaseOrphanedLocks(Set.copyOf(activeSessions));
        if (activeSessions.add(sessionId)) {
            hookLocks.put(sessionId, new ReentrantLock(true));
        }
    }

    /**
     * Refreshes a registered session's file-access scope (project dirs +
     * restrict flag) without re-registering it. Called each turn so the scope
     * tracks the currently-open projects — a project opened after the session
     * started then becomes reachable by the plugin's MCP tools (matching what
     * the CLI already sees via --add-dir). No-op for an unknown session.
     */
    public synchronized void updateSessionScope(String sessionId,
            List<java.io.File> projectDirs, boolean restrictToProjectFiles) {
        if (sessionId == null || !activeSessions.contains(sessionId)) {
            return;
        }
        sessionProjectDirs.put(sessionId, projectDirs);
        sessionRestrictToProject.put(sessionId, restrictToProjectFiles);
    }

    public synchronized void unregisterSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        LockManager.getInstance().releaseAllLocks(sessionId);
        activeSessions.remove(sessionId);
        hookLocks.remove(sessionId);
        sessionProjectDirs.remove(sessionId);
        sessionRestrictToProject.remove(sessionId);
        sessionAiTypeKey.remove(sessionId);
        // Lifecycle is owned by McpServerRegistry. Self-stopping here would create
        // a second owner and allow reuse of a dead HttpServer.
    }

    public boolean isFileAllowed(String sessionId, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        Boolean restrict = sessionRestrictToProject.get(sessionId);
        if (restrict == null || !restrict) {
            return true;
        }
        List<java.io.File> dirs = sessionProjectDirs.get(sessionId);
        if (dirs == null || dirs.isEmpty()) {
            return true;
        }
        java.nio.file.Path resolvedFile = resolveRealPath(java.nio.file.Path.of(filePath));
        return dirs.stream().anyMatch(d -> {
            java.nio.file.Path dir = resolveRealPath(d.toPath());
            return resolvedFile.equals(dir) || resolvedFile.startsWith(dir);
        });
    }

    public int getPort() {
        return port;
    }

    public String getBaseUrl() {
        InetAddress addr = httpServer.getAddress().getAddress();
        String host = addr.getHostAddress();
        if (addr instanceof Inet6Address) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + port;
    }

    /**
     * True once {@link #stop()} has been called; a stopped server cannot serve.
     */
    public boolean isStopped() {
        return stopped;
    }

    public synchronized void stop() {
        // Mark stopped first so it is always observable even if shutdown throws —
        // this lets McpServerRegistry detect and replace a dead server instead of
        // handing one back (which would leave MCP down until NetBeans restarts).
        stopped = true;
        started = false;
        try {
            httpServer.stop(0);
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "httpServer.stop threw", e);
        }
        try {
            executor.shutdownNow();
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "executor.shutdownNow threw", e);
        }
    }

    // ---- Hook request dispatch (/) ----
    private void handle(HttpExchange ex) throws IOException {
        McpHookServerUtil.addCors(ex);
        String method = ex.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String body;
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                McpHookServerUtil.sendJson(ex, 413, "{\"error\":\"request too large\"}");
                return;
            }
            body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Hook POST body: {0}", body);
        }
        JsonObject req;
        try {
            req = McpHookServerUtil.GSON.fromJson(body, JsonObject.class);
        }
        catch (JsonSyntaxException e) {
            LOG.log(Level.WARNING, "Hook: bad JSON: {0}", body);
            McpHookServerUtil.sendJson(ex, 400, "{\"error\":\"bad json\"}");
            return;
        }
        handleRequest(ex, req);
    }

    private void handleRequest(HttpExchange ex, JsonObject req) throws IOException {
        String hookEventName = McpHookServerUtil.str(req, "hook_event_name");
        if (hookEventName != null && !"PreToolUse".equals(hookEventName)) {
            // PostToolUse, Notification, Stop, etc. — not yet implemented, no-op
            LOG.log(Level.WARNING, "Hook Event Not Implementented: {0}", hookEventName);
            McpHookServerUtil.sendJson(ex, 200, "{}");
            return;
        }

        String sessionId = McpHookServerUtil.str(req, "session_id");
        String toolName = McpHookServerUtil.str(req, "tool_name");
        JsonObject input = McpHookServerUtil.obj(req, "tool_input");

        // The hook body session_id is the AI Code session UUID. AiSession
        // registers it as an alias in SessionRegistry on the first stream event, so
        // the direct lookup below succeeds in the normal case.
        AbstractAiSession session = SessionRegistry.get(sessionId);
        if (session == null) {
            // Do not fall back to another active session — that would route edits to
            // the wrong session context in a multi-session environment.  Defer and let
            // Retry once the session alias has been registered.
            LOG.log(Level.WARNING, "Hook: session_id {0} not in registry, deferring", sessionId);
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookDefer());
            return;
        }

        if (McpToolEnum.of(toolName) == null) {
            McpHookServerUtil.logToolUse(session.getSessionName(), toolName, input);
        }

        if (!"Edit".equals(toolName) && !"Write".equals(toolName)) {
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookAllow());
            return;
        }

        String filePath = McpHookServerUtil.str(input, "file_path");
        if (filePath == null || filePath.isBlank()) {
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookAllow());
            return;
        }
        String oldString = McpHookServerUtil.str(input, "old_string");
        String newString = McpHookServerUtil.str(input, "new_string");
        String writeContent = McpHookServerUtil.str(input, "content");

        if (!isFileAllowed(sessionId, filePath)) {
            McpHookServerUtil.sendJson(ex, 200,
                    McpHookServerUtil.hookDeny("Access denied: " + filePath + " is outside the allowed project scope for this session"));
            return;
        }

        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "Permission hook: {0} on {1} (session {2})",
                    new Object[]{toolName, filePath, sessionId});
        }

        var procListener = session.getAiProcessEventListener();
        if (procListener == null) {
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookAllow());
            return;
        }

        ReentrantLock sessionHookLock = hookLocks.get(sessionId);
        if (sessionHookLock == null) {
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookDefer());
            return;
        }
        sessionHookLock.lock();
        try {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            procListener.onAiProcessEvent(
                    new PermissionEvent(toolName, filePath, oldString, newString, writeContent, future));

            boolean allowed;
            try {
                allowed = future.get(120, TimeUnit.SECONDS);
            }
            catch (TimeoutException e) {
                LOG.log(Level.WARNING, "Permission request timed out for: {0}", filePath);
                allowed = false;
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                allowed = false;
            }
            catch (Exception e) {
                Exceptions.printStackTrace(e);
                allowed = false;
            }
            if (allowed) {
                // Acquire mutationLock so hook-applied writes are mutually exclusive with
                // MCP tool-call mutations (handleMcpToolCall also holds mutationLock).
                mutationLock.lock();
                String applyResult;
                try {
                    applyResult = "Write".equals(toolName)
                            ? RefactoringProvider.writeFileContent(filePath, writeContent)
                            : RefactoringProvider.applyEdit(filePath, oldString, newString);
                }
                finally {
                    mutationLock.unlock();
                }
                String allowedResponse = McpHookServerUtil.hookDeny("Applied by NetBeans plugin: " + applyResult);
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Hook response (applied): {0}", allowedResponse);
                }
                McpHookServerUtil.sendJson(ex, 200, allowedResponse);
            }
            else {
                String deniedResponse = McpHookServerUtil.hookDeny("User rejected - do not retry this change");
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Hook response (denied): {0}", deniedResponse);
                }
                McpHookServerUtil.sendJson(ex, 200, deniedResponse);
            }
        }
        finally {
            sessionHookLock.unlock();
        }
    }

    // ---- MCP Streamable HTTP endpoint (/mcp/{aiType}) ----
    private void handleMcp(HttpExchange ex) throws IOException {
        try {
            McpHookServerUtil.addCors(ex);
            String method = ex.getRequestMethod();
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "MCP {0} {1}", new Object[]{method, ex.getRequestURI()});
            }

            if ("OPTIONS".equalsIgnoreCase(method)) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if ("GET".equalsIgnoreCase(method)) {
                // Per the MCP Streamable-HTTP spec, a client MAY open a GET/SSE
                // stream for SERVER-initiated requests/notifications, and the server
                // MUST return either text/event-stream or 405 if it offers no such
                // stream. This server is request/response only (it never pushes
                // server-initiated messages), so it returns 405 — the definitive
                // "no SSE stream" signal. The previous "200 then immediately close"
                // advertised a stream and then killed it, so the client treated it
                // as broken and reconnected in a loop ("MCP server is connecting").
                // 405 stops that loop without holding a worker thread per connection.
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                ex.sendResponseHeaders(405, -1);
                return;
            }

            String body;
            try (InputStream in = ex.getRequestBody()) {
                byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    McpHookServerUtil.sendJson(ex, 413, "{\"error\":\"request too large\"}");
                    return;
                }
                body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "MCP body: {0}", body);
            }

            JsonObject req;
            try {
                req = McpHookServerUtil.GSON.fromJson(body, JsonObject.class);
            }
            catch (JsonSyntaxException e) {
                McpHookServerUtil.sendJson(ex, 400, "{\"error\":\"bad json\"}");
                return;
            }
            if (req == null) {
                McpHookServerUtil.sendJson(ex, 400, "{\"error\":\"bad json\"}");
                return;
            }

            if (!req.has("id") || req.get("id").isJsonNull()) {
                ex.sendResponseHeaders(202, -1);
                return;
            }

            JsonElement id = req.get("id");
            JsonElement methodEl = req.has("method") ? req.get("method") : null;
            String rpcMethod = (methodEl != null && !methodEl.isJsonNull() && methodEl.isJsonPrimitive())
                    ? methodEl.getAsString() : "";

            String path = ex.getRequestURI().getPath();
            String aiTypeKey = path != null && path.startsWith("/mcp/")
                    ? path.substring("/mcp/".length()) : null;
            AiTypeEnum aiType = aiTypeKey != null ? AiTypeEnum.fromKey(aiTypeKey) : null;

            JsonObject params = McpHookServerUtil.obj(req, "params");

            switch (rpcMethod) {
                case "initialize" -> {
                    String instructions = McpHookServerUtil.getInitializeStub();
                    JsonObject result = new JsonObject();
                    String clientProto = McpHookServerUtil.str(params, "protocolVersion");
                    result.addProperty("protocolVersion",
                            clientProto != null && !clientProto.isBlank() ? clientProto : "2024-11-05");
                    JsonObject caps = new JsonObject();
                    caps.add("tools", new JsonObject());
                    result.add("capabilities", caps);
                    JsonObject info = new JsonObject();
                    info.addProperty("name", StringConst.PLUGIN_ID);
                    info.addProperty("version", "1.0");
                    result.add("serverInfo", info);
                    result.addProperty("instructions", instructions);
                    McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.mcpOk(id, result));
                }
                case "tools/list" -> {
                    Map<McpToolEnum, McpToolInterface> handlers
                            = aiType != null ? McpInstructionRegistry.getHandlers(aiType) : Map.of();
                    JsonObject result = new JsonObject();
                    JsonArray tools = new JsonArray();
                    for (McpToolInterface h : handlers.values()) {
                        JsonObject schema = h.schema();
                        schema = McpHookServerUtil.injectSessionParams(schema);
                        tools.add(schema);
                    }
                    result.add("tools", tools);
                    McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.mcpOk(id, result));
                }
                case "tools/call" -> {
                    JsonObject argsObj = McpHookServerUtil.obj(params, "arguments");
                    String sessionId = McpHookServerUtil.str(argsObj, "sessionId");
                    String secretKey = McpHookServerUtil.str(argsObj, "secretKey");

                    if (sessionId == null || secretKey == null) {
                        McpHookServerUtil.sendJson(ex, 200,
                                McpHookServerUtil.mcpError(id, -32600, "Authentication failed"));
                        return;
                    }

                    if (!AiSessionInboxBroker.getInstance().validateSecret(sessionId, secretKey)) {
                        McpHookServerUtil.sendJson(ex, 200,
                                McpHookServerUtil.mcpError(id, -32600, "Authentication failed"));
                        return;
                    }

                    AbstractAiSession session = SessionRegistry.get(sessionId);

                    if (session == null) {
                        McpHookServerUtil.sendJson(ex, 200,
                                McpHookServerUtil.mcpError(id, -32600, "Unknown session"));
                        return;
                    }

                    String requestedName = McpHookServerUtil.str(params, "name");
                    McpToolEnum requestedTool = McpToolEnum.of(requestedName);
                    if (isToolGated(session.getAiSession().isInstructionsLoaded(), requestedTool)) {
                        McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.mcpTextResult(id,
                                "BLOCKED: call GetInstructions before using "
                                + (requestedName != null ? requestedName : "this tool")
                                + ". It returns the plugin usage guide and unlocks the other tools. "
                                + "Call GetInstructions now, then retry."));
                        return;
                    }

                    handleMcpToolCall(ex, req, id, session);
                }
                default ->
                    McpHookServerUtil.sendJson(ex, 200,
                            McpHookServerUtil.mcpError(id, -32601, "Method not found: " + rpcMethod));
            }
        }
        finally {
            try {
                ex.close();
            }
            catch (Exception ignored) {
            }
        }
    }

    private void handleMcpToolCall(HttpExchange ex, JsonObject req, JsonElement id,
            AbstractAiSession session) throws IOException {
        JsonObject params = McpHookServerUtil.obj(req, "params");
        String toolName = McpHookServerUtil.str(params, "name");
        JsonObject argsObj = McpHookServerUtil.obj(params, "arguments");

        McpToolEnum tool = McpToolEnum.of(toolName);
        if (tool == null) {
            McpHookServerUtil.sendJson(ex, 200,
                    McpHookServerUtil.mcpError(id, -32601, "Unknown tool: " + toolName));
            return;
        }
        McpToolInterface handler = session.getMcpToolHandlers().get(tool);
        if (handler == null) {
            McpHookServerUtil.sendJson(ex, 200,
                    McpHookServerUtil.mcpError(id, -32601, "Unhandled tool: " + toolName));
            return;
        }
        McpHookServerUtil.logToolUse(session.getSessionName(), toolName, argsObj);
        try {
            LockTypeEnum requiredLock = ToolLockRegistry.getLockType(tool, handler);
            LockManager lockManager = LockManager.getInstance();
            boolean lockAcquired = false;
            try {
                if (requiredLock != null) {
                    if (!lockManager.acquireLock(session.getId(), requiredLock)) {
                        String holder = lockManager.getLockHolder(requiredLock);
                        McpHookServerUtil.sendJson(ex, 200,
                                McpHookServerUtil.mcpTextResult(id,
                                        "Resource locked by session " + holder
                                        + ". Tool: " + toolName + ". Please try again shortly."));
                        return;
                    }
                    lockAcquired = true;
                }
                String result;
                if (!handler.isMutating()) {
                    result = handler.handle(new ToolRequestArguments(argsObj), session);
                }
                else {
                    boolean mutLockAcquired;
                    try {
                        mutLockAcquired = mutationLock.tryLock(900, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        mutLockAcquired = false;
                    }
                    if (!mutLockAcquired) {
                        McpHookServerUtil.sendJson(ex, 200,
                                McpHookServerUtil.mcpTextResult(id,
                                        "Error: mutation lock timeout — another operation is blocking. Please try again."));
                        return;
                    }
                    try {
                        result = handler.handle(new ToolRequestArguments(argsObj), session);
                    }
                    finally {
                        mutationLock.unlock();
                    }
                }
                McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.mcpTextResult(id, result));
            }
            finally {
                if (lockAcquired && requiredLock != null) {
                    lockManager.releaseLock(session.getId(), requiredLock);
                }
            }
        }
        catch (McpArgumentException e) {
            McpHookServerUtil.sendJson(ex, 200,
                    McpHookServerUtil.mcpError(id, e.getCode(), e.getMessage()));
        }
        catch (Exception e) {
            Exceptions.printStackTrace(e);
            McpHookServerUtil.sendJson(ex, 200,
                    McpHookServerUtil.mcpTextResult(id,
                            "Error in " + toolName + ": internal error"));
        }
    }
}
