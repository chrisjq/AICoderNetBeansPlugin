package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.McpSteeringPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.McpSteeringRefusalEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpClientHandler;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpSessionUpdateEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;

/**
 * Maps inbound ACP traffic to plugin events. All methods are called on AcpConnection's dispatcher thread
 * pool, never on the reader thread.
 *
 * <p>
 * {@code session/request_permission} is routed through the existing {@link PermissionEvent} + diff-panel
 * mechanism. We reply with the chosen optionId once the user accepts or rejects in the panel.
 */
class OpenCodeAcpClientHandler implements AcpClientHandler {

    private static final Logger LOG = Logger.getLogger(OpenCodeAcpClientHandler.class.getName());
    private static final Set<String> OUR_MCP_TOOL_NAMES = Set.of(McpToolEnum.allMcpNames().split(","));

    /**
     * ACP tool kinds that change something. Everything OpenCode's own edit/write/patch tools ask about
     * arrives as {@code edit}; the others are the ACP spec's remaining mutating kinds, and
     * {@code write}/{@code patch} are the legacy spellings {@link #mapToolKind} already accepts.
     */
    private static final Set<String> MUTATION_KINDS = Set.of("edit", "write", "patch", "delete", "move");

    /**
     * ACP tool kinds that only look at something, so a request that names a path under one of them is a
     * request to ACCESS that path, not to write it. {@code other} is in this set on purpose: OpenCode's
     * {@code external_directory} permission — the one behind "the AI wants to read a file outside the
     * project" — is sent as {@code kind:"other"}, not {@code "read"} (verified against the OpenCode ACP
     * agent, whose kind mapping falls through to {@code "other"} for any permission name it does not list). A
     * request whose kind is something else entirely, or absent, keeps the pre-existing behaviour rather than
     * being guessed at.
     */
    private static final Set<String> ACCESS_KINDS = Set.of("read", "search", "fetch", "think", "other");

    static String extractFirstLocationPath(JsonObject update) {
        if (!update.has(AcpJsonKeyEnum.LOCATIONS.key()) || !update.get(AcpJsonKeyEnum.LOCATIONS.key()).isJsonArray()) {
            return null;
        }
        JsonArray locations = update.getAsJsonArray(AcpJsonKeyEnum.LOCATIONS.key());
        if (locations.size() == 0 || !locations.get(0).isJsonObject()) {
            return null;
        }
        JsonObject loc = locations.get(0).getAsJsonObject();
        return loc.has(AcpJsonKeyEnum.PATH.key()) ? loc.get(AcpJsonKeyEnum.PATH.key()).getAsString() : null;
    }

    static ToolUseEvent.Kind mapToolKind(String kind) {
        if ("write".equals(kind)) {
            return ToolUseEvent.Kind.WRITE;
        }
        if ("edit".equals(kind) || "patch".equals(kind)) {
            return ToolUseEvent.Kind.EDIT;
        }
        return ToolUseEvent.Kind.OTHER;
    }

