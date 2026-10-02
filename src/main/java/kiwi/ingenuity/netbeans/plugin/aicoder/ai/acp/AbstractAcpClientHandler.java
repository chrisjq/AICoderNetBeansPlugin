package kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.McpSteeringPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.McpSteeringRefusalEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;

/**
 * Backend-neutral ACP client: the {@code session/request_permission} → {@link PermissionEvent}/
 * {@link ConfirmEvent} bridge and the {@code session/update} dispatch skeleton, plus the wire decoding both
 * of those need. Concrete handlers supply backend-specific event mapping (how an agent-message chunk, a tool
 * call or a usage update becomes a plugin event) through the {@code onXxx} hooks below; everything about
 * turning a permission request into a diff-panel prompt and a reply is shared, because the policy it encodes
 * (own-session-tree exemption, read-scope, MCP steering, one-decision-per-request) is a plugin rule, not an
 * agent's.
 */
public abstract class AbstractAcpClientHandler implements AcpClientHandler {

    private static final Set<String> MUTATION_KINDS = Set.of("edit", "write", "patch", "delete", "move");

    private static final Set<String> OUR_MCP_TOOL_NAMES = Set.of(McpToolEnum.allMcpNames().split(","));

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

    protected final AiProcessEventListener listener;
    /**
     * The backend's own logger, passed in rather than shared, so each backend's debug-JSON trail keeps
     * appearing under its own class name — including for a test that attaches a
     * {@link java.util.logging.Handler} to that specific logger.
     */
    protected final Logger log;
    private final Runnable disconnectCallback;
    private final Predicate<String> ownSessionConfigFile;
    private final Predicate<String> readPolicy;
    private final Function<String, String> readRefusalReason;
    private final Predicate<String> steeringIsActiveCheck;
    private final String pluginSessionId;
    private final Map<String, String> toolKindByCallId = new ConcurrentHashMap<>();
    private final List<PolicyRefusalEvent.Refusal> turnRefusals = new ArrayList<>();
    private volatile CompletableFuture<PermissionDecision> pendingPermission;

    /**
     * @param log                   the concrete backend's own logger (e.g.
     *                              {@code Logger.getLogger(OpenCodeAcpClientHandler.class.getName())}), so
     *                              log lines keep the backend's class name rather than the base's.
     * @param ownSessionConfigFile  true for a path inside this plugin session's own
     *                              {@code ~/.ai-coder/{type}/{sessionId}/} tree — see
     *                              {@link #sessionFileScope}. A permission request whose every path passes is
     *                              answered "allow once" without asking the user. Null means "nothing is
     *                              exempt": every path-bearing request is put to the user.
     * @param steeringIsActiveCheck optional check for whether MCP steering is active for a session (by plugin
     *                              sessionId). If null, steering is determined via {@link SessionRegistry} at
     *                              runtime, under {@code pluginSessionId}.
     * @param pluginSessionId       the PLUGIN session UUID this session is registered under in
     *                              {@link SessionRegistry} — never the agent's own ACP session id, which the
     *                              registry does not know. Null for callers with no plugin session (tests);
     *                              steering then resolves through {@code steeringIsActiveCheck} if given,
     *                              else off.
     */
    protected AbstractAcpClientHandler(AiProcessEventListener listener, Runnable disconnectCallback, Logger log,
                                       Predicate<String> ownSessionConfigFile, Predicate<String> steeringIsActiveCheck, String pluginSessionId) {
        this.listener = listener;
        this.disconnectCallback = disconnectCallback;
        this.log = log;
        this.ownSessionConfigFile = ownSessionConfigFile;
        this.steeringIsActiveCheck = steeringIsActiveCheck;
        this.pluginSessionId = pluginSessionId;
        // The read policy travels inside the same object passed for the own-tree check, so wiring it needs
        // no change at the call site. A plain predicate (a test, or a caller with no read policy) carries
        // none, and then a read is put to the user exactly as before.
        this.readPolicy = ownSessionConfigFile instanceof SessionFileScope scope ? scope::isReadAllowed : null;
        this.readRefusalReason = ownSessionConfigFile instanceof SessionFileScope scope ? scope::refusalReason : null;
    }

    /**
     * One plugin session's file policy as the ACP handler needs it: the own-tree exemption (as a
     * {@link Predicate}, so it stays drop-in for the {@code ownSessionConfigFile} constructor parameter) plus
     * the read-access decision.
     */
    public static final class SessionFileScope implements Predicate<String> {

        private final Predicate<String> ownSessionConfigFile;
        private final Predicate<String> readAllowed;
        private final Function<String, String> refusalReason;

        public SessionFileScope(Predicate<String> ownSessionConfigFile, Predicate<String> readAllowed) {
            this(ownSessionConfigFile, readAllowed, null);
        }

        public SessionFileScope(Predicate<String> ownSessionConfigFile, Predicate<String> readAllowed,
                                Function<String, String> refusalReason) {
            this.ownSessionConfigFile = ownSessionConfigFile;
            this.readAllowed = readAllowed;
            this.refusalReason = refusalReason;
        }

