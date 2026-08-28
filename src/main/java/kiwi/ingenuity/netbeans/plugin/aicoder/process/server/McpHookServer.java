package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
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
    private static final int IDLE_INTERVAL_SECONDS = (int) (TimeoutEnum.MCP_HTTP_IDLE_INTERVAL_MILLIS.millis() / 1000);   // JDK default 30 — idle keep-alive window
    private static final int MAX_IDLE_CONNECTIONS = 200;       // JDK default 200 — cached idle connections
    private static final int MAX_CONNECTIONS = -1;             // JDK default -1 — total connection cap (unlimited)
    private static final int MAX_REQ_TIME_SECONDS = -1;        // JDK default -1 — max time to read a request (off)
    private static final int MAX_RSP_TIME_SECONDS = -1;        // JDK default -1 — max time to write a response (off)

    /**
     * Gate predicate: while a conversation has not loaded the full instruction guide, every tool except GetInstructions
     * is blocked. An unknown tool (null) is also blocked so the AI is steered to GetInstructions first.
     */
    static boolean isToolGated(boolean instructionsLoaded, McpToolEnum tool) {
        if (instructionsLoaded) {
            return false;
        }
        return tool != McpToolEnum.GET_INSTRUCTIONS;
    }

    /**
     * Applies the HTTP connection-management settings above as JDK system properties. Must run before the first
     * HttpServer is created in the JVM, since com.sun.net.httpserver reads them once at ServerConfig init.
     */
    private static void applyConnectionSettings() {
        System.setProperty("sun.net.httpserver.idleInterval", Integer.toString(IDLE_INTERVAL_SECONDS));
        System.setProperty("sun.net.httpserver.maxIdleConnections", Integer.toString(MAX_IDLE_CONNECTIONS));
        System.setProperty("jdk.httpserver.maxConnections", Integer.toString(MAX_CONNECTIONS));
        System.setProperty("sun.net.httpserver.maxReqTime", Integer.toString(MAX_REQ_TIME_SECONDS));
        System.setProperty("sun.net.httpserver.maxRspTime", Integer.toString(MAX_RSP_TIME_SECONDS));
    }

    public static String fileAccessDeniedMessage(McpHookServer server, String sessionId, String filePath) {
        if (sessionId == null || sessionId.isBlank()) {
            return "Access denied: file access scope is unavailable because this tool call has no "
                    + McpToolPropertyEnum.SESSION_ID.key() + ". Retry with a valid session identity.";
        }
        if (server == null) {
            return "Access denied: file access scope is unavailable because the MCP server is not running. "
                    + "Retry after MCP session setup completes.";
        }
        // A path the filesystem cannot represent is refused for a completely different reason than one that is simply
        // out of scope, and saying "outside the allowed project scope" for it is misleading: a caller reads that as a
        // permissions problem and retries with a different prefix, when the real fault is a stray character in the
        // string it sent. The scope check denies both — correctly, and fail-closed — so only the explanation needs to
        // tell them apart.
        if (SessionFileScopeRegistry.isMalformedPath(filePath)) {
            return "Malformed path: the supplied path contains characters the filesystem cannot represent, so it "
                    + "cannot refer to any file. Check the path for stray control characters and resend it.";
        }
        return server.fileScope.fileAccessDeniedMessage(sessionId, filePath);
    }

    /**
     * Static, null-tolerant form of {@link #isFileAccessible(String, String)} — see {@link #isProjectFileAllowed} for
     * why a static overload exists (the {@code server == null || sessionId == null || ...} guard repeated at each call
     * site collapses into one call). This is the rule for the fourteen plain-scope tools
     * (Delete/Copy/Move/Close/NavigateToLine, Reformat, the refactor tools, and organise-imports/fix-imports): a
     * session may operate on its own config directory with any of them, the same as it may operate on a project file.
     */
    public static boolean isFileAccessible(McpHookServer server, String sessionId, String filePath) {
        return server != null && sessionId != null && server.isFileAccessible(sessionId, filePath);
    }

    /**
     * May this session access {@code filePath} under the plain project-scope rule ONLY — {@link #isFileAllowed}, with
     * no config-dir exemption. This is deliberately narrower than {@link #isFileAccessible}: it exists for the write
     * tools (ApplyEdit, WriteFile, SaveFile), which check {@link
     * #isOwnSessionConfigFile} explicitly first — that branch bypasses review entirely, so once it has been ruled out,
     * the remaining gate must NOT grant the config-dir exemption a second time.
     * <p>
     * Static and tolerant of a null {@code server} (unlike the instance methods above, which assume a live server) so
     * that the {@code server == null || sessionId == null || !server.isFileAllowed(...)} guard repeated verbatim at
     * each call site collapses into one call: {@code if (!McpHookServer.isProjectFileAllowed(server, sessionId, fp))}.
     */
    public static boolean isProjectFileAllowed(McpHookServer server, String sessionId, String filePath) {
        return server != null && sessionId != null
                && !server.isSessionPersistenceWriteDenied(filePath)
                && server.isFileAllowed(sessionId, filePath);
    }

    /**
     * May this session WRITE {@code filePath} under the plain-scope rule — {@link #isFileAccessible}, minus anything
     * {@link SessionFileScopeRegistry#isSessionPersistenceWriteDenied} refuses. The write counterpart of
     * {@link #isFileAccessible}, for the mutating members of that tool group (Delete, Move, and Copy's destination):
     * those three share the config-dir exemption with the read tools, so they cannot use {@link #isProjectFileAllowed},
     * but they must not inherit the read exemption granted to {@code sessions.json} and the template files at the
     * persistence base's root.
     * <p>
     * Copy's SOURCE deliberately keeps using {@link #isFileAccessible}: reading a file out is a read, and denying it
     * there would take away an access the read tools still grant. Move's source does not — moving a file away deletes
     * it from where it was.
     */
    public static boolean isFileWritable(McpHookServer server, String sessionId, String filePath) {
        return isFileAccessible(server, sessionId, filePath)
                && !server.isSessionPersistenceWriteDenied(filePath);
    }

    private HttpServer httpServer;
    private int port;
    private ExecutorService executor;
    private final Set<String> activeSessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, ReentrantLock> hookLocks = new ConcurrentHashMap<>();
    // All file/project access-scope state and policy: see SessionFileScopeRegistry's
    // class javadoc for the two directory trees it distinguishes.
    private final SessionFileScopeRegistry fileScope = new SessionFileScopeRegistry();
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
     * Start accepting connections. Called only by the {@link McpServerRegistry} supervisor after a successful
     * {@link #init()}. Idempotent.
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

    // ---- Public API ----
    /**
     * Register a session's file-scope data. The shared /mcp endpoint handles all sessions by validating
     * sessionId+secretKey from tool arguments.
     *
     * @param aiTypeKey AI type key from {@code AiTypeEnum.key()}, e.g. {@code "claude"}
     */
    public void registerSession(String sessionId, AiTypeEnum aiType,
            List<File> projectDirs, boolean restrictToProjectFiles) {
        if (sessionId == null) {
            return;
        }
        // Pure bookkeeping: the supervisor (McpServerRegistry) owns starting the
        // HTTP listener via start(), so by the time a session registers here the
        // server is already accepting connections.
        fileScope.registerScope(sessionId, aiType, projectDirs, restrictToProjectFiles);
        LockManager.getInstance().releaseOrphanedLocks(Set.copyOf(activeSessions));
        if (activeSessions.add(sessionId)) {
            hookLocks.put(sessionId, new ReentrantLock(true));
        }
    }

    /**
     * Refreshes a registered session's file-access scope (project dirs + restrict flag). If the session is not
     * currently tracked (hook-server restart, or a lost/startup-race registration), re-registers it rather than
     * no-oping — a silently untracked session would bypass the diff panel indefinitely. Only open sessions call this
     * (handleSubmit + the open-projects listener), and componentClosed removes that listener and calls
     * unregisterSession, so this cannot resurrect a closed session.
     */
    public void updateSessionScope(String sessionId, AiTypeEnum aiType,
            List<File> projectDirs, boolean restrictToProjectFiles) {
        if (sessionId == null) {
            return;
        }
        fileScope.updateScope(sessionId, aiType, projectDirs, restrictToProjectFiles);
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
        // Keep the last scope snapshot during teardown so in-flight calls see the
        // same policy they started with. Unknown sessions still fail closed because
        // they never had a restrict entry.
        // Lifecycle is owned by McpServerRegistry. Self-stopping here would create
        // a second owner and allow reuse of a dead HttpServer.
    }

    public boolean isFileAllowed(String sessionId, String filePath) {
        return fileScope.isFileAllowed(sessionId, filePath);
    }

    /**
     * True when {@code filePath} is ANY session's serialized-conversation history/context file — see
     * {@link SessionFileScopeRegistry}'s class javadoc for the full rationale (a directory tree distinct from {@link
     * #isOwnSessionConfigFile}'s, vetoed for every session rather than exempted for the caller's own). The native
     * Claude Edit/Write hook does not call {@link #isFileAllowed} (it inlines the equivalent checks), so it re-checks
     * this directly instead of inheriting it — see the hook dispatch below.
     */
    private boolean isSessionPersistenceDirFile(String filePath) {
        return fileScope.isSessionPersistenceDirFile(filePath);
    }

    /**
     * True when {@code filePath} may not be written anywhere under the serialized-conversation tree — see
     * {@link SessionFileScopeRegistry#isSessionPersistenceWriteDenied} for why this is wider than
     * {@link #isSessionPersistenceDirFile} and where the read/write split falls.
     */
    boolean isSessionPersistenceWriteDenied(String filePath) {
        return fileScope.isSessionPersistenceWriteDenied(filePath);
    }

    boolean isUnrestrictedFileAccess(String sessionId) {
        return fileScope.isUnrestrictedFileAccess(sessionId);
    }

    public String fileAccessDeniedMessage(String sessionId, String filePath) {
        return fileAccessDeniedMessage(this, sessionId, filePath);
    }

    /**
     * True if {@code filePath} resolves to a location inside one of the session's registered project roots. Independent
     * of the restrict-to-project flag, so a caller can ask "is this a project file?" directly. Fails closed when the
     * session has no registered roots.
     */
    boolean isWithinProjectDirs(String sessionId, String filePath) {
        return fileScope.isWithinProjectDirs(sessionId, filePath);
    }

    boolean isUnderAnyOpenProject(String filePath) {
        return fileScope.isUnderAnyOpenProject(filePath);
    }

    /**
     * True if {@code filePath} is inside this session's own per-session config directory
     * ({@code ~/.ai-coder/{type}/{sessionId}/}), where the AI keeps its memory and logs. These live outside every open
     * project, so no diff panel can be built for them; they pass straight through to the built-in tool. Scoped to the
     * requesting session, so one session can never write into another session's memory.
     */
    public boolean isOwnSessionConfigFile(String sessionId, String filePath) {
        return fileScope.isOwnSessionConfigFile(sessionId, filePath);
    }

    /**
     * May this session access {@code filePath} at all — for a plain read/query/action gate, not a write that needs the
     * diff-panel routing decision below. True when either {@link #isFileAllowed} (in project scope) or
     * {@link #isOwnSessionConfigFile} (this session's own memory/logs/tool_results, exempt from restrict-to-project)
     * holds.
     * <p>
     * This is the single source of truth for that OR — it was previously written out at each read-style call site, and
     * one of them (GetFileContentTool) was found with only the first half, so a session could write its own log via a
     * tool that already used both checks and then be refused reading it back through one that had only {@link
     * #isFileAllowed}. Confirmed by the user to be the correct rule for every plain-scope tool —
     * Delete/Copy/Move/Close/NavigateToLine/Reformat, the refactor tools, and organise-imports/fix-imports included: a
     * session's own config directory follows the same rule as a project file for the session that owns it, for all of
     * these.
     * <p>
     * Do NOT use this for the write tools (ApplyEdit, WriteFile, SaveFile) or the native Claude Edit/Write hook: those
     * need the two predicates evaluated as a routing decision, not flattened into one boolean. Own-config-dir writes
     * bypass the diff panel and fire no notification because that data belongs to the session itself and is
     * auto-accepted by design — not because a panel could not be built for it — so collapsing the two checks would let
     * an ordinary project file take the no-review/no-notification branch too.
     */
    public boolean isFileAccessible(String sessionId, String filePath) {
        return fileScope.isFileAccessible(sessionId, filePath);
    }

    /**
     * Resolves this session's own per-session config directory ({@code ~/.ai-coder/{type}/{sessionId}/}), or null when
     * the session type is unknown. The build/test providers park complete build logs there so the session can read them
     * back via {@link #isOwnSessionConfigFile} even under restrict-to-project.
     */
    public Path sessionConfigDirOrNull(String sessionId) {
        return fileScope.sessionConfigDirOrNull(sessionId);
    }

    public int getPort() {
        return port;
    }

    /**
     * The server's base URL (e.g. {@code http://127.0.0.1:PORT}). Captured at {@link #init()} so it remains valid after
     * {@link #stop()} nulls the underlying httpServer (finding 5). Null only if init() never ran.
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
            LOG.log(Level.INFO, "Hook POST body: {0}", McpHookServerUtil.redactAllSecrets(body));
        }
        JsonObject req;
        try {
            req = McpHookServerUtil.GSON.fromJson(body, JsonObject.class);
        }
        catch (JsonSyntaxException e) {
            LOG.log(Level.WARNING, "Hook: bad JSON: {0}", McpHookServerUtil.redactAllSecrets(body));
            McpHookServerUtil.sendJson(ex, 400, "{\"error\":\"bad json\"}");
            return;
        }
        handleRequest(ex, req);
    }

    private void handleRequest(HttpExchange ex, JsonObject req) throws IOException {
        // Claude's hook vocabulary, not ours. ClaudeHookKeyEnum exists so these
        // cannot be mistaken for McpToolPropertyEnum's camelCase equivalents —
        // Claude sends session_id and file_path, our tools take sessionId and
        // filePath, and aligning either to the other breaks something silently.
        String hookEventName = McpHookServerUtil.str(req, ClaudeHookKeyEnum.HOOK_EVENT_NAME.key());
        if (hookEventName != null && !"PreToolUse".equals(hookEventName)) {
            // PostToolUse, Notification, Stop, etc. — not yet implemented, no-op
            LOG.log(Level.WARNING, "Hook Event Not Implemented: {0}", hookEventName);
            McpHookServerUtil.sendJson(ex, 200, "{}");
            return;
        }

        String sessionId = McpHookServerUtil.str(req, ClaudeHookKeyEnum.SESSION_ID.key());
        String toolName = McpHookServerUtil.str(req, ClaudeHookKeyEnum.TOOL_NAME.key());
        JsonObject input = McpHookServerUtil.obj(req, ClaudeHookKeyEnum.TOOL_INPUT.key());

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

        String filePath = McpHookServerUtil.str(input, ClaudeHookKeyEnum.FILE_PATH.key());
        // Edit/Write without a path cannot be previewed — fail closed.
        if (filePath == null || filePath.isBlank()) {
            // Names Claude's key, not ours: the caller here is Claude's Edit/Write
            // hook, so telling it "missing filePath" would name a field it never
            // sends. This is why the two vocabularies have separate enums.
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookDeny(
                    "Access denied: missing " + ClaudeHookKeyEnum.FILE_PATH.key()));
            return;
        }
        String oldString = McpHookServerUtil.str(input, ClaudeHookKeyEnum.OLD_STRING.key());
        String newString = McpHookServerUtil.str(input, ClaudeHookKeyEnum.NEW_STRING.key());
        String writeContent = McpHookServerUtil.str(input, ClaudeHookKeyEnum.CONTENT.key());
        // Edit's replace_all. Read here rather than dropped: it is part of the caller's contract, and ignoring it
        // applied ONE replacement while answering "File updated and saved" — a partial edit reported as complete,
        // with nothing to alert the caller. Threaded to both the preview and the apply, which must agree exactly.
        boolean replaceAll = input.has(ClaudeHookKeyEnum.REPLACE_ALL.key())
                && input.get(ClaudeHookKeyEnum.REPLACE_ALL.key()).getAsJsonPrimitive().isBoolean()
                && input.get(ClaudeHookKeyEnum.REPLACE_ALL.key()).getAsBoolean();

        // 0. ANY session's serialized-conversation directory (history.json, context.json,
        //    and their siblings) is never accessible to any tool, including this native
        //    hook and regardless of which session is asking — see
        //    isSessionPersistenceDirFile for why. Checked first because it does NOT call
        //    isFileAllowed (whose own veto this bypasses otherwise) and must not fall
        //    through to either case below.
        //    Uses the WRITE predicate, not the read one: this hook only ever fires for
        //    Edit and Write (see the matcher registered by ClaudeAiMcpRegistrar), so the
        //    base-level read exemption for sessions.json must not apply here. Without
        //    this, an unrestricted session reached sessions.json through case 2 below
        //    and was answered hookAllow — a write with no diff panel at all.
        if (isSessionPersistenceWriteDenied(filePath)) {
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookDeny(fileAccessDeniedMessage(sessionId, filePath)));
            return;
        }

        // Two cases are decided here, before falling through to the diff panel below —
        // not because a diff panel could not be rendered for these paths (it could: the
        // panel builds its diff from originalContent/proposedContent strings, not from a
        // FileObject anchored to a project), but because these are deliberate policy
        // decisions about which writes get reviewed at all.
        //
        // 1. The session's OWN per-session config dir (memory, logs) always passes
        //    straight through to the built-in tool with no diff, no PermissionEvent, and
        //    no notification. This is a product decision: a session's own working data is
        //    auto-accepted rather than reviewed. Scoped to this session's dir, so one
        //    session can never write into another session's memory.
        if (isOwnSessionConfigFile(sessionId, filePath)) {
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookAllow());
            return;
        }
        // 2. A file outside every open project is not offered a diff review either;
        //    instead the restrict flag decides its fate directly: restrict ON -> deny
        //    (session is scoped to its projects); restrict OFF -> let the built-in tool
        //    write it directly. Files inside a project fall through to the diff panel below.
        if (!isWithinProjectDirs(sessionId, filePath) && !isUnderAnyOpenProject(filePath)) {
            McpHookServerUtil.sendJson(ex, 200, isUnrestrictedFileAccess(sessionId)
                    ? McpHookServerUtil.hookAllow()
                    : McpHookServerUtil.hookDeny(fileAccessDeniedMessage(sessionId, filePath)));
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
            McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.hookDeny(
                    LockManager.fileLockedMessage(lockManager.getFileLockHolder(filePath))));
            return;
        }
        sessionHookLock.lock();
        try {
            CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
            procListener.onAiProcessEvent(
                    new PermissionEvent(toolName, filePath, oldString, newString, writeContent, replaceAll, future));

            PermissionDecision decision;
            try {
                decision = future.get(TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
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
                        : RefactoringProvider.applyEdit(filePath, oldString, newString, replaceAll);
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
                LOG.log(Level.INFO, "MCP body: {0}", McpHookServerUtil.redactSecrets(body));
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

            // The MCP JSON-RPC envelope we serve — a different vocabulary from
            // Claude's hook payload handled above, hence a different enum.
            String idKey = McpProtocolKeyEnum.ID.key();
            String methodKey = McpProtocolKeyEnum.METHOD.key();
            if (!req.has(idKey) || req.get(idKey).isJsonNull()) {
                ex.sendResponseHeaders(202, -1);
                return;
            }

            JsonElement id = req.get(idKey);
            JsonElement methodEl = req.has(methodKey) ? req.get(methodKey) : null;
            String rpcMethod = (methodEl != null && !methodEl.isJsonNull() && methodEl.isJsonPrimitive())
                    ? methodEl.getAsString() : "";

            String path = ex.getRequestURI().getPath();
            String aiTypeKey = path != null && path.startsWith("/mcp/")
                    ? path.substring("/mcp/".length()) : null;
            AiTypeEnum aiType = aiTypeKey != null ? AiTypeEnum.fromKey(aiTypeKey) : null;

            JsonObject params = McpHookServerUtil.obj(req, McpProtocolKeyEnum.PARAMS.key());

            switch (rpcMethod) {
                case "initialize" -> {
                    String instructions = McpHookServerUtil.getInitializeStub(
                            aiType != null ? aiType.getMcpOptions() : Set.of());
                    JsonObject result = new JsonObject();
                    String clientProto = McpHookServerUtil.str(params, McpProtocolKeyEnum.PROTOCOL_VERSION.key());
                    result.addProperty(McpProtocolKeyEnum.PROTOCOL_VERSION.key(),
                            clientProto != null && !clientProto.isBlank() ? clientProto : "2024-11-05");
                    JsonObject caps = new JsonObject();
                    caps.add(McpProtocolKeyEnum.TOOLS.key(), new JsonObject());
                    result.add(McpProtocolKeyEnum.CAPABILITIES.key(), caps);
                    JsonObject info = new JsonObject();
                    info.addProperty(McpProtocolKeyEnum.NAME.key(), StringConst.PLUGIN_ID);
                    info.addProperty(McpProtocolKeyEnum.VERSION.key(), "1.0");
                    result.add(McpProtocolKeyEnum.SERVER_INFO.key(), info);
                    result.addProperty(McpProtocolKeyEnum.INSTRUCTIONS.key(), instructions);
                    McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.mcpOk(id, result));
                }
                case "tools/list" -> {
                    Map<McpToolEnum, McpToolInterface> handlers
                            = aiType != null ? McpInstructionRegistry.getHandlers(aiType) : Map.of();
                    Set<McpInstructionOptionEnum> toolOptions
                            = aiType != null ? aiType.getMcpOptions() : Set.of();
                    JsonObject result = new JsonObject();
                    JsonArray tools = new JsonArray();
                    for (McpToolInterface h : handlers.values()) {
                        tools.add(h.schema(toolOptions));
                    }
                    result.add(McpProtocolKeyEnum.TOOLS.key(), tools);
                    McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.mcpOk(id, result));
                }
                case "tools/call" -> {
                    // The envelope key is MCP's; what is inside it are OUR tool
                    // properties. Two vocabularies, one line apart.
                    JsonObject argsObj = McpHookServerUtil.obj(params, McpProtocolKeyEnum.ARGUMENTS.key());
                    String sessionId = McpHookServerUtil.str(argsObj, McpToolPropertyEnum.SESSION_ID.key());
                    String secretKey = McpHookServerUtil.str(argsObj, McpToolPropertyEnum.SECRET_KEY.key());

                    // Both failures used to read "Authentication failed", which
                    // tells the model nothing it can act on — it cannot see which
                    // of the two values we objected to, or that its own identity
                    // block is the fix. Named separately so a model that dropped
                    // or corrupted a credential can repair the call itself
                    // instead of retrying the same broken arguments.
                    if (sessionId == null || secretKey == null) {
                        McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.mcpError(id, -32600,
                                "Authentication failed: "
                                + McpToolPropertyEnum.SESSION_ID.key() + " and "
                                + McpToolPropertyEnum.SECRET_KEY.key()
                                + " are both required on every tool call. Copy them verbatim from your session identity block."));
                        return;
                    }

                    if (!AiSessionInboxBroker.getInstance().validateSecret(sessionId, secretKey)) {
                        McpHookServerUtil.sendJson(ex, 200, McpHookServerUtil.mcpError(id, -32600,
                                "Authentication failed: no session matches that "
                                + McpToolPropertyEnum.SESSION_ID.key() + "/"
                                + McpToolPropertyEnum.SECRET_KEY.key()
                                + " pair. Re-read your session identity block and copy both values exactly, character for character."));
                        return;
                    }

                    AbstractAiSession session = SessionRegistry.get(sessionId);

                    if (session == null) {
                        McpHookServerUtil.sendJson(ex, 200,
                                McpHookServerUtil.mcpError(id, -32600, "Unknown session"));
                        return;
                    }

                    String requestedName = McpHookServerUtil.str(params, McpProtocolKeyEnum.NAME.key());
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
        JsonObject params = McpHookServerUtil.obj(req, McpProtocolKeyEnum.PARAMS.key());
        String toolName = McpHookServerUtil.str(params, McpProtocolKeyEnum.NAME.key());
        JsonObject argsObj = McpHookServerUtil.obj(params, McpProtocolKeyEnum.ARGUMENTS.key());

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
        try {
            // McpToolInvoker logs the call — see the note there on why it moved.
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