    /**
     * Extracts the target file path from a {@code session/request_permission} {@code toolCall} object.
     * Priority: {@code content[0].path} → {@code locations[0].path} → {@code rawInput.filepath}.
     */
    static String extractPermissionFilePath(JsonObject toolCall) {
        if (toolCall == null) {
            return null;
        }
        // 1. content[0].path
        if (toolCall.has(AcpJsonKeyEnum.CONTENT.key()) && toolCall.get(AcpJsonKeyEnum.CONTENT.key()).isJsonArray()) {
            JsonArray content = toolCall.getAsJsonArray(AcpJsonKeyEnum.CONTENT.key());
            if (content.size() > 0 && content.get(0).isJsonObject()) {
                JsonObject c0 = content.get(0).getAsJsonObject();
                if (c0.has(AcpJsonKeyEnum.PATH.key()) && !c0.get(AcpJsonKeyEnum.PATH.key()).isJsonNull()) {
                    return c0.get(AcpJsonKeyEnum.PATH.key()).getAsString();
                }
            }
        }
        // 2. locations[0].path
        String locPath = extractFirstLocationPath(toolCall);
        if (locPath != null) {
            return locPath;
        }
        // 3. rawInput.filepath
        if (toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key()) && toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()) {
            JsonObject rawInput = toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key());
            if (rawInput.has(AcpJsonKeyEnum.FILEPATH.key()) && !rawInput.get(AcpJsonKeyEnum.FILEPATH.key()).isJsonNull()) {
                return rawInput.get(AcpJsonKeyEnum.FILEPATH.key()).getAsString();
            }
        }
        return null;
    }

    /**
     * Extracts {@code rawInput.command} from a {@code toolCall} — the shell command text for an
     * {@code execute}-kind permission request (live-probed shape: {@code
     * rawInput:{"command":"echo hi"}}, empty {@code locations}, no {@code content}).
     */
    static String extractRawInputCommand(JsonObject toolCall) {
        if (toolCall == null || !toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key()) || !toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()) {
            return null;
        }
        JsonObject rawInput = toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key());
        return rawInput.has(AcpJsonKeyEnum.COMMAND.key()) && !rawInput.get(AcpJsonKeyEnum.COMMAND.key()).isJsonNull()
                ? rawInput.get(AcpJsonKeyEnum.COMMAND.key()).getAsString() : null;
    }

    /**
     * Extracts {@code content[0].newText} when {@code content[0].type == "diff"} — the full proposed file
     * content ACP sends for an edit permission request. Returns null for any other shape (non-diff content,
     * missing content, etc.).
     */
    static String extractDiffNewText(JsonObject toolCall) {
        if (toolCall == null || !toolCall.has(AcpJsonKeyEnum.CONTENT.key()) || !toolCall.get(AcpJsonKeyEnum.CONTENT.key()).isJsonArray()) {
            return null;
        }
        JsonArray content = toolCall.getAsJsonArray(AcpJsonKeyEnum.CONTENT.key());
        if (content.size() == 0 || !content.get(0).isJsonObject()) {
            return null;
        }
        JsonObject c0 = content.get(0).getAsJsonObject();
        if (!"diff".equals(c0.has(AcpJsonKeyEnum.TYPE.key()) ? c0.get(AcpJsonKeyEnum.TYPE.key()).getAsString() : null)) {
            return null;
        }
        return c0.has(AcpJsonKeyEnum.NEW_TEXT.key()) && !c0.get(AcpJsonKeyEnum.NEW_TEXT.key()).isJsonNull() ? c0.get(AcpJsonKeyEnum.NEW_TEXT.key()).getAsString() : null;
    }

    /**
     * EVERY path a {@code session/request_permission} names, in order and de-duplicated —
     * {@code content[*].path}, {@code locations[*].path}, and the string fields OpenCode puts in
     * {@code rawInput} for a directory-scoped request ({@code filepath}, {@code filePath}, {@code parentDir},
     * {@code path}, {@code directories[]}). {@link #extractPermissionFilePath} deliberately returns only the
     * first, which is right for labelling but not for deciding that a request is harmless: an
     * {@code external_directory} ask can list the spool file AND another directory, and exempting it because
     * the first one is ours would let the second through unasked.
     */
    static List<String> extractAllPermissionPaths(JsonObject toolCall) {
        Set<String> paths = new LinkedHashSet<>();
        if (toolCall == null) {
            return List.of();
        }
        collectPaths(toolCall, AcpJsonKeyEnum.CONTENT.key(), paths);
        collectPaths(toolCall, AcpJsonKeyEnum.LOCATIONS.key(), paths);
        if (toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key()) && toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()) {
            JsonObject rawInput = toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key());
            for (String key : new String[]{"filepath", "filePath", "parentDir", "path"}) {
                addIfString(rawInput, key, paths);
            }
            if (rawInput.has("directories") && rawInput.get("directories").isJsonArray()) {
                for (var dir : rawInput.getAsJsonArray("directories")) {
                    if (dir.isJsonPrimitive() && dir.getAsJsonPrimitive().isString() && !dir.getAsString().isBlank()) {
                        paths.add(dir.getAsString());
                    }
                }
            }
            // A multi-file patch names each file (and any rename target) here rather than in a single filepath.
            if (rawInput.has("files") && rawInput.get("files").isJsonArray()) {
                for (var file : rawInput.getAsJsonArray("files")) {
                    if (file.isJsonObject()) {
                        addIfString(file.getAsJsonObject(), "filePath", paths);
                        addIfString(file.getAsJsonObject(), "movePath", paths);
                    }
                }
            }
        }
        return List.copyOf(paths);
    }

    private static void collectPaths(JsonObject toolCall, String arrayKey, Set<String> into) {
        if (!toolCall.has(arrayKey) || !toolCall.get(arrayKey).isJsonArray()) {
            return;
        }
        for (var element : toolCall.getAsJsonArray(arrayKey)) {
            if (element.isJsonObject()) {
                addIfString(element.getAsJsonObject(), AcpJsonKeyEnum.PATH.key(), into);
            }
        }
    }

    private static void addIfString(JsonObject obj, String key, Set<String> into) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isString()
                && !obj.get(key).getAsString().isBlank()) {
            into.add(obj.get(key).getAsString());
        }
    }

    /**
     * True only when the request is positively a change: a mutating kind, or a diff proposed in
     * {@code content} or {@code rawInput.diff} whatever the kind claims. Absence of a diff is NOT treated as
     * evidence of a read — that is decided by kind — so an unrecognised kind keeps the existing behaviour
     * instead of being reclassified on a guess.
     */
    static boolean isMutationRequest(String kind, JsonObject toolCall) {
        if (kind != null && MUTATION_KINDS.contains(kind)) {
            return true;
        }
        if (toolCall == null) {
            return false;
        }
        if (toolCall.has(AcpJsonKeyEnum.CONTENT.key()) && toolCall.get(AcpJsonKeyEnum.CONTENT.key()).isJsonArray()) {
            for (var element : toolCall.getAsJsonArray(AcpJsonKeyEnum.CONTENT.key())) {
                if (element.isJsonObject() && element.getAsJsonObject().has(AcpJsonKeyEnum.TYPE.key())
                        && "diff".equals(element.getAsJsonObject().get(AcpJsonKeyEnum.TYPE.key()).getAsString())) {
                    return true;
                }
            }
        }
        return toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key()) && toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()
                && toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key()).has("diff");
    }

    /**
     * Name shown to the user for a request to look at a path: the verb that matches the kind, never "Write". {@code
     * other} — where an {@code external_directory} ask lands — is "Access", because the request says the AI
     * wants to reach outside the project, not which tool it will use once it can.
     */
    static String accessToolName(String kind) {
        if (kind == null) {
            return "Access";
        }
        return switch (kind) {
            case "read" ->
                "Read";
            case "search" ->
                "Search";
            case "fetch" ->
                "Fetch";
            case "think" ->
                "Think";
            default ->
                "Access";
        };
    }

    /**
     * What the confirm prompt says is being approved. A search names its pattern (OpenCode puts it in
     * {@code title}) as well as where it will look; everything else is just the path.
     */
    static String accessDisplayText(String kind, String title, String path) {
        if ("search".equals(kind) && title != null && !title.isBlank() && !title.equals(path)) {
            return title.trim() + " in " + path;
        }
        return path;
    }

    /**
     * A well-formed {@code selected} reply — {@code "once"} to allow, {@code "reject"} to refuse — built
     * without touching {@link #pendingPermission}: a request decided by policy never became pending, and
     * clearing the field here could drop a genuinely open dialog's future. A refusal is the same reply a
     * user's "No" produces, so the agent gets a clean rejection for that one call instead of a cancelled turn
     * or a hang.
     */
    private static JsonObject selectedResult(String optionId) {
        JsonObject outcome = new JsonObject();
        outcome.addProperty(AcpJsonKeyEnum.OUTCOME.key(), "selected");
        outcome.addProperty(AcpJsonKeyEnum.OPTION_ID.key(), optionId);
        JsonObject result = new JsonObject();
        result.add(AcpJsonKeyEnum.OUTCOME.key(), outcome);
        return result;
    }

    /**
     * True when the exemption is wired AND every path the request names is inside this session's own config
     * tree. Fails closed: no predicate, no paths, one outside path, or a predicate that throws all mean
     * "ask".
     */
    private boolean isEntirelyInsideOwnSessionTree(JsonObject toolCall) {
        Predicate<String> check = ownSessionConfigFile;
        if (check == null) {
            return false;
        }
        List<String> paths = extractAllPermissionPaths(toolCall);
        if (paths.isEmpty()) {
            return false;
        }
        try {
            for (String path : paths) {
                if (!check.test(path)) {
                    return false;
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "own-session check failed; asking the user instead", e);
            return false;
        }
        return true;
    }

    /**
     * How many in-flight tool calls' kinds are remembered before the map is dropped and rebuilt. A tool call
     * is forgotten as soon as it completes, so this only bounds a leak from calls that never report a
     * terminal status. A lookup that misses is safe by design — it means "ask", never "allow".
     */
    private static final int MAX_TRACKED_TOOL_CALLS = 256;

    /**
     * How many distinct refusals one turn keeps for {@link #consumeTurnRefusals}. A turn that ends on a
     * refusal has normally made one; the bound only stops a pathological burst from growing the notice
     * without limit.
     */
    private static final int MAX_TURN_REFUSALS = 8;

    private final AiProcessEventListener listener;
    private final Runnable disconnectCallback;
    private final BiConsumer<String, String> toolCallTracker;
    private final Predicate<String> ownSessionConfigFile;
    private final Predicate<String> readPolicy;
    private final Function<String, String> readRefusalReason;
    private final Predicate<String> steeringIsActiveCheck;
    /**
     * The PLUGIN session UUID (the id the MCP server registered), as opposed to OpenCode's own ACP session
     * id. Steering is resolved against {@link SessionRegistry} under this id, never the ACP session id that
     * arrives in a {@code session/request_permission} payload, which the registry does not know. Null when
     * the caller has no plugin session wired (older callers, plain tests) — then steering fails closed to the
     * injected check (if any) or off.
     */
    private final String pluginSessionId;
    private final Map<String, String> toolKindByCallId = new ConcurrentHashMap<>();
    /**
     * Every read the read-scope rule refused during the current turn: the path and its refusal text, verbatim
     * as {@code GetFileContent} would return it, in the order refused and without repeats. Written from the
     * ACP request thread, drained by the process manager when the turn ends, so every access holds this
     * list's own monitor.
     */
    private final List<PolicyRefusalEvent.Refusal> turnRefusals = new ArrayList<>();
    private volatile CompletableFuture<PermissionDecision> pendingPermission = null;

    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback) {
        this(listener, disconnectCallback, null, null, null);
    }

    /**
     * @param toolCallTracker receives {@code (toolCallId, status)} for every {@code tool_call} and
     * {@code tool_call_update} session/update so the process manager can track in-flight tool calls (F5)
     * without reaching into the handler's internals. May be null when the caller is not a manager (e.g. tests
     * wiring a handler to a bare connection).
     */
    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback,
            BiConsumer<String, String> toolCallTracker) {
        this(listener, disconnectCallback, toolCallTracker, null, null);
    }

    /**
     * @param ownSessionConfigFile true for a path inside this plugin session's own
     * {@code ~/.ai-coder/{type}/{sessionId}/} tree — see {@link #ownSessionConfigFileCheck}. A permission
     * request whose every path passes is answered "allow once" without asking the user, exactly as the
     * plugin's own file tools and the Claude/Pi hook treat that tree. Null means "nothing is exempt": every
     * path-bearing request is put to the user, as before this parameter existed.
     */
    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback,
            BiConsumer<String, String> toolCallTracker, Predicate<String> ownSessionConfigFile) {
        this(listener, disconnectCallback, toolCallTracker, ownSessionConfigFile, null);
    }

    /**
     * @param ownSessionConfigFile true for a path inside this plugin session's own
     * {@code ~/.ai-coder/{type}/{sessionId}/} tree
     * @param steeringIsActiveCheck optional check for whether MCP steering is active for a session (by
     * sessionId). If null, steering is determined via {@link SessionRegistry} at runtime, under the plugin
     * session id passed as {@code pluginSessionId}.
     */
    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback,
            BiConsumer<String, String> toolCallTracker, Predicate<String> ownSessionConfigFile,
            Predicate<String> steeringIsActiveCheck) {
        this(listener, disconnectCallback, toolCallTracker, ownSessionConfigFile, steeringIsActiveCheck, null);
    }

    /**
     * @param ownSessionConfigFile true for a path inside this plugin session's own
     * {@code ~/.ai-coder/{type}/{sessionId}/} tree
     * @param steeringIsActiveCheck optional check for whether MCP steering is active for a session (by
     * sessionId). If null, steering is determined via {@link SessionRegistry} at runtime, under
     * {@code pluginSessionId}.
     * @param pluginSessionId the PLUGIN session UUID the plugin session is registered under in
     * {@link SessionRegistry} — see the field. Null for callers with no plugin session (older callers, plain
     * tests); steering then resolves through {@code steeringIsActiveCheck} if given, else off.
     */
    OpenCodeAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback,
            BiConsumer<String, String> toolCallTracker, Predicate<String> ownSessionConfigFile,
            Predicate<String> steeringIsActiveCheck, String pluginSessionId) {
        this.listener = listener;
        this.disconnectCallback = disconnectCallback;
        this.toolCallTracker = toolCallTracker;
        this.ownSessionConfigFile = ownSessionConfigFile;
        this.steeringIsActiveCheck = steeringIsActiveCheck;
        this.pluginSessionId = pluginSessionId;
        // The read policy travels inside the same object the process manager already passes for the own-tree check, so
        // wiring it needs no change there. A plain predicate (a test, or an older caller) carries no read policy, and
        // then a read is put to the user exactly as before.
        this.readPolicy = ownSessionConfigFile instanceof SessionFileScope scope ? scope::isReadAllowed : null;
        this.readRefusalReason = ownSessionConfigFile instanceof SessionFileScope scope ? scope::refusalReason : null;
    }

    /**
     * One plugin session's file policy as the ACP handler needs it: the own-tree exemption (as a
     * {@link Predicate}, so it stays drop-in for the existing constructor parameter) plus the read-access
     * decision.
     */
    static final class SessionFileScope implements Predicate<String> {

        private final Predicate<String> ownSessionConfigFile;
        private final Predicate<String> readAllowed;
        private final Function<String, String> refusalReason;

        SessionFileScope(Predicate<String> ownSessionConfigFile, Predicate<String> readAllowed) {
            this(ownSessionConfigFile, readAllowed, null);
        }

        SessionFileScope(Predicate<String> ownSessionConfigFile, Predicate<String> readAllowed,
                Function<String, String> refusalReason) {
            this.ownSessionConfigFile = ownSessionConfigFile;
            this.readAllowed = readAllowed;
            this.refusalReason = refusalReason;
        }

        /**
         * Why {@code GetFileContent} would refuse {@code path}, in its own words, or null when no source is
         * wired. For the log only — nothing that reaches the agent or the user.
         */
        String refusalReason(String path) {
            return refusalReason == null ? null : refusalReason.apply(path);
        }

        /**
         * Is {@code path} inside this session's own {@code ~/.ai-coder/{type}/{sessionId}/} tree.
         */
        @Override
        public boolean test(String path) {
            return ownSessionConfigFile.test(path);
        }

        /**
         * Would {@code GetFileContent} be allowed to read {@code path} for this session.
         */
        boolean isReadAllowed(String path) {
            return readAllowed.test(path);
        }
    }

    /**
     * The file policy for one plugin session, resolved against the live shared MCP server on every call. Both
     * halves defer to {@link McpHookServer} rather than restating a rule:
     * <ul>
     * <li>own tree — {@link McpHookServer#isOwnSessionConfigFile}, the helper {@code WriteFile},
     * {@code ApplyEdit}, {@code SaveFile} and the Claude/Pi hook use. False when the server is down or the
     * session unregistered: an exemption that cannot be verified is never granted.</li>
     * <li>read — {@link McpHookServer#isFileAccessible(McpHookServer, String, String)}, the static
     * null-tolerant form of the instance method {@code GetFileContentTool.handle} calls to decide whether to
     * serve a file. Calling that same method (project scope with the restrict-to-project setting, OR the
     * session's own tree, minus the vetoed conversation-history tree) is what makes a native read and a
     * {@code GetFileContent} read obey one policy by construction. It is false for a null server or session,
     * so it fails toward denial rather than approval.</li>
     * </ul>
     * Needs the PLUGIN session id (the one the MCP server registered), not OpenCode's own ACP session id,
     * which is why the process manager that knows it supplies this.
     */
    static SessionFileScope sessionFileScope(Supplier<McpHookServer> server, String pluginSessionId) {
        return new SessionFileScope(
                path -> {
                    McpHookServer s = server.get();
                    return s != null && s.isOwnSessionConfigFile(pluginSessionId, path);
                },
                path -> McpHookServer.isFileAccessible(server.get(), pluginSessionId, path),
                // The refusal text GetFileContentTool.handle returns for the same path, from the same method.
                path -> McpHookServer.fileAccessDeniedMessage(server.get(), pluginSessionId, path));
    }

    /**
     * {@link #sessionFileScope} against the shared server, typed as the {@link Predicate} the process manager
     * passes to the constructor. The returned object is a {@link SessionFileScope}, which is how the read
     * policy gets in.
     */
    static Predicate<String> ownSessionConfigFileCheck(String pluginSessionId) {
        return sessionFileScope(McpServerRegistry::getServer, pluginSessionId);
    }

    @Override
    public void onSessionUpdate(String sessionId, JsonObject update) {
        String raw = update.has(AcpJsonKeyEnum.SESSION_UPDATE.key()) ? update.get(AcpJsonKeyEnum.SESSION_UPDATE.key()).getAsString() : null;
        AcpSessionUpdateEnum type = AcpSessionUpdateEnum.fromWire(raw);
        if (type == null) {
            return; // Unknown sessionUpdate value — ignore silently, never throw
        }
        switch (type) {
            case AGENT_MESSAGE_CHUNK:
                handleAgentMessageChunk(update);
                break;
            case AGENT_THOUGHT_CHUNK:
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.THINKING, ""));
                break;
            case TOOL_CALL:
            case TOOL_CALL_UPDATE:
                // locations and rawInput may be empty on the initial tool_call
                handleToolEvent(update);
                break;
            case USAGE_UPDATE:
                handleUsageUpdate(update);
                break;
            default:
                break;
        }
    }

    private void handleUsageUpdate(JsonObject update) {
        int used = update.has(AcpJsonKeyEnum.USED.key()) ? update.get(AcpJsonKeyEnum.USED.key()).getAsInt() : 0;
        int size = update.has(AcpJsonKeyEnum.SIZE.key()) ? update.get(AcpJsonKeyEnum.SIZE.key()).getAsInt() : 0;
        listener.onAiProcessEvent(new OpenCodeUsageEvent(used, size));
    }

    private void handleAgentMessageChunk(JsonObject update) {
        String messageId = update.has(AcpJsonKeyEnum.MESSAGE_ID.key()) ? update.get(AcpJsonKeyEnum.MESSAGE_ID.key()).getAsString() : null;
        String text = "";
        if (update.has(AcpJsonKeyEnum.CONTENT.key()) && update.get(AcpJsonKeyEnum.CONTENT.key()).isJsonObject()) {
            JsonObject content = update.getAsJsonObject(AcpJsonKeyEnum.CONTENT.key());
            if (content.has(AcpJsonKeyEnum.TEXT.key())) {
                text = content.get(AcpJsonKeyEnum.TEXT.key()).getAsString();
            }
        }
        listener.onAiProcessEvent(new TextDeltaEvent(text, messageId));
    }

    private void handleToolEvent(JsonObject update) {
        String toolName = update.has(AcpJsonKeyEnum.TITLE.key()) ? update.get(AcpJsonKeyEnum.TITLE.key()).getAsString() : "";
        String kind = update.has(AcpJsonKeyEnum.KIND.key()) ? update.get(AcpJsonKeyEnum.KIND.key()).getAsString() : "";
        String filePath = extractFirstLocationPath(update);
        listener.onAiProcessEvent(new ToolUseEvent(toolName, filePath, null, null, mapToolKind(kind)));
        String toolCallId = stringOrNull(update, AcpJsonKeyEnum.TOOL_CALL_ID.key());
        String status = stringOrNull(update, AcpJsonKeyEnum.STATUS.key());
        rememberToolKind(toolCallId, kind, status);
        // F5: forward the lifecycle signal so the manager can hold a Mail interrupt while a call
        // is in flight. The status is null only when the field is absent, in which case the
        // manager treats the update as not changing the count.
        if (toolCallTracker != null && toolCallId != null) {
            toolCallTracker.accept(toolCallId, status);
        }
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }

    /**
     * Remembers which kind of tool a call id belongs to, until it reports a terminal status. An {@code
     * external_directory} permission arrives as {@code kind:"other"} with no hint of which tool wanted the
     * directory; the only thing linking it to a read is that its {@code toolCallId} is the id of an earlier
     * {@code tool_call} whose kind was {@code read}. An update without a kind leaves the remembered one
     * alone.
     */
    private void rememberToolKind(String toolCallId, String kind, String status) {
        if (toolCallId == null) {
            return;
        }
        if ("completed".equals(status) || "failed".equals(status)) {
            toolKindByCallId.remove(toolCallId);
            return;
        }
        if (kind == null || kind.isBlank()) {
            return;
        }
        if (toolKindByCallId.size() >= MAX_TRACKED_TOOL_CALLS) {
            toolKindByCallId.clear();
        }
        toolKindByCallId.put(toolCallId, kind);
    }

    /**
     * The kind of the tool that raised this permission request, taken from the {@code tool_call} that
     * announced it, or null when that call was never seen (or has completed). Null is never treated as a
     * read.
     */
    private String originatingToolKind(JsonObject toolCall) {
        String toolCallId = toolCall == null ? null : stringOrNull(toolCall, AcpJsonKeyEnum.TOOL_CALL_ID.key());
        return toolCallId == null ? null : toolKindByCallId.get(toolCallId);
    }

    /**
     * True only when the request is CONFIDENTLY a read: not a mutation, and either its own kind is {@code read}/{@code
     * search}, or it is an {@code other}-kind request (the {@code external_directory} ask) whose originating
     * tool call was a {@code read}/{@code search}. An {@code other} request that cannot be traced to a read —
     * the tool call was never seen, or it was an edit or a command — is not a read, because that ask precedes
     * every kind of access to the directory and treating it as one would silently decide an edit or a shell
     * command on a read's rules.
     */
    private boolean isConfidentRead(String kind, JsonObject toolCall) {
        if (isMutationRequest(kind, toolCall)) {
            return false;
        }
        String effective = "other".equals(kind) ? originatingToolKind(toolCall) : kind;
        return "read".equals(effective) || "search".equals(effective);
    }

    /**
     * The outcome of the read-scope rule: allowed, or refused with the first path it refused (kept so the log
     * can say why, in GetFileContent's words).
     */
    private record ReadDecision(boolean allowed, String refusedPath) {

    }

    /**
     * The read-scope decision for a request that is confidently a read, or null when it could not be made (no
     * read policy wired, no paths, or the policy failed), in which case the caller falls back to asking.
     * Every path the request names must pass; one that GetFileContent would refuse refuses the whole request.
     */
    private ReadDecision decideRead(JsonObject toolCall) {
        Predicate<String> policy = readPolicy;
        if (policy == null) {
            return null;
        }
        List<String> paths = extractAllPermissionPaths(toolCall);
        if (paths.isEmpty()) {
            return null;
        }
        try {
            for (String path : paths) {
                if (!policy.test(path)) {
                    return new ReadDecision(false, path);
                }
            }
            return new ReadDecision(true, null);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "read-scope check failed; asking the user instead", e);
            return null;
        }
    }

    /**
     * The refusal {@code GetFileContent} returns for {@code refusedPath}, from the same
     * {@link McpHookServer#fileAccessDeniedMessage}, or null when there is no source, it yields nothing, or
     * it fails. Never allowed to throw: it runs on the refusal path, and neither a log line nor a notice may
     * be able to break the reply to OpenCode.
     */
    private String refusalMessage(String refusedPath) {
        Function<String, String> reason = readRefusalReason;
        if (reason == null || refusedPath == null) {
            return null;
        }
        try {
            String text = reason.apply(refusedPath);
            return text == null || text.isBlank() ? null : text;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Remembers a refusal, path and text, for the end of the turn, when the process manager decides whether
     * the agent needs telling. A refusal whose text cannot be produced is not remembered: the notice quotes
     * the shared text and has nothing else to say.
     */
    private void recordRefusal(String refusedPath, String refusalMessage) {
        if (refusedPath == null || refusalMessage == null) {
            return;
        }
        PolicyRefusalEvent.Refusal refusal = new PolicyRefusalEvent.Refusal(refusedPath, refusalMessage);
        synchronized (turnRefusals) {
            if (turnRefusals.size() < MAX_TURN_REFUSALS && !turnRefusals.contains(refusal)) {
                turnRefusals.add(refusal);
            }
        }
    }

    /**
     * Takes, and forgets, every read refused since the last call: each refused path with its refusal text,
     * exactly as {@code GetFileContent} would have returned it, in the order refused.
     *
     * <p>
     * Called once at the end of every turn, so a refusal can never be reported on a later turn. Costs one
     * monitor acquisition and an emptiness check when nothing was refused.
     *
     * @return an empty list when nothing was refused
     */
    List<PolicyRefusalEvent.Refusal> consumeTurnRefusals() {
        synchronized (turnRefusals) {
            if (turnRefusals.isEmpty()) {
                return List.of();
            }
            List<PolicyRefusalEvent.Refusal> taken = List.copyOf(turnRefusals);
            turnRefusals.clear();
            return taken;
        }
    }

    /**
     * Forgets the current turn's refusals without reporting them; a new turn starts with none.
     */
    void clearTurnRefusals() {
        synchronized (turnRefusals) {
            turnRefusals.clear();
        }
    }

    /**
     * Routes {@code session/request_permission} by {@code toolCall.kind}, not by whether a file path happened
     * to resolve (live probe, "Write: null" defect):
     * <ul>
     * <li>{@code execute} — a shell command. There is no diff to render, so this raises {@link ConfirmEvent}
     * (yes/no), not {@link PermissionEvent}.</li>
     * <li>a request that is CONFIDENTLY a read ({@link #isConfidentRead}) — a policy decision, made with no
     * event and nothing shown: allowed if {@code GetFileContent} would be allowed to read every path it
     * names, refused (a clean {@code reject} reply) if not. Both come from
     * {@link McpHookServer#isFileAccessible}, the rule {@code GetFileContentTool} itself applies, so the two
     * cannot disagree. Undecidable — no policy wired, no paths, or the policy failing — falls through to
     * asking.</li>
     * <li>every path named is inside this session's own {@code ~/.ai-coder/{type}/{sessionId}/} tree —
     * answered "allow once" with no event and no prompt, the same exemption
     * {@code WriteFile}/{@code ApplyEdit}/{@code SaveFile} and the Claude/Pi hook give that tree. This is
     * what stops the plugin's own spooled tool results asking to be approved.</li>
     * <li>a resolvable path on a request that proposes a change ({@link #isMutationRequest}) — unchanged
     * {@link PermissionEvent} "Write" path, the only one that renders a diff.</li>
     * <li>a resolvable path on a {@code read}/{@code search}/{@code fetch}/{@code think}/{@code other}
     * request — a request to ACCESS that path, raised as a yes/no {@link ConfirmEvent} named for what it is
     * ("Read", "Search", "Access"). It carries no diff, so it must not go through the "Write" path: that
     * would compute a diff against an empty proposal and show a file about to be blanked when nothing of the
     * kind is happening.</li>
     * <li>a resolvable path on an unrecognised or absent kind — unchanged {@link PermissionEvent} "Write"
     * path, rather than a guess.</li>
     * <li>no path at all — the subject could not be identified. Falling back to the old "Write: null" text
     * let auto-accept approve an unseen action of unknown kind. Raise {@link ConfirmEvent} instead, showing
     * the kind and title so there is at least something real to see, and never treat it as silently
     * approvable.</li>
     * </ul>
     */
    @Override
    public CompletableFuture<JsonObject> onRequestPermission(JsonObject params) {
        JsonObject toolCall = params.has(AcpJsonKeyEnum.TOOL_CALL.key()) && params.get(AcpJsonKeyEnum.TOOL_CALL.key()).isJsonObject()
                ? params.getAsJsonObject(AcpJsonKeyEnum.TOOL_CALL.key()) : null;
        String kind = toolCall != null && toolCall.has(AcpJsonKeyEnum.KIND.key()) && !toolCall.get(AcpJsonKeyEnum.KIND.key()).isJsonNull()
                ? toolCall.get(AcpJsonKeyEnum.KIND.key()).getAsString() : null;
        String title = toolCall != null && toolCall.has(AcpJsonKeyEnum.TITLE.key()) && !toolCall.get(AcpJsonKeyEnum.TITLE.key()).isJsonNull()
                ? toolCall.get(AcpJsonKeyEnum.TITLE.key()).getAsString() : null;
        String filePath = extractPermissionFilePath(toolCall);

        if ("execute".equals(kind)) {
            String command = extractRawInputCommand(toolCall);
            String displayText = command != null ? command : title != null ? title : "(unknown command)";
            if (steeringIsActive() && !isOurMcpServerTool(toolCall)) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "OpenCode permission request: kind=execute -> auto-denied by MCP steering, "
                            + "command={0}", displayText);
                }
                listener.onAiProcessEvent(new SystemNotificationEvent("MCP Steering: " + displayText));
                String steeringText = McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.SHELL);
                listener.onAiProcessEvent(new McpSteeringRefusalEvent(List.of(
                        new McpSteeringRefusalEvent.Refusal("Execute", steeringText))));
                return CompletableFuture.completedFuture(selectedResult("reject"));
            }
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode permission request: kind=execute -> ConfirmEvent, command={0}",
                        displayText);
            }
            // Shell execution always needs a human, matching the Copilot rule:
            // the command is identified, but running arbitrary commands is not
            // something auto-accept should answer on the user's behalf. When
            // extractRawInputCommand finds nothing, displayText degrades to
            // "(unknown command)" — approving that unseen is the failure this
            // guards against.
            return raiseConfirmAndReply("Execute", displayText, null, null, true);
        }

        // A confident read is a policy decision, not a conversation: allowed or refused by the very rule GetFileContent
        // applies, with no event and nothing in the transcript. Anything not confidently a read, or that cannot be
        // decided, carries on to the branches below and is put to the user as before.
        if (filePath != null && isConfidentRead(kind, toolCall)) {
            ReadDecision decision = decideRead(toolCall);
            if (decision != null) {
                // Built once per refusal, never for an allowed read, and shared by the log line and the end-of-turn
                // notice: a refusal that ends the agent's turn is reported to it from this same text.
                String refusal = decision.allowed() ? null : refusalMessage(decision.refusedPath());
                recordRefusal(decision.refusedPath(), refusal);
                // Gated like every other diagnostic in this handler and on the MCP hook's own response logs: a routine
                // policy decision is not worth a line per read unless the user asked for the JSON trail.
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "OpenCode read request: path={0} toolCall.kind={1} originatingKind={2} -> {3} by "
                            + "the read-scope rule, no prompt{4}", new Object[]{filePath, kind, originatingToolKind(toolCall),
                                decision.allowed() ? "allowed" : "denied",
                                refusal == null ? "" : ". Reason: " + refusal});
                }
                return CompletableFuture.completedFuture(selectedResult(decision.allowed() ? "once" : "reject"));
            }
        }

        if (filePath != null && isEntirelyInsideOwnSessionTree(toolCall)) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode permission request: path={0} toolCall.kind={1} title={2} is inside this "
                        + "session's own config tree -> allowed once, no prompt", new Object[]{filePath, kind, title});
            }
            return CompletableFuture.completedFuture(selectedResult("once"));
        }

        if (filePath != null && !isMutationRequest(kind, toolCall) && kind != null && ACCESS_KINDS.contains(kind)) {
            if (steeringIsActive() && !isOurMcpServerTool(toolCall)) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "OpenCode permission request: path={0} toolCall.kind={1} -> auto-denied by MCP steering",
                            new Object[]{filePath, kind});
                }
                listener.onAiProcessEvent(new SystemNotificationEvent("MCP Steering: " + accessDisplayText(kind, title, filePath)));
                String steeringText = McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.READ);
                listener.onAiProcessEvent(new McpSteeringRefusalEvent(List.of(
                        new McpSteeringRefusalEvent.Refusal(accessToolName(kind), steeringText))));
                return CompletableFuture.completedFuture(selectedResult("reject"));
            }
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode permission request: path={0} toolCall.kind={1} title={2} rawInput={3} "
                        + "-> ConfirmEvent({4}), not a Write", new Object[]{filePath, kind, title,
                            McpHookServerUtil.redactAllSecrets(String.valueOf(
                                    toolCall != null ? toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()) : null)),
                            accessToolName(kind)});
            }
            // A request to look at a path, not to change it. Yes/no, named for the kind, and auto-acceptable exactly as
            // it was when it was mislabelled a Write — the label and the diff are what change, not who may approve it.
            return raiseConfirmAndReply(accessToolName(kind), accessDisplayText(kind, title, filePath),
                    filePath, null, false);
        }

        if (filePath != null) {
            if (steeringIsActive() && !isOurMcpServerTool(toolCall)) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO, "OpenCode permission request: path={0} toolCall.kind={1} -> auto-denied by MCP steering (Write)",
                            new Object[]{filePath, kind});
                }
                listener.onAiProcessEvent(new SystemNotificationEvent("MCP Steering: Write " + filePath));
                String steeringText = McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.WRITE);
                listener.onAiProcessEvent(new McpSteeringRefusalEvent(List.of(
                        new McpSteeringRefusalEvent.Refusal("Write", steeringText))));
                return CompletableFuture.completedFuture(selectedResult("reject"));
            }
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode permission request: path={0} toolCall.kind={1} title={2} -> PermissionEvent",
                        new Object[]{filePath, kind, title});
            }
            // ACP sends full file contents in newText — route through "Write", not "Edit".
            // PermissionDiffPolicy.decide("Edit",...) requires an exact oldString substring
            // match, which breaks for full-file diffs and fails outright when oldText is
            // null (new files).
            String newText = extractDiffNewText(toolCall);
            CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
            pendingPermission = decisionFuture;
            listener.onAiProcessEvent(new PermissionEvent("Write", filePath, null, null, newText, decisionFuture));
            return decisionFuture.handle(this::mapDecisionToAcpResult);
        }

        // Neither an execute nor a resolvable path — none of the three known shapes
        // matched. This used to fall straight into the "Write" PermissionEvent with a
        // null path, which read as "Write: null" and, with auto-accept on, approved an
        // unidentified action sight unseen. Show what we do know instead of guessing,
        // and never let this branch look auto-acceptable.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.WARNING,
                    "OpenCode permission request with no extractable file path and kind != execute "
                    + "-> ConfirmEvent(kind={0}, title={1}) instead of \"Write: null\". Raw params: {2}",
                    new Object[]{kind, title, McpHookServerUtil.redactAllSecrets(String.valueOf(params))});
        }
        String toolName = kind != null && !kind.isBlank() ? kind : "Unknown";
        String displayText = title != null && !title.isBlank()
                ? title : "(unidentified OpenCode action, kind=" + toolName + ")";
        if (steeringIsActive() && !isOurMcpServerTool(toolCall)) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode permission request: unidentified (kind={0}) -> auto-denied by MCP steering", toolName);
            }
            listener.onAiProcessEvent(new SystemNotificationEvent("MCP Steering: " + displayText));
            String steeringText = McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.UNKNOWN);
            listener.onAiProcessEvent(new McpSteeringRefusalEvent(List.of(
                    new McpSteeringRefusalEvent.Refusal(toolName, steeringText))));
            return CompletableFuture.completedFuture(selectedResult("reject"));
        }
        // Never auto-accept this one. It is the fallback for a request whose kind
        // and subject could not be worked out, so auto-accept would approve an
        // action of unknown type against an unknown target and log it as
        // "Unknown — auto-accepted" — the whole point of the gate lost exactly
        // where the request is least understood. This is the "Write: null" case
        // under a better label.
        return raiseConfirmAndReply(toolName, displayText, null, null, true);
    }

    /**
     * Raises a {@link ConfirmEvent} (yes/no, no diff to render) and maps the eventual
     * {@link PermissionDecision} to the ACP wire reply via {@link #mapDecisionToAcpResult}. Mirrors
     * {@code CodexAppServerHandler.raiseConfirmAndReply}.
     */
    private CompletableFuture<JsonObject> raiseConfirmAndReply(
            String toolName, String displayText, String filePath, String targetPath,
            boolean requireExplicitApproval) {
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        listener.onAiProcessEvent(new ConfirmEvent(toolName, displayText, filePath, targetPath,
                decisionFuture, requireExplicitApproval));
        return decisionFuture.handle(this::mapDecisionToAcpResult);
    }

    /**
     * Maps a completed {@link PermissionDecision} future to the ACP {@code
     * session/request_permission} wire response. Shared by every {@code onRequestPermission} branch
     * (execute/edit/unidentified) so the decision→outcome mapping lives in one place.
     *
     * <p>
     * NOTE: We always reply "once" for an allow, never "always". The diff panel's auto-accept path calls
     * {@code PermissionDecision.allowed()} directly, so we cannot distinguish it from an explicit user click.
     * Replying "always" would configure OpenCode to skip future permission requests permanently — a
     * side-effect the user did not request from the auto-accept toggle.
     *
     * <p>
     * NOTE: Unlike the Claude/MCP path (which applies the edit itself and then sends "deny" to prevent a
     * double-write), here answering "once" lets OpenCode perform the write/command itself. We MUST NOT apply
     * the edit ourselves — doing so would double-apply it.
     */
    private JsonObject mapDecisionToAcpResult(PermissionDecision decision, Throwable ex) {
        pendingPermission = null;
        JsonObject result = new JsonObject();
        if (ex != null || decision == null) {
            // Turn was cancelled while the permission dialog was open
            JsonObject outcome = new JsonObject();
            outcome.addProperty(AcpJsonKeyEnum.OUTCOME.key(), "cancelled");
            result.add(AcpJsonKeyEnum.OUTCOME.key(), outcome);
        } else {
            JsonObject outcome = new JsonObject();
            outcome.addProperty(AcpJsonKeyEnum.OUTCOME.key(), "selected");
            outcome.addProperty(AcpJsonKeyEnum.OPTION_ID.key(), decision.allow() ? "once" : "reject");
            result.add(AcpJsonKeyEnum.OUTCOME.key(), outcome);
        }
        return result;
    }

    /**
     * Cancels any in-flight permission dialog by completing its future exceptionally. Called by the process
     * manager on turn cancel and stop. The {@code handle} in {@link #onRequestPermission} maps this to
     * {@code {"outcome":{"outcome":"cancelled"}}} back to OpenCode.
     */
    void cancelPendingPermissions() {
        CompletableFuture<PermissionDecision> pf = pendingPermission;
        if (pf != null) {
            pendingPermission = null;
            pf.completeExceptionally(new CancellationException("turn cancelled"));
        }
    }

    /**
     * Checks if MCP steering is enabled for the plugin session this handler serves. Steering is resolved
     * under {@link #pluginSessionId} — the PLUGIN session UUID the session is registered under in
     * {@link SessionRegistry} — never under the ACP session id that arrives in a
     * {@code session/request_permission} payload, which the registry does not know (an OpenCode session id
     * looks like {@code ses_...}). When a {@code steeringIsActiveCheck} is injected (tests), it decides; in
     * production the registry lookup decides.
     */
    private boolean steeringIsActive() {
        if (steeringIsActiveCheck != null) {
            return steeringIsActiveCheck.test(pluginSessionId);
        }
        if (pluginSessionId == null) {
            return false;
        }
        AbstractAiSession session = SessionRegistry.get(pluginSessionId);
        if (session == null) {
            return false;
        }
        return session.getSettings().effectiveMcpSteering()
                && session.getType().mcpSteeringSupport().supported();
    }

    /**
     * Checks if the tool call is for one of our own MCP server tools, which should never be steered. Our
     * tools are named as "mcp__aicoder-nb-ki-plugin__<ToolName>". If OpenCode ever asks for permission on our
     * MCP tools, we should not deny them via steering.
     */
    private boolean isOurMcpServerTool(JsonObject toolCall) {
        if (toolCall == null) {
            return false;
        }
        // Check title field which may contain the tool name
        if (toolCall.has(AcpJsonKeyEnum.TITLE.key())) {
            String title = toolCall.get(AcpJsonKeyEnum.TITLE.key()).getAsString();
            if (title != null && OUR_MCP_TOOL_NAMES.contains(title)) {
                return true;
            }
        }
        // Check kind field for our server name
        if (toolCall.has(AcpJsonKeyEnum.KIND.key())) {
            String kind = toolCall.get(AcpJsonKeyEnum.KIND.key()).getAsString();
            if (kind != null && OUR_MCP_TOOL_NAMES.contains(kind)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<JsonObject> onWriteTextFile(JsonObject params) {
        // Slice 4: route through diff panel.
        return CompletableFuture.completedFuture(new JsonObject());
    }

    @Override
    public CompletableFuture<JsonObject> onReadTextFile(JsonObject params) {
        // Slice 4: serve from IDE.
        return CompletableFuture.completedFuture(new JsonObject());
    }

    @Override
    public void onDisconnected(Exception cause) {
        disconnectCallback.run();
    }
}