        /**
         * Why {@code GetFileContent} would refuse {@code path}, in its own words, or null when no source is
         * wired. For the log only — nothing that reaches the agent or the user.
         */
        public String refusalReason(String path) {
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
        public boolean isReadAllowed(String path) {
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
     * serve a file. Calling that same method is what makes a native read and a {@code GetFileContent} read
     * obey one policy by construction. It is false for a null server or session, so it fails toward denial
     * rather than approval.</li>
     * </ul>
     * Needs the PLUGIN session id (the one the MCP server registered), not the agent's own ACP session id,
     * which is why the process manager that knows it supplies this.
     */
    public static SessionFileScope sessionFileScope(Supplier<McpHookServer> server, String pluginSessionId) {
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
     * {@link #sessionFileScope} against the shared server, typed as the {@link Predicate} the constructor
     * takes. The returned object is a {@link SessionFileScope}, which is how the read policy gets in.
     */
    public static Predicate<String> ownSessionConfigFileCheck(String pluginSessionId) {
        return sessionFileScope(kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry::getServer, pluginSessionId);
    }

    public static String extractFirstLocationPath(JsonObject update) {
        if (update == null || !update.has(AcpJsonKeyEnum.LOCATIONS.key())
            || !update.get(AcpJsonKeyEnum.LOCATIONS.key()).isJsonArray()) {
            return null;
        }
        JsonArray locations = update.getAsJsonArray(AcpJsonKeyEnum.LOCATIONS.key());
        if (locations.size() == 0 || !locations.get(0).isJsonObject()) {
            return null;
        }
        JsonObject location = locations.get(0).getAsJsonObject();
        return location.has(AcpJsonKeyEnum.PATH.key()) && !location.get(AcpJsonKeyEnum.PATH.key()).isJsonNull()
               ? location.get(AcpJsonKeyEnum.PATH.key()).getAsString() : null;
    }

    public static ToolUseEvent.Kind mapToolKind(String kind) {
        if ("write".equals(kind)) {
            return ToolUseEvent.Kind.WRITE;
        }
        if ("edit".equals(kind) || "patch".equals(kind)) {
            return ToolUseEvent.Kind.EDIT;
        }
        return ToolUseEvent.Kind.OTHER;
    }

    public static String extractPermissionFilePath(JsonObject toolCall) {
        if (toolCall == null) {
            return null;
        }
        if (toolCall.has(AcpJsonKeyEnum.CONTENT.key()) && toolCall.get(AcpJsonKeyEnum.CONTENT.key()).isJsonArray()) {
            JsonArray content = toolCall.getAsJsonArray(AcpJsonKeyEnum.CONTENT.key());
            if (content.size() > 0 && content.get(0).isJsonObject()) {
                JsonObject first = content.get(0).getAsJsonObject();
                if (first.has(AcpJsonKeyEnum.PATH.key()) && !first.get(AcpJsonKeyEnum.PATH.key()).isJsonNull()) {
                    return first.get(AcpJsonKeyEnum.PATH.key()).getAsString();
                }
            }
        }
        String locationPath = extractFirstLocationPath(toolCall);
        if (locationPath != null) {
            return locationPath;
        }
        if (toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key()) && toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()) {
            JsonObject rawInput = toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key());
            if (rawInput.has(AcpJsonKeyEnum.FILEPATH.key()) && !rawInput.get(AcpJsonKeyEnum.FILEPATH.key()).isJsonNull()) {
                return rawInput.get(AcpJsonKeyEnum.FILEPATH.key()).getAsString();
            }
            // Grok's rawInput shape: {variant, file_path, content} — a different field name for the same thing.
            if (rawInput.has(AcpJsonKeyEnum.FILE_PATH.key()) && !rawInput.get(AcpJsonKeyEnum.FILE_PATH.key()).isJsonNull()) {
                return rawInput.get(AcpJsonKeyEnum.FILE_PATH.key()).getAsString();
            }
        }
        return null;
    }

    public static String extractRawInputCommand(JsonObject toolCall) {
        if (toolCall == null || !toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key())
            || !toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()) {
            return null;
        }
        JsonObject rawInput = toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key());
        return rawInput.has(AcpJsonKeyEnum.COMMAND.key()) && !rawInput.get(AcpJsonKeyEnum.COMMAND.key()).isJsonNull()
               ? rawInput.get(AcpJsonKeyEnum.COMMAND.key()).getAsString() : null;
    }

