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
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
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
            // Path may not exist yet (e.g. WriteFile creating a new file).
            // Resolve the deepest existing ancestor so symlinked paths still
            // match the registered project dirs, then re-append the tail.
            java.nio.file.Path abs = p.toAbsolutePath().normalize();
            java.nio.file.Path tail = abs.getFileName();
            java.nio.file.Path parent = abs.getParent();
            while (parent != null) {
                try {
                    return parent.toRealPath().resolve(tail);
                }
                catch (IOException ex) {
                    tail = parent.getFileName().resolve(tail);
                    parent = parent.getParent();
                }
            }
            return abs;
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

    private HttpServer httpServer;
    private int port;
    private ExecutorService executor;
    private final Set<String> activeSessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, ReentrantLock> hookLocks = new ConcurrentHashMap<>();
    private final Map<String, List<java.io.File>> sessionProjectDirs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sessionRestrictToProject = new ConcurrentHashMap<>();
    private final Map<String, String> sessionAiTypeKey = new ConcurrentHashMap<>();
    private boolean started = false;
    private volatile boolean stopped = false;
    private String name = "";
    // Captured at init() so getBaseUrl()/getPort() stay valid after stop() nulls httpServer.
    private volatile String baseUrl = null;

    public McpHookServer(int port) {
        this.port = port;
    }

    public void init() throws IOException {
        applyConnectionSettings();
        try {
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), getPort()), 0);
            this.port = httpServer.getAddress().getPort();
            this.name = "hook-server-" + getPort();
            InetAddress boundAddr = httpServer.getAddress().getAddress();
            String host = boundAddr.getHostAddress();
            if (boundAddr instanceof Inet6Address) {
                host = "[" + host + "]";
            }
            this.baseUrl = "http://" + host + ":" + getPort();

            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, StringConst.PLUGIN_ID + "-mcp-server-" + getPort());
                t.setDaemon(true);
                return t;
            });

            httpServer.setExecutor(executor);
            httpServer.createContext("/", this::handle);
            httpServer.createContext("/mcp", this::handleMcp);
            LOG.log(Level.INFO, "Inited {0}", this.name);
        }
        catch (IOException e) {
            if (httpServer != null) {
                try {
                    httpServer.stop(0);
                }
                catch (Exception ex1) {
                }

                httpServer = null;
            }

            if (executor != null) {
                executor.shutdown();
                executor = null;
            }

            throw e;
        }
    }

    /**
     * Start accepting connections. Called only by the {@link McpServerRegistry}
     * supervisor after a successful {@link #init()}. Idempotent.
     */
    public void start() {
        if (started) {
            return;
        }
        if (httpServer == null) {
            throw new IllegalStateException("start() called before init()");
        }
        httpServer.start();
        started = true;
        LOG.log(Level.INFO, "MCP hook server listening on port {0}", getPort());
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
    public void registerSession(String sessionId, AiTypeEnum aiType,
            List<java.io.File> projectDirs, boolean restrictToProjectFiles) {
        if (sessionId == null) {
            return;
        }
        // Pure bookkeeping: the supervisor (McpServerRegistry) owns starting the
        // HTTP listener via start(), so by the time a session registers here the
        // server is already accepting connections. The backing maps are all
        // concurrent, so no synchronization is required.
        sessionAiTypeKey.put(sessionId, aiType.key());
        sessionProjectDirs.put(sessionId, projectDirs);
        sessionRestrictToProject.put(sessionId, restrictToProjectFiles);
        LockManager.getInstance().releaseOrphanedLocks(Set.copyOf(activeSessions));
        if (activeSessions.add(sessionId)) {
            hookLocks.put(sessionId, new ReentrantLock(true));
        }
    }

    /**
     * Refreshes a registered session's file-access scope (project dirs +
     * restrict flag). If the session is not currently tracked (hook-server
     * restart, or a lost/startup-race registration), re-registers it rather
     * than no-oping — a silently untracked session would bypass the diff panel
     * indefinitely. Only open sessions call this (handleSubmit + the
     * open-projects listener), and componentClosed removes that listener and
     * calls unregisterSession, so this cannot resurrect a closed session.
     */
    public void updateSessionScope(String sessionId, AiTypeEnum aiType,
            List<java.io.File> projectDirs, boolean restrictToProjectFiles) {
        if (sessionId == null) {
            return;
        }
        if (aiType != null) {
            sessionAiTypeKey.put(sessionId, aiType.key());
        }
        sessionProjectDirs.put(sessionId, projectDirs);
        sessionRestrictToProject.put(sessionId, restrictToProjectFiles);
        if (activeSessions.add(sessionId)) {
            hookLocks.put(sessionId, new ReentrantLock(true));
        }
    }

    public void unregisterSession(String sessionId) {
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
        // Restrict is on: the file must resolve inside one of the session's registered
        // project roots. An empty dir list fails closed (never fail-open, which would
        // open the whole FS if scope was never populated).
        return isWithinProjectDirs(sessionId, filePath);
    }

    /**
     * True if {@code filePath} resolves to a location inside one of the
     * session's registered project roots. Independent of the
     * restrict-to-project flag, so a caller can ask "is this a project file?"
     * directly. Fails closed when the session has no registered roots.
     */
    boolean isWithinProjectDirs(String sessionId, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        List<java.io.File> dirs = sessionProjectDirs.get(sessionId);
        if (dirs == null || dirs.isEmpty()) {
            return false;
        }
        java.nio.file.Path resolvedFile = resolveRealPath(java.nio.file.Path.of(filePath));
        return dirs.stream().anyMatch(d -> {
            java.nio.file.Path dir = resolveRealPath(d.toPath());
            return resolvedFile.equals(dir) || resolvedFile.startsWith(dir);
        });
    }

    boolean isUnderAnyOpenProject(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        java.nio.file.Path f = resolveRealPath(java.nio.file.Path.of(filePath));
        for (org.netbeans.api.project.Project p : org.netbeans.api.project.ui.OpenProjects.getDefault().getOpenProjects()) {
            java.nio.file.Path d = resolveRealPath(java.nio.file.Path.of(p.getProjectDirectory().getPath()));
            if (f.equals(d) || f.startsWith(d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if {@code filePath} is inside this session's own per-session config
     * directory ({@code ~/.ai-coder/{type}/{sessionId}/}), where the AI keeps
     * its memory and logs. These live outside every open project, so no diff
     * panel can be built for them; they pass straight through to the built-in
     * tool. Scoped to the requesting session, so one session can never write
     * into another session's memory.
     */
    public boolean isOwnSessionConfigFile(String sessionId, String filePath) {
        if (sessionId == null || filePath == null || filePath.isBlank()) {
            return false;
        }
        String aiTypeKey = sessionAiTypeKey.get(sessionId);
        if (aiTypeKey == null || aiTypeKey.isBlank()) {
            return false;
        }
        try {
            java.nio.file.Path sessionDir = PluginUtil.getPluginConfigDir()
                    .resolve(aiTypeKey).resolve(sessionId);
            java.nio.file.Path resolvedDir = resolveRealPath(sessionDir);
            java.nio.file.Path resolvedFile = resolveRealPath(java.nio.file.Path.of(filePath));
            return resolvedFile.equals(resolvedDir) || resolvedFile.startsWith(resolvedDir);
        }
        catch (IOException e) {
            return false;
        }
    }

    public int getPort() {
        return port;
    }

    /**
     * The server's base URL (e.g. {@code http://127.0.0.1:PORT}). Captured at
     * {@link #init()} so it remains valid after {@link #stop()} nulls the
     * underlying httpServer (finding 5). Null only if init() never ran.
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * True once {@link #stop()} has been called; a stopped server cannot serve.
     */
    public boolean isStopped() {
        return stopped;
    }

    public synchronized void stop() {
        // Mark stopped first, unconditionally, so it is always observable even if
        // shutdown throws — this lets the supervisor detect and replace a dead
        // server instead of handing one back (which would leave MCP down until
        // NetBeans restarts). Null-guard everything so a double stop() or a
        // stop() after a failed init() cannot NPE.
        stopped = true;
        started = false;
        if (httpServer != null) {
            try {
                httpServer.stop(0);
            }
            catch (Exception e) {
                LOG.log(Level.FINE, "httpServer.stop threw", e);
            }
            httpServer = null;
        }
        if (executor != null) {
            try {
                executor.shutdown();
            }
            catch (Exception e) {
                LOG.log(Level.FINE, "executor.shutdown threw", e);
            }
            executor = null;
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
            LOG.log(Level.WARNING, "Hook Event Not Implemented: {0}", hookEventName);
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
        // Edit/Write without a path cannot be previewed — fail closed.
        if (filePath == null || filePath.isBlank()) {
            McpHookServerUtil.sendJson(ex, 200,
                    McpHookServerUtil.hookDeny("Access denied: missing file_path"));
            return;
        }
        String oldString = McpHookServerUtil.str(input, "old_string");
        String newString = McpHookServerUtil.str(input, "new_string");
        String writeContent = McpHookServerUtil.str(input, "content");

        // Out-of-project files cannot be shown in the Accept/Reject diff panel, so they
        // are decided here instead of falling through to the panel — which would defer
        // forever (the "no panel, no response" hang).
        //
        // 1. The session's OWN per-session config dir (memory, logs) always passes
        //    straight through to the built-in tool. Scoped to this session's dir, so one
        //    session can never write into another session's memory.
        if (isOwnSessionConfigFile(sessionId, filePath)) {
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookAllow());
            return;
        }
        // 2. A file outside every open project cannot be diffed. Honour the restrict flag:
        //    restrict ON -> deny (session is scoped to its projects); restrict OFF -> let
        //    the built-in tool write it directly. Files inside a project fall through to
        //    the diff panel below.
        if (!isWithinProjectDirs(sessionId, filePath) && !isUnderAnyOpenProject(filePath)) {
            boolean restrict = Boolean.TRUE.equals(sessionRestrictToProject.get(sessionId));
            McpHookServerUtil.sendJson(ex, 200, restrict
                    ? McpHookServerUtil.hookDeny("Access denied: " + filePath + " is outside the allowed project scope for this session")
                    : McpHookServerUtil.hookAllow());
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

        // Per-file lock, held from before the diff is shown through the decision and the
        // write — scoped to just this file, so a concurrent diff on a different file (from
        // this session or another) is unaffected. Also keeps this native-hook path and the
        // MCP-tool WriteFile/ApplyEdit path (which locks the same way) mutually exclusive on
        // the same file, since they'd otherwise share no coordination at all.
        LockManager lockManager = LockManager.getInstance();
        if (!lockManager.acquireFileLock(sessionId, filePath)) {
            String holder = lockManager.getFileLockHolder(filePath);
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookDeny(
                    "File is locked by " + (holder != null ? "session " + holder : "another in-progress edit")
                    + " — try again shortly"));
            return;
        }
        sessionHookLock.lock();
        try {
            CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
            procListener.onAiProcessEvent(
                    new PermissionEvent(toolName, filePath, oldString, newString, writeContent, future));

            PermissionDecision decision;
            try {
                decision = future.get(120, TimeUnit.SECONDS);
            }
            catch (TimeoutException e) {
                LOG.log(Level.WARNING, "Permission request timed out for: {0}", filePath);
                // Distinct from a genuine rejection: the user simply never acted on the
                // diff panel in time. Say so and mark it retryable — a real rejection ends
                // with "do not retry this change", which would be wrong advice here.
                decision = PermissionDecision.denied(
                        "Timed out waiting for the user to review this change in the diff panel — "
                        + "the user did not respond in time. You may retry.");
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                decision = PermissionDecision.denied(null);
            }
            catch (Exception e) {
                Exceptions.printStackTrace(e);
                decision = PermissionDecision.denied(null);
            }
            if (decision != null && decision.allow()) {
                String applyResult = "Write".equals(toolName)
                        ? RefactoringProvider.writeFileContent(filePath, writeContent)
                        : RefactoringProvider.applyEdit(filePath, oldString, newString);
                String allowedResponse = McpHookServerUtil.hookDeny("Applied by NetBeans plugin: " + applyResult);
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Hook response (applied): {0}", allowedResponse);
                }
                McpHookServerUtil.sendJson(ex, 200, allowedResponse);
            }
            else {
                String deniedResponse = McpHookServerUtil.hookDeny(
                        decision != null
                                ? decision.effectiveDenyMessage("User rejected - do not retry this change")
                                : "User rejected - do not retry this change");
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "Hook response (denied): {0}", deniedResponse);
                }
                McpHookServerUtil.sendJson(ex, 200, deniedResponse);
            }
        }
        finally {
            sessionHookLock.unlock();
            lockManager.releaseFileLock(sessionId, filePath);
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
                        tools.add(h.schema(McpInstructionOptions.cli()));
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
            String result = McpToolInvoker.invoke(tool, handler, argsObj, session);
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.mcpTextResult(id, result));
        }
        catch (McpArgumentException e) {
            McpHookServerUtil.sendJson(ex, 200,
                    McpHookServerUtil.mcpError(id, e.getCode(), e.getMessage()));
        }
        catch (IOException e) {
            throw e;
        }
        catch (Exception e) {
            if (PluginSettings.isLogToolUse()) {
                Exceptions.printStackTrace(e);
            }
            else {
                LOG.log(Level.FINE, "Tool failure: " + toolName, e);
            }
            McpHookServerUtil.sendJson(ex, 200,
                    McpHookServerUtil.mcpError(id, -32603, "Internal error: " + e.getMessage()));
        }
    }
}