    public static String extractDiffNewText(JsonObject toolCall) {
        if (toolCall == null) {
            return null;
        }
        if (toolCall.has(AcpJsonKeyEnum.CONTENT.key()) && toolCall.get(AcpJsonKeyEnum.CONTENT.key()).isJsonArray()) {
            JsonArray content = toolCall.getAsJsonArray(AcpJsonKeyEnum.CONTENT.key());
            if (content.size() > 0 && content.get(0).isJsonObject()) {
                JsonObject first = content.get(0).getAsJsonObject();
                if ("diff".equals(first.has(AcpJsonKeyEnum.TYPE.key())
                                  ? first.get(AcpJsonKeyEnum.TYPE.key()).getAsString() : null)
                    && first.has(AcpJsonKeyEnum.NEW_TEXT.key()) && !first.get(AcpJsonKeyEnum.NEW_TEXT.key()).isJsonNull()) {
                    return first.get(AcpJsonKeyEnum.NEW_TEXT.key()).getAsString();
                }
            }
        }
        // Grok's rawInput shape: {variant, file_path, content} — content is the proposed file text directly, not a
        // diff content block.
        if (toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key()) && toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()) {
            JsonObject rawInput = toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key());
            if (rawInput.has(AcpJsonKeyEnum.CONTENT.key()) && rawInput.get(AcpJsonKeyEnum.CONTENT.key()).isJsonPrimitive()
                && rawInput.get(AcpJsonKeyEnum.CONTENT.key()).getAsJsonPrimitive().isString()) {
                return rawInput.get(AcpJsonKeyEnum.CONTENT.key()).getAsString();
            }
        }
        return null;
    }

    /**
     * Returns every path named by a permission request, in order and without duplicates.
     *
     * <p>
     * Callers must check all paths before silently exempting a request: an external-directory request can
     * name a session spool file and another directory, and the first safe path must not allow the second one
     * through unasked.
     */
    public static List<String> extractAllPermissionPaths(JsonObject toolCall) {
        Set<String> paths = new LinkedHashSet<>();
        if (toolCall == null) {
            return List.of();
        }
        collectPaths(toolCall, AcpJsonKeyEnum.CONTENT.key(), paths);
        collectPaths(toolCall, AcpJsonKeyEnum.LOCATIONS.key(), paths);
        if (toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key()) && toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()) {
            JsonObject rawInput = toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key());
            for (String key : new String[]{"filepath", "filePath", "file_path", "parentDir", "path"}) {
                addIfString(rawInput, key, paths);
            }
            if (rawInput.has("directories") && rawInput.get("directories").isJsonArray()) {
                for (var directory : rawInput.getAsJsonArray("directories")) {
                    if (directory.isJsonPrimitive() && directory.getAsJsonPrimitive().isString()
                        && !directory.getAsString().isBlank()) {
                        paths.add(directory.getAsString());
                    }
                }
            }
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

    public static boolean isMutationRequest(String kind, JsonObject toolCall) {
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
        if (!toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key()) || !toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()) {
            return false;
        }
        JsonObject rawInput = toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key());
        if (rawInput.has("diff")) {
            return true;
        }
        // Grok's write shape: rawInput {variant, file_path, content} where content is the full proposed file
        // text as a plain string — a mutation regardless of what kind the request claims (an "other"-kind
        // ask carrying this shape is still a write, and must not be let through the look-only ConfirmEvent
        // path without the diff panel).
        boolean hasStringContent = rawInput.has(AcpJsonKeyEnum.CONTENT.key())
                                   && rawInput.get(AcpJsonKeyEnum.CONTENT.key()).isJsonPrimitive()
                                   && rawInput.get(AcpJsonKeyEnum.CONTENT.key()).getAsJsonPrimitive().isString();
        boolean hasPath = (rawInput.has(AcpJsonKeyEnum.FILEPATH.key()) && !rawInput.get(AcpJsonKeyEnum.FILEPATH.key()).isJsonNull())
                          || (rawInput.has(AcpJsonKeyEnum.FILE_PATH.key()) && !rawInput.get(AcpJsonKeyEnum.FILE_PATH.key()).isJsonNull());
        return hasStringContent && hasPath;
    }

    public static String accessToolName(String kind) {
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

    public static String accessDisplayText(String kind, String title, String path) {
        return "search".equals(kind) && title != null && !title.isBlank() && !title.equals(path)
               ? title.trim() + " in " + path : path;
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

    private static void addIfString(JsonObject object, String key, Set<String> into) {
        if (object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isString()
            && !object.get(key).getAsString().isBlank()) {
            into.add(object.get(key).getAsString());
        }
    }

    // ---- Permission bridge: session/request_permission -> PermissionEvent/ConfirmEvent ----
    /**
     * A well-formed {@code selected} reply naming {@code optionId}, or — when {@code optionId} is null — a
     * clean {@code cancelled} outcome instead. Built without touching {@link #pendingPermission}: a request
     * decided by policy never became pending, and clearing the field here could drop a genuinely open
     * dialog's future.
     *
     * <p>
     * The null case is the fail-closed path: {@link #resolveOptionIds} leaves an id null rather than invent
     * one the agent never offered, and replying with a fabricated id risks the agent treating an invented
     * "allow" as a real approval (or an invented "reject" as a no-op it ignores) instead of the refusal we
     * mean. {@code cancelled} is the one reply every ACP agent must already understand — the same outcome a
     * turn-level cancel produces — so this is always safe, never a guess.
     */
    private static JsonObject selectedResult(String optionId) {
        JsonObject outcome = new JsonObject();
        if (optionId == null) {
            outcome.addProperty(AcpJsonKeyEnum.OUTCOME.key(), "cancelled");
        }
        else {
            outcome.addProperty(AcpJsonKeyEnum.OUTCOME.key(), "selected");
            outcome.addProperty(AcpJsonKeyEnum.OPTION_ID.key(), optionId);
        }
        JsonObject result = new JsonObject();
        result.add(AcpJsonKeyEnum.OUTCOME.key(), outcome);
        return result;
    }

    /**
     * The optionId to send back for "allow once" and for "reject", resolved per-request from
     * {@code params.options} rather than hardcoded: backends spell these differently — OpenCode's optionIds
     * are literally {@code "once"}/{@code "reject"}, Grok's are {@code "allow-once"}/{@code "allow-edits-session"}/
     * {@code "reject"}-shaped strings — but every backend's options array carries the standard {@code kind} (
     * {@link AcpPermissionKindEnum#ALLOW_ONCE}, {@code REJECT_ONCE}, {@code REJECT_ALWAYS}) alongside its own
     * optionId (confirmed against a live Grok probe), so matching on kind works for all of them without a
     * per-backend table.
     *
     * <p>
     * We always resolve to an ALLOW_ONCE id, never ALLOW_ALWAYS: see {@link #mapDecisionToAcpResult}'s note
     * on why "always" is never sent. Reject accepts either REJECT_ONCE or REJECT_ALWAYS, whichever appears
     * FIRST in the array — the same first-wins rule the allow search uses, so neither search is favoured by
     * array order.
     *
     * <p>
     * FAILS CLOSED: an options array that was genuinely sent but carries no recognised allow/reject kind must
     * never fall back to an invented literal — the agent might not have that id at all. The fallback instead
     * looks at each option's own {@code optionId} text for an "allow"/"reject"-shaped word (this is
     * deliberately keyed on optionId, not kind: a backend that reaches this fallback has already failed to
     * supply a kind we recognise, so its optionId is the only text left to go on), EXCLUDING any option whose
     * id or kind also says "always" or "session" — "allow-edits-session" contains "allow" too, and picking it
     * for the ALLOW_ONCE slot would grant a standing approval for a single click the user never asked to make
     * standing. If that finds nothing either, leaves the slot {@code null} — {@link
     * #selectedResult} turns a null id into a clean {@code cancelled} reply, never a guess at which broader
     * grant was meant. Only the complete ABSENCE of an options field in the request (no array at all — an
     * older probe, or a caller, including most of this handler's own tests, that never populated one) falls
     * back to the original literal {@code "once"}/{@code "reject"} strings; those are real OpenCode
     * optionIds, not a guess, and this is the one case existing behaviour must stay unchanged.
     */
    private record PermissionOptionIds(String allowOnceId, String rejectId) {

    }

    private PermissionOptionIds resolveOptionIds(JsonObject params) {
        boolean hasOptionsArray = params != null && params.has(AcpJsonKeyEnum.OPTIONS.key())
                                  && params.get(AcpJsonKeyEnum.OPTIONS.key()).isJsonArray();
        if (!hasOptionsArray) {
            return new PermissionOptionIds("once", "reject");
        }
        String allowOnce = null;
        String reject = null;
        String allowLike = null;
        String rejectLike = null;
        for (var element : params.getAsJsonArray(AcpJsonKeyEnum.OPTIONS.key())) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject option = element.getAsJsonObject();
            String optionId = option.has(AcpJsonKeyEnum.OPTION_ID.key()) && option.get(AcpJsonKeyEnum.OPTION_ID.key()).isJsonPrimitive()
                              ? option.get(AcpJsonKeyEnum.OPTION_ID.key()).getAsString() : null;
            if (optionId == null) {
                continue;
            }
            String kindWire = option.has(AcpJsonKeyEnum.KIND.key()) && option.get(AcpJsonKeyEnum.KIND.key()).isJsonPrimitive()
                              ? option.get(AcpJsonKeyEnum.KIND.key()).getAsString() : null;
            AcpPermissionKindEnum kind = AcpPermissionKindEnum.fromWire(kindWire);
            if (kind == AcpPermissionKindEnum.ALLOW_ONCE && allowOnce == null) {
                allowOnce = optionId;
            }
            if ((kind == AcpPermissionKindEnum.REJECT_ONCE || kind == AcpPermissionKindEnum.REJECT_ALWAYS) && reject == null) {
                reject = optionId;
            }
            String optionIdLower = optionId.toLowerCase(Locale.ROOT);
            String kindLower = kindWire != null ? kindWire.toLowerCase(Locale.ROOT) : "";
            // Never widen scope in the fallback: an id/kind that also says "always" or "session" (e.g.
            // "allow-always", "allow-edits-session") is not an allow-ONCE option just because it contains
            // "allow" — granting it here would turn a single approval into a standing one the user never
            // asked for. If nothing allow-once-shaped survives this exclusion, the slot stays null and
            // selectedResult turns that into "cancelled", never a guess at which broader grant was meant.
            boolean scopedWiderThanOnce = optionIdLower.contains("always") || optionIdLower.contains("session")
                                          || kindLower.contains("always") || kindLower.contains("session");
            if (!scopedWiderThanOnce) {
                if (allowLike == null && optionIdLower.contains("allow")) {
                    allowLike = optionId;
                }
                if (rejectLike == null && (optionIdLower.contains("reject") || optionIdLower.contains("deny"))) {
                    rejectLike = optionId;
                }
            }
        }
        String resolvedAllow = allowOnce != null ? allowOnce : allowLike;
        String resolvedReject = reject != null ? reject : rejectLike;
        if (allowOnce == null || reject == null) {
            // At least one slot needed the broader optionId-text fallback, or found nothing at all. Always
            // worth a line: a backend sending options with no kind we recognise is either a protocol
            // surprise or a bug on our side, and this is the only place that would ever notice.
            log.log(Level.WARNING,
                    "{0} permission options carried no recognised allow_once/reject_once/reject_always kind "
                    + "(allowOnce missing={1}, reject missing={2}); falling back to optionId text -> allow={3}, reject={4}",
                    new Object[]{backendDisplayName(), allowOnce == null, reject == null, resolvedAllow, resolvedReject});
        }
        return new PermissionOptionIds(resolvedAllow, resolvedReject);
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
        }
        catch (RuntimeException e) {
            log.log(Level.FINE, "own-session check failed; asking the user instead", e);
            return false;
        }
        return true;
    }

    /**
     * Remembers which kind of tool a call id belongs to, until it reports a terminal status. An {@code
     * external_directory} permission can arrive as a kind that does not say which tool wanted the directory;
     * the only thing linking it to a read is that its {@code toolCallId} is the id of an earlier
     * {@code tool_call} whose kind was {@code read}. An update without a kind leaves the remembered one
     * alone. Call this from your {@code onToolEvent} override for every {@code tool_call}/
     * {@code tool_call_update}.
     */
    protected final void rememberToolKind(String toolCallId, String kind, String status) {
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
     * announced it (see {@link #rememberToolKind}), or null when that call was never seen (or has completed).
     * Null is never treated as a read.
     */
    private String originatingToolKind(JsonObject toolCall) {
        String toolCallId = toolCall == null ? null
                            : (toolCall.has(AcpJsonKeyEnum.TOOL_CALL_ID.key()) && toolCall.get(AcpJsonKeyEnum.TOOL_CALL_ID.key()).isJsonPrimitive()
                               ? toolCall.get(AcpJsonKeyEnum.TOOL_CALL_ID.key()).getAsString() : null);
        return toolCallId == null ? null : toolKindByCallId.get(toolCallId);
    }

    /**
     * True only when the request is CONFIDENTLY a read: not a mutation, and either its own kind is
     * {@code read}/{@code search}, or it is an {@code other}-kind request (an external-directory-style ask)
     * whose originating tool call was a {@code read}/{@code search}. An {@code other} request that cannot be
     * traced to a read is not a read, because that ask precedes every kind of access and treating it as one
     * would silently decide an edit or a shell command on a read's rules.
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
        }
        catch (RuntimeException e) {
            log.log(Level.FINE, "read-scope check failed; asking the user instead", e);
            return null;
        }
    }

    /**
     * The refusal {@code GetFileContent} returns for {@code refusedPath}, from the same
     * {@link McpHookServer#fileAccessDeniedMessage}, or null when there is no source, it yields nothing, or
     * it fails. Never allowed to throw: it runs on the refusal path, and neither a log line nor a notice may
     * be able to break the reply to the agent.
     */
    private String refusalMessage(String refusedPath) {
        Function<String, String> reason = readRefusalReason;
        if (reason == null || refusedPath == null) {
            return null;
        }
        try {
            String text = reason.apply(refusedPath);
            return text == null || text.isBlank() ? null : text;
        }
        catch (RuntimeException e) {
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
    public final List<PolicyRefusalEvent.Refusal> consumeTurnRefusals() {
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
    public final void clearTurnRefusals() {
        synchronized (turnRefusals) {
            turnRefusals.clear();
        }
    }

    /**
     * Checks if MCP steering is enabled for the plugin session this handler serves. Steering is resolved
     * under the constructor's {@code pluginSessionId} — the PLUGIN session UUID the session is registered
     * under in {@link SessionRegistry} — never under the agent's own ACP session id, which the registry does
     * not know. When a {@code steeringIsActiveCheck} is injected (tests), it decides; in production the
     * registry lookup decides.
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
     * TIER 1 — fully verified: the tool call is auto-allowed with no prompt at all (see the early-return in
     * {@link #handlePermissionRequest}) and never steered away. Stricter than {@link #namesOurTool} (tier 2)
     * on purpose, since this tier skips the diff panel entirely rather than just skipping steering.
     *
     * <p>
     * SECURITY (Coder_1 HIGH + BigP_1 independently rated it HIGH — a consent AND steering bypass, since it
     * runs before every other branch): this used to also accept a bare {@code title}/{@code kind} match, and
     * read the tool name from whichever of {@code rawInput.tool_name}/{@code toolName}/{@code tool}/
     * {@code name} existed. All of those are attacker-controlled strings an agent's OWN native tool call can
     * populate — a native {@code edit}/{@code write}/{@code execute} call whose {@code rawInput.name} (or
     * bare {@code title}) happened to equal one of our tool names would have been auto-allowed straight past
     * the diff panel. Now ALL of these must hold:
     * <ol>
     * <li>the request is STRUCTURALLY Grok's own {@code use_tool} meta-tool wrapper — {@code
     * rawInput.variant == "UseTool"}, or {@code title == "use_tool"}, or {@code _meta["x.ai/tool"].name ==
     * "use_tool"} (verified live shapes) — never a bare title/kind match, and {@code kind} is never consulted
     * at all: it is ACP's type discriminator, not a place a tool name legitimately appears;</li>
     * <li>the tool name comes ONLY from {@code rawInput.tool_name} (not {@code toolName}/{@code tool}/
     * {@code name} — those were never confirmed live and only widened the attack surface), resolved by
     * {@link #matchesOurTool}'s strict prefix rule to a real {@link McpToolEnum};</li>
     * <li>the call carries no resolvable path ({@link #extractPermissionFilePath}) and is not itself a
     * mutation ({@link #isMutationRequest}) — belt-and-suspenders in case a genuine wrapper call ever carries
     * either, since a real use_tool call's actual arguments live nested under {@code rawInput.tool_input},
     * never at these top-level fields.</li>
     * </ol>
     */
    private boolean isVerifiedOurToolWrapper(JsonObject toolCall) {
        if (toolCall == null || !toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key())
            || !toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()) {
            return false;
        }
        JsonObject rawInput = toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key());
        if (!isGrokUseToolWrapper(toolCall, rawInput)) {
            return false;
        }
        String kind = toolCall.has(AcpJsonKeyEnum.KIND.key()) && !toolCall.get(AcpJsonKeyEnum.KIND.key()).isJsonNull()
                      ? toolCall.get(AcpJsonKeyEnum.KIND.key()).getAsString() : null;
        if (isMutationRequest(kind, toolCall) || extractPermissionFilePath(toolCall) != null) {
            return false;
        }
        return matchesOurTool(firstStringField(rawInput, "tool_name"));
    }

    /**
     * TIER 2 — name-only match, restoring OpenCode's original behaviour: EXEMPT FROM STEERING, never
     * auto-allowed. OpenCode can call one of our tools directly (no {@code use_tool}-style wrapper at all),
     * and its permission request's own {@code title}/{@code rawInput.tool_name} then names the tool plainly —
     * unlike Grok, this is not spoofable-with-impunity the way {@link #isVerifiedOurToolWrapper}'s bare match
     * used to be, because a hit here only lifts the STEERING refusal; the call still falls through to the
     * normal diff panel / confirm dialog afterwards. Checked from {@code title} and {@code
     * rawInput.tool_name} only, by {@link #matchesOurTool}'s same strict prefix rule — never {@code kind}
     * (ACP's type discriminator, not a tool-name field) and never the wider {@code toolName}/{@code tool}/
     * {@code name} rawInput keys tier 1 also dropped.
     */
    private boolean namesOurTool(JsonObject toolCall) {
        if (toolCall == null) {
            return false;
        }
        if (toolCall.has(AcpJsonKeyEnum.TITLE.key()) && !toolCall.get(AcpJsonKeyEnum.TITLE.key()).isJsonNull()
            && matchesOurTool(toolCall.get(AcpJsonKeyEnum.TITLE.key()).getAsString())) {
            return true;
        }
        if (toolCall.has(AcpJsonKeyEnum.RAW_INPUT.key()) && toolCall.get(AcpJsonKeyEnum.RAW_INPUT.key()).isJsonObject()
            && matchesOurTool(firstStringField(toolCall.getAsJsonObject(AcpJsonKeyEnum.RAW_INPUT.key()), "tool_name"))) {
            return true;
        }
        return false;
    }

    /**
     * Exempt from MCP steering — either tier qualifies. Tier 1 ({@link #isVerifiedOurToolWrapper}) never
     * reaches the steering checks below at all (the early-return in {@link #handlePermissionRequest} answers
     * it first), so in practice this is tier 2 ({@link #namesOurTool}) alone; kept as the sum of both so the
     * steering checks read as "exempt", not "tier 2", and stay correct if that early-return ever changes.
     */
    private boolean isExemptFromSteering(JsonObject toolCall) {
        return isVerifiedOurToolWrapper(toolCall) || namesOurTool(toolCall);
    }

    /**
     * True when {@code toolCall} is structurally Grok's own {@code use_tool} meta-tool wrapper — verified
     * live shapes only, checked independently of any tool name: {@code rawInput.variant == "UseTool"},
     * {@code title == "use_tool"}, or {@code _meta["x.ai/tool"].name == "use_tool"}. A native call (edit,
     * write, execute, ...) is never shaped like this, however its other fields are spoofed.
     */
    private static boolean isGrokUseToolWrapper(JsonObject toolCall, JsonObject rawInput) {
        if ("UseTool".equals(firstStringField(rawInput, "variant"))) {
            return true;
        }
        if (toolCall.has(AcpJsonKeyEnum.TITLE.key()) && !toolCall.get(AcpJsonKeyEnum.TITLE.key()).isJsonNull()
            && "use_tool".equals(toolCall.get(AcpJsonKeyEnum.TITLE.key()).getAsString())) {
            return true;
        }
        if (toolCall.has("_meta") && toolCall.get("_meta").isJsonObject()) {
            JsonObject meta = toolCall.getAsJsonObject("_meta");
            if (meta.has("x.ai/tool") && meta.get("x.ai/tool").isJsonObject()
                && "use_tool".equals(firstStringField(meta.getAsJsonObject("x.ai/tool"), "name"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prefix grok-4.6 uses for its own {@code use_tool} wrapping of one of OUR tools: the plugin's bare
     * server name plus "__" — e.g. {@code "aicoder-nb-ki-plugin__GetInstructions"}, NOT the full
     * {@code "mcp__aicoder-nb-ki-plugin__"} form {@link #OUR_MCP_TOOL_NAMES} holds (verified live fact).
     */
    private static final String GROK_SERVER_PREFIX = StringConst.PLUGIN_ID + "__";

    /**
     * True when {@code candidate} names one of our own MCP tools — in the full qualified form
     * {@link #OUR_MCP_TOOL_NAMES} holds, or grok's bare-server-name form ({@link #GROK_SERVER_PREFIX}).
     * SECURITY: only these two specific, known-ours prefixes are ever stripped — never an arbitrary
     * {@code "<anything>__"}, since a user's OTHER MCP server could easily have one of our tools' bare names
     * too (e.g. {@code "othersrv__ApplyEdit"}), and treating that as ours would exempt a foreign tool from
     * steering.
     */
    private static boolean matchesOurTool(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (OUR_MCP_TOOL_NAMES.contains(candidate)) {
            return true;
        }
        if (!candidate.startsWith(GROK_SERVER_PREFIX)) {
            return false;
        }
        String bareName = candidate.substring(GROK_SERVER_PREFIX.length());
        return McpToolEnum.of(bareName) != null;
    }

    private static String firstStringField(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isString()) {
                return obj.get(key).getAsString();
            }
        }
        return null;
    }

    /**
     * Routes {@code session/request_permission} by {@code toolCall.kind}, not by whether a file path happened
     * to resolve:
     * <ul>
     * <li>one of OUR OWN MCP tools, fully verified ({@link #isVerifiedOurToolWrapper}, tier 1) — answered
     * "allow once" immediately, no event, no prompt, REGARDLESS of steering, kind or path. Our tools carry
     * their own gating already (write tools through the diff panel, deletes through their own confirmation,
     * and so on); asking again here would put a yes/no dialog in front of every single plugin-tool call an
     * agent makes through {@code use_tool} — unusable. Checked first, before every other branch below. A
     * request that merely NAMES one of our tools without the wrapper shape ({@link #namesOurTool}, tier 2)
     * does not qualify here — it only skips the steering checks further down, via
     * {@link #isExemptFromSteering}, and still falls through to the normal diff panel / confirm dialog.</li>
     * <li>{@code execute} — a shell command. There is no diff to render, so this raises {@link ConfirmEvent}
     * (yes/no), not {@link PermissionEvent}.</li>
     * <li>a request that is CONFIDENTLY a read ({@link #isConfidentRead}) — a policy decision, made with no
     * event and nothing shown: allowed if {@code GetFileContent} would be allowed to read every path it
     * names, refused (a clean {@code reject} reply) if not. Undecidable — no policy wired, no paths, or the
     * policy failing — falls through to asking.</li>
     * <li>every path named is inside this session's own config tree — answered "allow once" with no event and
     * no prompt, the same exemption the plugin's own file tools and the Claude/Pi hook give that tree.</li>
     * <li>a resolvable path on a request that proposes a change ({@link #isMutationRequest}) —
     * {@link PermissionEvent} "Write", the only path that renders a diff.</li>
     * <li>a resolvable path on a read/search/fetch/think/other-shaped request — a request to ACCESS that
     * path, raised as a yes/no {@link ConfirmEvent} named for what it is. It carries no diff, so it must not
     * go through the "Write" path.</li>
     * <li>a resolvable path on an unrecognised or absent kind — {@link PermissionEvent} "Write", rather than
     * a guess.</li>
     * <li>no path at all — the subject could not be identified. Raise {@link ConfirmEvent} instead, showing
     * the kind and title so there is at least something real to see, and never treat it as silently
     * approvable.</li>
     * </ul>
     *
     * @param accessKinds kinds this backend's agent sends for a look-only request (read/search/fetch/think
     *                    and whatever else it maps an external-directory-style ask to)
     */
    protected final CompletableFuture<JsonObject> handlePermissionRequest(JsonObject params, Set<String> accessKinds) {
        JsonObject toolCall = params.has(AcpJsonKeyEnum.TOOL_CALL.key()) && params.get(AcpJsonKeyEnum.TOOL_CALL.key()).isJsonObject()
                              ? params.getAsJsonObject(AcpJsonKeyEnum.TOOL_CALL.key()) : null;
        String kind = toolCall != null && toolCall.has(AcpJsonKeyEnum.KIND.key()) && !toolCall.get(AcpJsonKeyEnum.KIND.key()).isJsonNull()
                      ? toolCall.get(AcpJsonKeyEnum.KIND.key()).getAsString() : null;
        String title = toolCall != null && toolCall.has(AcpJsonKeyEnum.TITLE.key()) && !toolCall.get(AcpJsonKeyEnum.TITLE.key()).isJsonNull()
                       ? toolCall.get(AcpJsonKeyEnum.TITLE.key()).getAsString() : null;
        String filePath = extractPermissionFilePath(toolCall);
        PermissionOptionIds optionIds = resolveOptionIds(params);

        if (isVerifiedOurToolWrapper(toolCall)) {
            if (PluginSettings.isDebugJson()) {
                log.log(Level.INFO, "{0} permission request for one of our own MCP tools (kind={1}, title={2}) "
                                    + "-> allow-once, no prompt", new Object[]{backendDisplayName(), kind, title});
            }
            return CompletableFuture.completedFuture(selectedResult(optionIds.allowOnceId()));
        }

        if ("execute".equals(kind)) {
            String command = extractRawInputCommand(toolCall);
            String displayText = command != null ? command : title != null ? title : "(unknown command)";
            if (steeringIsActive() && !isExemptFromSteering(toolCall)) {
                McpHookServerUtil.logMcpSteeringRefusal(backendSteeringLogName(), McpSteeringPolicy.Category.SHELL, displayText);
                String steeringText = McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.SHELL);
                listener.onAiProcessEvent(new McpSteeringRefusalEvent(List.of(
                        new McpSteeringRefusalEvent.Refusal("Execute", steeringText))));
                return CompletableFuture.completedFuture(selectedResult(optionIds.rejectId()));
            }
            if (PluginSettings.isDebugJson()) {
                log.log(Level.INFO, "{0} permission request: kind=execute -> ConfirmEvent, command={1}",
                        new Object[]{backendDisplayName(), displayText});
            }
            return raiseConfirmAndReply("Execute", displayText, null, null, true, optionIds);
        }

        if (filePath != null && isConfidentRead(kind, toolCall)) {
            ReadDecision decision = decideRead(toolCall);
            if (decision != null) {
                String refusal = decision.allowed() ? null : refusalMessage(decision.refusedPath());
                recordRefusal(decision.refusedPath(), refusal);
                if (PluginSettings.isDebugJson()) {
                    log.log(Level.INFO, "{0} read request: path={1} toolCall.kind={2} originatingKind={3} -> {4} by "
                                        + "the read-scope rule, no prompt{5}", new Object[]{backendDisplayName(), filePath, kind,
                                                                                            originatingToolKind(toolCall), decision.allowed() ? "allowed" : "denied",
                                                                                            refusal == null ? "" : ". Reason: " + refusal});
                }
                return CompletableFuture.completedFuture(selectedResult(decision.allowed() ? optionIds.allowOnceId() : optionIds.rejectId()));
            }
        }

        if (filePath != null && isEntirelyInsideOwnSessionTree(toolCall)) {
            if (PluginSettings.isDebugJson()) {
                log.log(Level.INFO, "{0} permission request: path={1} toolCall.kind={2} title={3} is inside this "
                                    + "session's own config tree -> allowed once, no prompt", new Object[]{backendDisplayName(), filePath, kind, title});
            }
            return CompletableFuture.completedFuture(selectedResult(optionIds.allowOnceId()));
        }

        if (filePath != null && !isMutationRequest(kind, toolCall) && kind != null && accessKinds.contains(kind)) {
            if (steeringIsActive() && !isExemptFromSteering(toolCall)) {
                McpHookServerUtil.logMcpSteeringRefusal(backendSteeringLogName(), McpSteeringPolicy.Category.READ,
                        accessDisplayText(kind, title, filePath));
                String steeringText = McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.READ);
                listener.onAiProcessEvent(new McpSteeringRefusalEvent(List.of(
                        new McpSteeringRefusalEvent.Refusal(accessToolName(kind), steeringText))));
                return CompletableFuture.completedFuture(selectedResult(optionIds.rejectId()));
            }
            if (PluginSettings.isDebugJson()) {
                log.log(Level.INFO, "{0} permission request: path={1} toolCall.kind={2} title={3} -> ConfirmEvent({4}), not a Write",
                        new Object[]{backendDisplayName(), filePath, kind, title, accessToolName(kind)});
            }
            return raiseConfirmAndReply(accessToolName(kind), accessDisplayText(kind, title, filePath), filePath, null, false, optionIds);
        }

        if (filePath != null) {
            if (steeringIsActive() && !isExemptFromSteering(toolCall)) {
                McpHookServerUtil.logMcpSteeringRefusal(backendSteeringLogName(), McpSteeringPolicy.Category.WRITE, "Write " + filePath);
                String steeringText = McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.WRITE);
                listener.onAiProcessEvent(new McpSteeringRefusalEvent(List.of(
                        new McpSteeringRefusalEvent.Refusal("Write", steeringText))));
                return CompletableFuture.completedFuture(selectedResult(optionIds.rejectId()));
            }
            if (PluginSettings.isDebugJson()) {
                log.log(Level.INFO, "{0} permission request: path={1} toolCall.kind={2} title={3} -> PermissionEvent",
                        new Object[]{backendDisplayName(), filePath, kind, title});
            }
            String newText = extractDiffNewText(toolCall);
            CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
            pendingPermission = decisionFuture;
            listener.onAiProcessEvent(new PermissionEvent("Write", filePath, null, null, newText, decisionFuture));
            return decisionFuture.handle((decision, ex) -> mapDecisionToAcpResult(decision, ex, optionIds));
        }

        if (PluginSettings.isDebugJson()) {
            log.log(Level.WARNING,
                    "{0} permission request with no extractable file path and kind != execute "
                    + "-> ConfirmEvent(kind={1}, title={2}) instead of \"Write: null\". Raw params: {3}",
                    new Object[]{backendDisplayName(), kind, title, McpHookServerUtil.redactAllSecrets(String.valueOf(params))});
        }
        String toolName = kind != null && !kind.isBlank() ? kind : "Unknown";
        String displayText = title != null && !title.isBlank()
                             ? title : "(unidentified " + backendDisplayName() + " action, kind=" + toolName + ")";
        if (steeringIsActive() && !isExemptFromSteering(toolCall)) {
            McpHookServerUtil.logMcpSteeringRefusal(backendSteeringLogName(), McpSteeringPolicy.Category.UNKNOWN, displayText);
            String steeringText = McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.UNKNOWN);
            listener.onAiProcessEvent(new McpSteeringRefusalEvent(List.of(
                    new McpSteeringRefusalEvent.Refusal(toolName, steeringText))));
            return CompletableFuture.completedFuture(selectedResult(optionIds.rejectId()));
        }
        return raiseConfirmAndReply(toolName, displayText, null, null, true, optionIds);
    }

    /**
     * Lower-case backend key passed to {@link McpHookServerUtil#logMcpSteeringRefusal} — e.g.
     * {@code "opencode"}, {@code "grok"}. Purely cosmetic: never parsed, never sent on the wire.
     */
    protected abstract String backendSteeringLogName();

    /**
     * Human-readable backend name for the debug-JSON log lines and the "(unidentified X action...)" fallback
     * text — e.g. {@code "OpenCode"}, {@code "Grok"}. Purely cosmetic: never parsed, never sent on the wire.
     */
    protected abstract String backendDisplayName();

    /**
     * Raises a {@link ConfirmEvent} (yes/no, no diff to render) and maps the eventual
     * {@link PermissionDecision} to the ACP wire reply via {@link #mapDecisionToAcpResult}.
     */
    private CompletableFuture<JsonObject> raiseConfirmAndReply(
            String toolName, String displayText, String filePath, String targetPath,
            boolean requireExplicitApproval, PermissionOptionIds optionIds) {
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        listener.onAiProcessEvent(new ConfirmEvent(toolName, displayText, filePath, targetPath,
                decisionFuture, requireExplicitApproval));
        return decisionFuture.handle((decision, ex) -> mapDecisionToAcpResult(decision, ex, optionIds));
    }

    /**
     * Maps a completed {@link PermissionDecision} future to the ACP {@code session/request_permission} wire
     * response. Shared by every {@link #handlePermissionRequest} branch so the decision→outcome mapping lives
     * in one place.
     *
     * <p>
     * NOTE: We always reply "once" for an allow, never "always". The diff panel's auto-accept path calls
     * {@code PermissionDecision.allowed()} directly, so we cannot distinguish it from an explicit user click.
     * Replying "always" would configure the agent to skip future permission requests permanently — a
     * side-effect the user did not request from the auto-accept toggle.
     *
     * <p>
     * NOTE: unlike the Claude/MCP native-hook path (which applies the edit itself and then sends "deny" to
     * prevent a double-write), here answering "once" lets the agent perform the write/command itself. We MUST
     * NOT apply the edit ourselves — doing so would double-apply it.
     */
    private JsonObject mapDecisionToAcpResult(PermissionDecision decision, Throwable ex, PermissionOptionIds optionIds) {
        pendingPermission = null;
        if (ex != null || decision == null) {
            // Turn was cancelled while the permission dialog was open.
            return selectedResult(null);
        }
        // selectedResult(null) — a fail-closed id resolveOptionIds could not find — becomes the same
        // "cancelled" reply a genuine mid-dialog cancellation produces, never a guessed optionId.
        return selectedResult(decision.allow() ? optionIds.allowOnceId() : optionIds.rejectId());
    }

    /**
     * Cancels any in-flight permission dialog by completing its future exceptionally. Called by the process
     * manager on turn cancel and stop. The {@code handle} in {@link #handlePermissionRequest} maps this to
     * {@code {"outcome":{"outcome":"cancelled"}}} back to the agent.
     */
    public final void cancelPendingPermissions() {
        CompletableFuture<PermissionDecision> pf = pendingPermission;
        if (pf != null) {
            pendingPermission = null;
            pf.completeExceptionally(new CancellationException("turn cancelled"));
        }
    }

    @Override
    public void onDisconnected(Exception cause) {
        disconnectCallback.run();
    }

    @Override
    public CompletableFuture<JsonObject> onWriteTextFile(JsonObject params) {
        return CompletableFuture.completedFuture(new JsonObject());
    }

    @Override
    public CompletableFuture<JsonObject> onReadTextFile(JsonObject params) {
        return CompletableFuture.completedFuture(new JsonObject());
    }

    /**
     * Non-zero while a session/load-or-resume request is in flight, so replayed {@code session/update}
     * notifications for the conversation being loaded are dropped rather than rendered as new output —
     * live-confirmed Grok behaviour: it replays the WHOLE prior turn sequence (agent_message_chunk, tool
     * calls, even config changes) as notifications between sending {@code session/load} and answering it, and
     * the plugin's own persisted history already shows that conversation, so re-rendering it ahead of the
     * real reply duplicated the whole tab. Guarded generically — "while a load/resume call is in flight", not
     * hardcoded to Grok's method name — so a backend whose resume carries no such replay (OpenCode's
     * {@code session/resume}: confirmed live, and its response shape carries only {@code configOptions},
     * nothing streamed) is simply never affected: the flag is set and cleared around its own resume call the
     * same way, but nothing ever arrives while it is up.
     *
     * <p>
     * Owned by a token, not a plain boolean, mirroring {@code OpenCodeAcpClientHandler}'s compaction
     * suppression: a stale load's late clear can then never disarm a newer one's suppression. {@link
     * #beginSuppressingSessionUpdatesForLoad} arms a fresh token;
     * {@link #endSuppressingSessionUpdatesForLoad} clears only while it still matches.
     */
    private volatile long loadSuppressionToken;

    private final AtomicLong loadSuppressionTokens = new AtomicLong();

    /**
     * Call immediately before sending the load/resume request, and keep the returned token to pass back to
     * {@link #endSuppressingSessionUpdatesForLoad}.
     *
     * <p>
     * REVIEW FINDING (race): the load's wire response completes on {@code AcpConnection}'s dispatch executor
     * (or the reader thread directly), but any replay notification that precedes it is queued on the
     * single-thread {@code acp-notify} executor. Clearing suppression synchronously in the caller's own
     * {@code finally} the instant {@code get()} returns can therefore run BEFORE acp-notify has worked
     * through replay chunks it already queued, leaking the tail of the replay as real output and letting
     * {@code trackToolCallLifecycle} count a replayed tool call into the next turn. The caller MUST clear via
     * {@code conn.runOnNotifyThread(() -> endSuppressingSessionUpdatesForLoad(token))} (inline on {@link
     * java.util.concurrent.RejectedExecutionException} — the connection is already closed, so nothing is
     * queued behind it), exactly as {@code OpenCodeAiProcessManager.sendCompactPrompt} sequences its own
     * suppression's end onto the same executor for the same reason. Never call this overload — the one taking
     * a {@link Runnable} fencepost is for the connection-already-closed inline fallback ONLY — directly from
     * the thread that called {@code get()}.
     */
    public final long beginSuppressingSessionUpdatesForLoad() {
        return loadSuppressionToken = loadSuppressionTokens.incrementAndGet();
    }

    /**
     * Disarms suppression owned by {@code token}, if it is still current. A stale token (a newer load already
     * re-armed it) leaves the newer suppression untouched. Call ONLY from {@code acp-notify} (via {@code
     * conn.runOnNotifyThread}) or its inline fallback — see {@link #beginSuppressingSessionUpdatesForLoad}.
     */
    public final void endSuppressingSessionUpdatesForLoad(long token) {
        if (loadSuppressionToken == token) {
            loadSuppressionToken = 0;
        }
    }

    /**
     * Disarms load suppression unconditionally — the safety net {@code sendTurn} calls so a load that timed
     * out, or whose ordered clear never ran because the connection it belonged to is gone, can never silence
     * a real turn that starts afterwards. Mirrors {@code OpenCodeAcpClientHandler.clearTextSuppression}.
     */
    public final void clearSuppressingSessionUpdatesForLoad() {
        loadSuppressionToken = 0;
    }

    // ---- session/update dispatch skeleton ----
    /**
     * Dispatches by {@code sessionUpdate} kind to the {@code onXxx} hooks below. An unrecognised value is
     * ignored silently, never thrown on — the protocol adds new update kinds over time.
     */
    @Override
    public final void onSessionUpdate(String sessionId, JsonObject update) {
        if (loadSuppressionToken != 0) {
            return;
        }
        String raw = update.has(AcpJsonKeyEnum.SESSION_UPDATE.key()) ? update.get(AcpJsonKeyEnum.SESSION_UPDATE.key()).getAsString() : null;
        AcpSessionUpdateEnum type = AcpSessionUpdateEnum.fromWire(raw);
        if (type == null) {
            return;
        }
        switch (type) {
            case AGENT_MESSAGE_CHUNK ->
                onAgentMessageChunk(update);
            case AGENT_THOUGHT_CHUNK ->
                onAgentThoughtChunk(update);
            case TOOL_CALL, TOOL_CALL_UPDATE ->
                onToolEvent(update);
            case USAGE_UPDATE ->
                onUsageUpdate(update);
            case SESSION_INFO_UPDATE ->
                onSessionInfoUpdate(update);
            case CONFIG_OPTION_UPDATE ->
                onConfigOptionUpdate(update);
            default -> {
            }
        }
    }

    /**
     * An {@code agent_message_chunk}: streamed assistant text.
     */
    protected abstract void onAgentMessageChunk(JsonObject update);

    /**
     * An {@code agent_thought_chunk}: streamed reasoning/thinking text.
     */
    protected abstract void onAgentThoughtChunk(JsonObject update);

    /**
     * A {@code tool_call} or {@code tool_call_update}. Overrides calling {@link #rememberToolKind} keep the
     * permission bridge's external-directory heuristic working.
     */
    protected abstract void onToolEvent(JsonObject update);

    /**
     * A {@code usage_update}: token/context accounting, backend-specific shape.
     */
    protected abstract void onUsageUpdate(JsonObject update);

    /**
     * A {@code session_info_update} (e.g. title). No-op unless a backend overrides it.
     */
    protected void onSessionInfoUpdate(JsonObject update) {
    }

    /**
     * A {@code config_option_update} (model/effort echoed back). No-op unless a backend overrides it.
     */
    protected void onConfigOptionUpdate(JsonObject update) {
    }
}
