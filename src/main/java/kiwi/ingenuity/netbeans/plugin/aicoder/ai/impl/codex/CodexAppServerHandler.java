package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.MultiPermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.MultiPermissionItem;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexRateLimitEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;

/**
 * Maps inbound {@code app-server} traffic to plugin events (design doc §8) and bridges its two approval-request kinds
 * to the plugin's existing {@link ConfirmEvent} confirm flow (§0a "Working flow for the permission bridge"). Combined
 * in one class, like {@code OpenCodeAcpClientHandler}, rather than split into the design doc §6 sketch of separate
 * {@code CodexStreamParser}/{@code CodexPermissionBridge} classes: the fileChange approval request carries no diff of
 * its own and depends on the {@code changes[]} cached from an earlier {@code item/started} notification for the same
 * item id, so the two concerns share state and splitting them would only mean passing that cache between two objects.
 *
 * <p>
 * {@link #onNotification} and {@link #onServerRequest} are invoked on {@link CodexJsonRpcClient}'s notify/dispatch
 * executors — never the reader thread, never the EDT.
 */
class CodexAppServerHandler implements CodexNotificationListener, CodexServerRequestHandler, CodexConnectionListener {

    private static final Logger LOG = Logger.getLogger(CodexAppServerHandler.class.getName());

    static final String METHOD_AGENT_MESSAGE_DELTA = "item/agentMessage/delta";
    static final String METHOD_ITEM_STARTED = "item/started";
    static final String METHOD_TURN_STARTED = "turn/started";
    static final String METHOD_TURN_COMPLETED = "turn/completed";
    static final String METHOD_COMMAND_EXECUTION_APPROVAL = "item/commandExecution/requestApproval";
    static final String METHOD_FILE_CHANGE_APPROVAL = "item/fileChange/requestApproval";
    static final String METHOD_MCP_ELICITATION = "mcpServer/elicitation/request";
    static final String METHOD_THREAD_TOKEN_USAGE = "thread/tokenUsage/updated";
    static final String METHOD_ACCOUNT_RATE_LIMITS_UPDATED = "account/rateLimits/updated";
    /**
     * Placeholder path for a change entry Codex sent without a usable one. Such an entry is kept rather than dropped —
     * the user must be told the set contains something we could not identify, and the count in the log must match the
     * count Codex asked about. It carries null proposed content, so the review declines the whole set and names it.
     */
    static final String UNNAMED_CHANGE_PATH = "(no path supplied)";

    /**
     * Extracts {@code item.changes} from an {@code item/started} notification when the item is a fileChange, for
     * caching by item id. Returns null for any other item type or malformed payload.
     */
    static JsonArray extractFileChangeChanges(JsonObject params) {
        if (params == null || !params.has(CodexJsonKeyEnum.ITEM.key()) || !params.get(CodexJsonKeyEnum.ITEM.key()).isJsonObject()) {
            return null;
        }
        JsonObject item = params.getAsJsonObject(CodexJsonKeyEnum.ITEM.key());
        String type = item.has(CodexJsonKeyEnum.TYPE.key()) && item.get(CodexJsonKeyEnum.TYPE.key()).isJsonPrimitive() ? item.get(CodexJsonKeyEnum.TYPE.key()).getAsString() : null;
        if (!"fileChange".equals(type)) {
            return null;
        }
        return item.has(CodexJsonKeyEnum.CHANGES.key()) && item.get(CodexJsonKeyEnum.CHANGES.key()).isJsonArray() ? item.getAsJsonArray(CodexJsonKeyEnum.CHANGES.key()) : null;
    }

    static String extractItemId(JsonObject params) {
        if (params == null || !params.has(CodexJsonKeyEnum.ITEM.key()) || !params.get(CodexJsonKeyEnum.ITEM.key()).isJsonObject()) {
            return null;
        }
        JsonObject item = params.getAsJsonObject(CodexJsonKeyEnum.ITEM.key());
        return item.has(CodexJsonKeyEnum.ID.key()) && item.get(CodexJsonKeyEnum.ID.key()).isJsonPrimitive() ? item.get(CodexJsonKeyEnum.ID.key()).getAsString() : null;
    }

    /**
     * {@code turn/started}/{@code turn/completed} both carry {@code turn.status} (design doc:
     * {@code TurnStartedNotification}/{@code TurnCompletedNotification} schemas). Returns null on any unexpected shape.
     */
    static String extractTurnStatus(JsonObject params) {
        if (params == null || !params.has(CodexJsonKeyEnum.TURN.key()) || !params.get(CodexJsonKeyEnum.TURN.key()).isJsonObject()) {
            return null;
        }
        JsonObject turn = params.getAsJsonObject(CodexJsonKeyEnum.TURN.key());
        return turn.has(CodexJsonKeyEnum.STATUS.key()) && turn.get(CodexJsonKeyEnum.STATUS.key()).isJsonPrimitive() ? turn.get(CodexJsonKeyEnum.STATUS.key()).getAsString() : null;
    }

    static String extractTurnErrorMessage(JsonObject params) {
        if (params == null || !params.has(CodexJsonKeyEnum.TURN.key()) || !params.get(CodexJsonKeyEnum.TURN.key()).isJsonObject()) {
            return null;
        }
        JsonObject turn = params.getAsJsonObject(CodexJsonKeyEnum.TURN.key());
        if (!turn.has(CodexJsonKeyEnum.ERROR.key()) || !turn.get(CodexJsonKeyEnum.ERROR.key()).isJsonObject()) {
            return null;
        }
        JsonObject error = turn.getAsJsonObject(CodexJsonKeyEnum.ERROR.key());
        return error.has(CodexJsonKeyEnum.MESSAGE.key()) && error.get(CodexJsonKeyEnum.MESSAGE.key()).isJsonPrimitive() ? error.get(CodexJsonKeyEnum.MESSAGE.key()).getAsString() : null;
    }

    /**
     * Extracts the {@code codexErrorInfo} discriminant from a turn error, if present and a plain string (e.g.
     * {@code "unauthorized"}, {@code "contextWindowExceeded"}). Returns null when absent, null in JSON, or a structured
     * variant (object shape).
     */
    static String extractTurnCodexErrorInfo(JsonObject params) {
        if (params == null || !params.has(CodexJsonKeyEnum.TURN.key()) || !params.get(CodexJsonKeyEnum.TURN.key()).isJsonObject()) {
            return null;
        }
        JsonObject turn = params.getAsJsonObject(CodexJsonKeyEnum.TURN.key());
        if (!turn.has(CodexJsonKeyEnum.ERROR.key()) || !turn.get(CodexJsonKeyEnum.ERROR.key()).isJsonObject()) {
            return null;
        }
        JsonObject error = turn.getAsJsonObject(CodexJsonKeyEnum.ERROR.key());
        if (!error.has(CodexJsonKeyEnum.CODEX_ERROR_INFO.key()) || !error.get(CodexJsonKeyEnum.CODEX_ERROR_INFO.key()).isJsonPrimitive()) {
            return null;
        }
        return error.get(CodexJsonKeyEnum.CODEX_ERROR_INFO.key()).getAsString();
    }

    /**
     * Builds a short, single-line summary of a cached fileChange's {@code
     * changes[]} for {@link ConfirmEvent#displayText()} — not the raw unified diff. {@code ConfirmPanel} renders
     * {@code displayText} as one bold HTML line, so a multi-line diff dump would collapse unreadably rather than
     * display as intended.
     *
     * <p>
     * Describes ONE file, never a count. It used to end with "Codex wants to modify N files" for a larger set, which
     * was the blind bulk approval the multi-file review replaced; every array larger than one now goes to that review
     * and cannot reach here. Deliberately no plural form remains — text offering to approve N unseen files is the thing
     * this feature exists to remove, and leaving it would let a nearby edit revive it by accident.</p>
     */
    static String summarizeFileChanges(JsonArray changes) {
        if (changes != null && changes.size() == 1 && changes.get(0).isJsonObject()) {
            JsonObject c = changes.get(0).getAsJsonObject();
            String path = c.has(CodexJsonKeyEnum.PATH.key()) && c.get(CodexJsonKeyEnum.PATH.key()).isJsonPrimitive() ? c.get(CodexJsonKeyEnum.PATH.key()).getAsString() : "a file";
            return "Codex wants to modify " + path;
        }
        return "Codex wants to modify a file";
    }

    static String firstChangedPath(JsonArray changes) {
        if (changes == null || changes.size() == 0 || !changes.get(0).isJsonObject()) {
            return null;
        }
        JsonObject c = changes.get(0).getAsJsonObject();
        return c.has(CodexJsonKeyEnum.PATH.key()) && c.get(CodexJsonKeyEnum.PATH.key()).isJsonPrimitive() ? c.get(CodexJsonKeyEnum.PATH.key()).getAsString() : null;
    }

    /**
     * {@code codexErrorInfo == "unauthorized"} is the schema-documented plain-string variant, but a live probe against
     * a real 401 (missing bearer token on the Responses websocket) showed {@code codexErrorInfo} collapse to the
     * generic string {@code "other"} by the time {@code turn/completed} fires — the only reliable signal left at that
     * point is the {@code error.message} text itself ("unexpected status 401 Unauthorized: ..."). Check both: the
     * schema path in case some other auth failure genuinely reports it, and the message-text path for the one this
     * project has actually observed.
     */
    private static String buildFailedMessage(JsonObject params) {
        String codexErrorInfo = extractTurnCodexErrorInfo(params);
        String message = extractTurnErrorMessage(params);
        boolean isAuthFailure = "unauthorized".equals(codexErrorInfo)
                || (message != null && message.contains("401 Unauthorized"));
        if (isAuthFailure) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "codex turn failed: mapped to authentication error (codexErrorInfo={0}, message={1})",
                        new Object[]{codexErrorInfo, message});
            }
            return "Authentication required. Run 'codex login' in a terminal, then retry.";
        }
        return message != null ? message : "Turn failed";
    }

    /**
     * Maps a completed {@link PermissionDecision} future to a Codex approval string. Used by both the
     * {@code decision}-field methods (file-change, command) and the {@code action}-field method (MCP elicitation); the
     * string value is identical.
     *
     * <ul>
     * <li>{@code "accept"} — user approved
     * <li>{@code "decline"} — user deliberately rejected; agent continues the turn
     * <li>{@code "cancel"} — exceptional completion (panel closed, process interrupted); agent interrupts the turn
     * immediately
     * </ul>
     */
    private static String approvalDecision(PermissionDecision decision, Throwable ex) {
        if (ex != null) {
            return "cancel";
        }
        return decision != null && decision.allow() ? "accept" : "decline";
    }

    /**
     * Applies a Codex unified-diff hunk (the {@code diff} field of an {@code update} change) to {@code original} file
     * content. The hunk uses standard unified-diff format but carries no {@code ---}/{@code +++} header lines — those
     * are prepended here so that {@link UnifiedDiffUtils#parseUnifiedDiff} can locate hunk boundaries.
     *
     * <p>ONLY for {@code update}. An {@code add} change's {@code diff} field is not a diff at all — see
     * {@link #proposedContentFor}.</p>
     *
     * <p>THE TRAILING NEWLINE IS STRIPPED FROM THE HUNK, and that is load-bearing. {@code split("\n", -1)} keeps
     * trailing empty strings, so a hunk ending in a newline yields one final "" element; the parser reads that as an
     * extra CONTEXT LINE expecting an empty line, the file has real text there, and the patch fails with
     * CONTENT_DOES_NOT_MATCH_TARGET. Every Codex hunk ends with a newline, so this failed for every single-file edit
     * for as long as this method has existed — silently, because the caller fell back to a blind confirm. Confirmed
     * against a hunk captured from a live run on 2026-08-29.</p>
     *
     * <p>A genuinely blank context line is " " (a space) in unified-diff format, never "", so stripping exactly one
     * trailing newline cannot discard real content. The ORIGINAL keeps {@code split("\n", -1)} untouched: there the
     * trailing empty element represents the file's final newline, and dropping it would strip that newline on every
     * write.</p>
     *
     * @throws PatchFailedException if the hunk does not match the file content (stale read, CRLF vs LF, whitespace
     * mismatch)
     */
    static String applyUnifiedDiff(String original, String diffHunk) throws PatchFailedException {
        String hunk = diffHunk.endsWith("\n") ? diffHunk.substring(0, diffHunk.length() - 1) : diffHunk;
        List<String> diffLines = Arrays.asList(
                ("--- original\n+++ revised\n" + hunk).split("\n", -1));
        Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(diffLines);
        List<String> originalLines = Arrays.asList(original.split("\n", -1));
        List<String> patchedLines = DiffUtils.patch(originalLines, patch);
        return String.join("\n", patchedLines);
    }

    /**
     * Turns Codex's {@code changes[]} into the neutral, ordered change set the review consumes.
     *
     * <p>
     * Order is Codex's, never sorted: it reflects how the model sequenced its own work, which is the order that reads
     * coherently when reviewing.</p>
     *
     * <p>
     * Every entry becomes an item, including ones we cannot render. A change whose proposed content cannot be produced
     * — no path, no diff, unreadable file, or a hunk that will not apply — becomes an item with null proposed content
     * rather than being dropped or answered separately. The review turns that into a whole-set decline that names the
     * file. Dropping it instead would let the user approve a set smaller than the one Codex is about to write, which is
     * the worst available outcome.</p>
     */
    private static List<MultiPermissionItem> buildChangeSet(JsonArray changes) {
        List<MultiPermissionItem> items = new ArrayList<>(changes.size());
        for (JsonElement element : changes) {
            if (!element.isJsonObject()) {
                items.add(new MultiPermissionItem(UNNAMED_CHANGE_PATH, null));
                continue;
            }
            JsonObject change = element.getAsJsonObject();
            String filePath = change.has(CodexJsonKeyEnum.PATH.key()) && change.get(CodexJsonKeyEnum.PATH.key()).isJsonPrimitive()
                    ? change.get(CodexJsonKeyEnum.PATH.key()).getAsString() : null;
            String diffHunk = change.has(CodexJsonKeyEnum.DIFF.key()) && change.get(CodexJsonKeyEnum.DIFF.key()).isJsonPrimitive()
                    ? change.get(CodexJsonKeyEnum.DIFF.key()).getAsString() : null;
            items.add(new MultiPermissionItem(
                    filePath != null && !filePath.isBlank() ? filePath : UNNAMED_CHANGE_PATH,
                    proposedContentFor(filePath, diffHunk, changeKind(change), changeMovePath(change))));
        }
        return items;
    }

    /**
     * Why an approval request was refused outright, or null when every change in it can be reviewed.
     *
     * <p>Returned to Codex as the message of a JSON-RPC error rather than as a decline, because a decline cannot carry
     * one: FileChangeRequestApprovalResponse has a {@code decision} field and nothing else. A silent decline tells the
     * model only that the answer was no, so its rational next move is to retry the same unsupported patch. The error
     * channel is the only way to say why, and naming the path and the kind lets it act on this one specifically.</p>
     */
    static String unsupportedChangeReason(JsonArray changes) {
        if (changes == null) {
            return null;
        }
        for (JsonElement element : changes) {
            if (!element.isJsonObject()) {
                return "This client can only review 'add' and in-place 'update' file changes. One entry in this patch"
                        + " is not a change object at all (" + element + "), so it could not be identified and nothing"
                        + " was applied.";
            }
            JsonObject change = element.getAsJsonObject();
            String path = change.has(CodexJsonKeyEnum.PATH.key()) && change.get(CodexJsonKeyEnum.PATH.key()).isJsonPrimitive()
                    ? change.get(CodexJsonKeyEnum.PATH.key()).getAsString() : UNNAMED_CHANGE_PATH;
            String kind = changeKind(change);
            if (KIND_ADD.equals(kind)) {
                continue;
            }
            if (KIND_UPDATE.equals(kind)) {
                String movePath = changeMovePath(change);
                if (movePath == null || movePath.isBlank()) {
                    continue;
                }
                return "This client can only review in-place 'add' and 'update' file changes, and " + path
                        + " is an 'update' that also renames the file to " + movePath
                        + ". A rename cannot be shown in its diff review, so nothing was applied."
                        + " Do the rename as a separate step and send the content change on its own.";
            }
            if (KIND_DELETE.equals(kind)) {
                return "This client can only review 'add' and in-place 'update' file changes, and " + path
                        + " is a 'delete'. Deletions cannot be shown in its diff review, so nothing was applied."
                        + " Do the deletion as a separate step (for example with a shell command the user can approve)"
                        + " and keep add/update changes in the patch.";
            }
            // Neither on the allowlist nor a kind we have a specific message for. The raw value goes in the message
            // verbatim: this string is how we find out Codex shipped a kind we have never seen, so it has to be
            // something a user can paste into a bug report.
            return "This client can only review 'add' and in-place 'update' file changes, and " + path
                    + " arrived with a change kind it does not recognise: " + describeKind(change)
                    + ". Nothing was applied. If this is a new Codex change kind, this client needs updating to"
                    + " support it.";
        }
        return null;
    }

    /**
     * The change's {@code kind} exactly as it arrived, for an error message a human can act on: the raw JSON when the
     * field is present, or "absent" when it is missing. The schema makes it required, so "absent" is itself a finding.
     */
    private static String describeKind(JsonObject change) {
        if (change == null || !change.has(CodexJsonKeyEnum.KIND.key())) {
            return "absent";
        }
        return change.get(CodexJsonKeyEnum.KIND.key()).toString();
    }

    /** A change that creates a file: no prior content, an all-additions hunk. */
    static final String KIND_ADD = "add";
    /** A change that removes a file. */
    static final String KIND_DELETE = "delete";
    /** A change that edits a file in place, or — with move_path — also renames it. */
    static final String KIND_UPDATE = "update";

    /**
     * The {@code type} out of a change's {@code kind}, or null when absent or malformed.
     *
     * <p>The schema makes {@code kind} an OBJECT — {@code {"type":"add"}} — not a bare string, so that is what is read
     * first. A plain string is accepted as well: it costs one branch and means a variant that flattens the field does
     * not silently read as "kind unknown", which would send every change down the update path.</p>
     */
    static String changeKind(JsonObject change) {
        if (change == null || !change.has(CodexJsonKeyEnum.KIND.key())) {
            return null;
        }
        JsonElement kind = change.get(CodexJsonKeyEnum.KIND.key());
        if (kind.isJsonObject()) {
            JsonObject asObject = kind.getAsJsonObject();
            return asObject.has(CodexJsonKeyEnum.TYPE.key()) && asObject.get(CodexJsonKeyEnum.TYPE.key()).isJsonPrimitive()
                    ? asObject.get(CodexJsonKeyEnum.TYPE.key()).getAsString() : null;
        }
        return kind.isJsonPrimitive() ? kind.getAsString() : null;
    }

    /**
     * The destination path when an {@code update} is also a rename, or null when the file stays where it is.
     */
    static String changeMovePath(JsonObject change) {
        if (change == null || !change.has(CodexJsonKeyEnum.KIND.key())
                || !change.get(CodexJsonKeyEnum.KIND.key()).isJsonObject()) {
            return null;
        }
        JsonObject kind = change.get(CodexJsonKeyEnum.KIND.key()).getAsJsonObject();
        return kind.has(CodexJsonKeyEnum.MOVE_PATH.key()) && kind.get(CodexJsonKeyEnum.MOVE_PATH.key()).isJsonPrimitive()
                ? kind.get(CodexJsonKeyEnum.MOVE_PATH.key()).getAsString() : null;
    }

    /**
     * Applies one change's hunk to produce the content the user will review, or null when we cannot honestly show what
     * the change does. Null is a normal result here, not an error to propagate: the review turns it into a decline the
     * user can read, which keeps one code path instead of two.
     *
     * <p>Driven by the change's {@code kind}, not by whether a file read happens to succeed. The protocol states what
     * each change is; inferring it from a failed read conflates cases that must stay apart:</p>
     *
     * <ul>
     * <li><b>add</b> — the file does not exist yet, so the original is EMPTY and the all-additions hunk applies to
     * that. Renderable: the diff panel shows it as a new file. Reading first and treating the failure as unrenderable
     * declined whole batches for the ordinary act of creating a file.</li>
     * <li><b>update</b> — read the file and apply the hunk, as before. A file that exists but cannot be READ stays
     * unrenderable: an unreadable file must never render as a full-file addition, or the user approves replacing
     * content they could not see. An update whose file is MISSING is equally unrenderable — the protocol said it was
     * there, so something is wrong and guessing is not the answer.</li>
     * <li><b>delete</b>, <b>update with move_path</b>, and <b>any kind not on the allowlist</b> — returns null
     * deliberately. In normal operation these never reach here: {@link #unsupportedChangeReason} refuses the whole
     * request with a JSON-RPC error before anything is raised. The checks stay as the second layer, so a future caller
     * that builds a change set directly cannot render one of them as an ordinary edit.</li>
     * </ul>
     */
    private static String proposedContentFor(String filePath, String diffHunk, String kind, String movePath) {
        if (filePath == null || filePath.isBlank() || diffHunk == null || diffHunk.isBlank()) {
            return null;
        }
        // A deletion is not an edit, and this item shape can only express "here is the file's new content". Applying
        // the hunk would show the file being EMPTIED, the user would approve that, and Codex would then delete it —
        // a review of the wrong operation that silently succeeds. Refusing is honest; faking it is not.
        if (KIND_DELETE.equals(kind)) {
            LOG.log(Level.INFO, "Codex change is a deletion, which this review cannot display: {0}", filePath);
            return null;
        }
        // A rename carries a content diff we could render, but nothing in this item shape can tell the user the file
        // is also MOVING. Approving a diff that silently relocates the file is the same class of surprise.
        if (movePath != null && !movePath.isBlank()) {
            LOG.log(Level.INFO, "Codex change renames {0} to {1}, which this review cannot display",
                    new Object[]{filePath, movePath});
            return null;
        }
        // Allowlist, not a blocklist. Anything that is not explicitly add or update renders nothing — including a kind
        // that is absent, malformed, or one Codex has not shipped yet. Falling back to "treat it as an edit" would be
        // safe only while update is the only thing an unrecognised shape could mean; the first new kind would then be
        // shown to the user as a content diff for an operation that is not one.
        if (!KIND_ADD.equals(kind) && !KIND_UPDATE.equals(kind)) {
            LOG.log(Level.INFO, "Codex change kind is not one this review supports ({0}): {1}",
                    new Object[]{kind == null ? "absent" : kind, filePath});
            return null;
        }
        // FOR AN add, THE "diff" FIELD IS NOT A DIFF. It carries the complete raw file content — no @@ header, no +
        // prefixes, no diff syntax at all. The field name lies for this kind; the non-v2 schema is honest about it and
        // calls the same data AddFileChange.content. Feeding a whole Java source file to a unified-diff parser is what
        // produced POSITION_OUT_OF_TARGET for every new file in the first live run. Verbatim from a capture on
        // 2026-08-29. So: use it directly, parse nothing, apply it to nothing.
        if (KIND_ADD.equals(kind)) {
            return diffHunk;
        }
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                LOG.log(Level.WARNING, "Codex update targets a file that is not there: {0}", filePath);
                return null;
            }
            String original = Files.readString(path, RefactoringProvider.resolveCharset(filePath));
            return applyUnifiedDiff(original, diffHunk);
        }
        // RuntimeException covers InvalidPathException from Path.of — a malformed path must decline the set, not
        // escape into the JSON-RPC dispatch as an internal error.
        catch (IOException | PatchFailedException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not render Codex diff for {0}: {1}",
                    new Object[]{filePath, e.getMessage()});
            return null;
        }
    }

    private final AiProcessEventListener listener;
    private final Runnable disconnectCallback;
    /**
     * item id -> changes[] from item/started, consumed by the matching approval request.
     *
     * <p>A FUTURE per item, not the array itself, because the two sides arrive on DIFFERENT executors and nothing
     * orders them: notifications are drained by the single {@code codex-notify} thread while approvals run on the
     * {@code codex-dispatch} pool. The old map held the array and the approval did a plain {@code remove}, so an
     * approval that won the race read null and fell through to the blind confirm — observed live in one run out of
     * three. The notify thread also carries every streaming text delta, so item/started can queue behind a burst while
     * the approval starts immediately on a fresh dispatch thread.</p>
     *
     * <p>Either side may create the entry. Whichever arrives first installs the future; the notification completes it
     * and the approval composes on it, so the ordering assumption is removed rather than merely narrowed.</p>
     */
    private final ConcurrentHashMap<String, CompletableFuture<JsonArray>> fileChangeCache = new ConcurrentHashMap<>();
    /**
     * Outstanding approval decision future — at most one per turn (Codex approvals are sequential: each blocks the turn
     * until answered). Written by the three raise* methods; read by {@link #cancelPendingPermissions()} on
     * stop/interrupt.
     */
    private volatile CompletableFuture<PermissionDecision> pendingPermission;

    CodexAppServerHandler(AiProcessEventListener listener, Runnable disconnectCallback) {
        this.listener = listener;
        this.disconnectCallback = disconnectCallback;
    }

    /**
     * Cache cleared at the start of each turn — item ids are turn-scoped.
     */
    void onTurnStarting() {
        fileChangeCache.clear();
    }

    /**
     * Test seam: whether every item's entry has been released. A consumed entry must not linger and a timed-out one
     * must not leak, or a long turn accumulates one per file change it ever made.
     */
    boolean fileChangeCacheIsEmpty() {
        return fileChangeCache.isEmpty();
    }

    /**
     * Test seam: how long an approval waits for its {@code item/started}. Production uses
     * {@link CodexTimeoutEnum#FILE_CHANGE_CACHE_WAIT_MILLIS}; a test shortens it so the timeout path can be exercised
     * without a real ten-second pause. Mirrors the seam {@code GrokModelDiscovery} uses for its own bound.
     */
    private volatile long fileChangeWaitMillis = CodexTimeoutEnum.FILE_CHANGE_CACHE_WAIT_MILLIS.millis();

    void setFileChangeWaitMillisForTest(long millis) {
        this.fileChangeWaitMillis = millis;
    }

    /**
     * Cancels any outstanding approval dialog when the turn is stopped or interrupted. Completes the pending future
     * exceptionally, which routes through the existing {@link #approvalDecision} path and replies {@code "cancel"} to
     * Codex — immediately interrupting the turn rather than leaving the dialog up and the turn wedged. Safe to call
     * when no approval is in flight.
     */
    void cancelPendingPermissions() {
        CompletableFuture<PermissionDecision> pf = pendingPermission;
        if (pf != null) {
            pendingPermission = null;
            pf.completeExceptionally(new CancellationException("turn cancelled"));
        }
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        // item/started notifications for an mcpToolCall item carry the tool's
        // arguments verbatim, including this session's secretKey — see
        // announceToolCall's javadoc for the confirmed live shape.
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "codex notification: {0} {1}",
                    new Object[]{method, McpHookServerUtil.redactAllSecrets(String.valueOf(params))});
        }
        try {
            switch (method) {
                case METHOD_AGENT_MESSAGE_DELTA:
                    handleAgentMessageDelta(params);
                    break;
                case METHOD_ITEM_STARTED:
                    handleItemStarted(params);
                    break;
                case METHOD_TURN_STARTED:
                    listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.THINKING, ""));
                    break;
                case METHOD_TURN_COMPLETED:
                    handleTurnCompleted(params);
                    break;
                case METHOD_THREAD_TOKEN_USAGE:
                    handleTokenUsageUpdated(params);
                    break;
                case METHOD_ACCOUNT_RATE_LIMITS_UPDATED:
                    handleRateLimitsUpdated(params);
                    break;
                default:
                    break; // Unrecognised notification — ignore silently, never throw.
            }
        }
        catch (Throwable t) {
            // This executor drains every inbound notification FIFO, so a throw
            // escaping one malformed payload would silently kill the worker and
            // drop all later traffic until the next connection. Log and keep
            // draining instead.
            LOG.log(Level.WARNING, "codex notification handler threw for " + method, t);
        }
    }

    private void handleAgentMessageDelta(JsonObject params) {
        String delta = params.has(CodexJsonKeyEnum.DELTA.key()) && params.get(CodexJsonKeyEnum.DELTA.key()).isJsonPrimitive() ? params.get(CodexJsonKeyEnum.DELTA.key()).getAsString() : "";
        String turnId = params.has(CodexJsonKeyEnum.TURN_ID.key()) && params.get(CodexJsonKeyEnum.TURN_ID.key()).isJsonPrimitive() ? params.get(CodexJsonKeyEnum.TURN_ID.key()).getAsString() : null;
        listener.onAiProcessEvent(new TextDeltaEvent(delta, turnId));
    }

    private void handleItemStarted(JsonObject params) {
        announceToolCall(params);
        JsonArray changes = extractFileChangeChanges(params);
        if (changes == null) {
            return;
        }
        String itemId = extractItemId(params);
        if (itemId != null) {
            // computeIfAbsent, not put: an approval that arrived first has already installed the future and is waiting
            // on it. Completing it is what hands the changes over.
            fileChangeCache.computeIfAbsent(itemId, k -> new CompletableFuture<>()).complete(changes);
        }
    }

    /**
     * Raises a ToolUseEvent for an {@code item/started} notification describing an MCP tool call, so the UI knows a
     * tool ran between two runs of agent text.
     *
     * <p>
     * That event is what sets AiTopComponent's {@code pendingNewlineBeforeText}, the only thing that separates the
     * model's narration either side of a tool call. Codex raised no such event, so the two blocks were appended to one
     * bubble verbatim and collided — a live transcript reads "...instruction now.At 00:17 the weather station...". The
     * blocks carry no trailing whitespace of their own, so the break has to be synthesised at the only point that knows
     * a tool ran.
     *
     * <p>
     * Field names taken from a live notification, not guessed: null null null null null null null null null null null null     {@code {"item":{"type":"mcpToolCall","tool":"ListAiSessions",
     * "server":"aicoder-nb-ki-plugin",...}}}. Kind.OTHER with a null path deliberately — {@code isFileModification()}
     * stays false so no diff panel is raised; file changes keep their own path below.
     */
    private void announceToolCall(JsonObject params) {
        if (params == null || !params.has(CodexJsonKeyEnum.ITEM.key()) || !params.get(CodexJsonKeyEnum.ITEM.key()).isJsonObject()) {
            return;
        }
        JsonObject item = params.getAsJsonObject(CodexJsonKeyEnum.ITEM.key());
        String type = item.has(CodexJsonKeyEnum.TYPE.key()) && item.get(CodexJsonKeyEnum.TYPE.key()).isJsonPrimitive()
                ? item.get(CodexJsonKeyEnum.TYPE.key()).getAsString() : null;
        if (!"mcpToolCall".equals(type)) {
            return;
        }
        String tool = item.has(CodexJsonKeyEnum.TOOL.key()) && item.get(CodexJsonKeyEnum.TOOL.key()).isJsonPrimitive()
                ? item.get(CodexJsonKeyEnum.TOOL.key()).getAsString() : "tool";
        listener.onAiProcessEvent(new ToolUseEvent(tool, null, null, null, ToolUseEvent.Kind.OTHER));
    }

    private void handleTokenUsageUpdated(JsonObject params) {
        if (params == null || !params.has(CodexJsonKeyEnum.TOKEN_USAGE.key()) || !params.get(CodexJsonKeyEnum.TOKEN_USAGE.key()).isJsonObject()) {
            return;
        }
        JsonObject tokenUsage = params.getAsJsonObject(CodexJsonKeyEnum.TOKEN_USAGE.key());
        long contextWindow = 0L;
        if (tokenUsage.has(CodexJsonKeyEnum.MODEL_CONTEXT_WINDOW.key()) && tokenUsage.get(CodexJsonKeyEnum.MODEL_CONTEXT_WINDOW.key()).isJsonPrimitive()) {
            contextWindow = tokenUsage.get(CodexJsonKeyEnum.MODEL_CONTEXT_WINDOW.key()).getAsLong();
        }
        if (!tokenUsage.has(CodexJsonKeyEnum.LAST.key()) || !tokenUsage.get(CodexJsonKeyEnum.LAST.key()).isJsonObject()) {
            return;
        }
        // `total` is the thread's lifetime token spend, which can exceed the
        // model context window many times over. `last` is the active turn's
        // context usage and is therefore comparable with modelContextWindow.
        JsonObject last = tokenUsage.getAsJsonObject(CodexJsonKeyEnum.LAST.key());
        long usedTokens = last.has(CodexJsonKeyEnum.TOTAL_TOKENS.key()) && last.get(CodexJsonKeyEnum.TOTAL_TOKENS.key()).isJsonPrimitive()
                ? last.get(CodexJsonKeyEnum.TOTAL_TOKENS.key()).getAsLong() : 0L;
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "codex tokenUsage: used={0} contextWindow={1}",
                    new Object[]{usedTokens, contextWindow});
        }
        listener.onAiProcessEvent(new CodexTokenUsageEvent(usedTokens, contextWindow));
    }

    private void handleRateLimitsUpdated(JsonObject params) {
        if (params == null || !params.has(CodexJsonKeyEnum.RATE_LIMITS.key()) || !params.get(CodexJsonKeyEnum.RATE_LIMITS.key()).isJsonObject()) {
            return;
        }
        JsonObject rateLimits = params.getAsJsonObject(CodexJsonKeyEnum.RATE_LIMITS.key());
        if (!rateLimits.has(CodexJsonKeyEnum.PRIMARY.key()) || !rateLimits.get(CodexJsonKeyEnum.PRIMARY.key()).isJsonObject()) {
            return;
        }
        JsonObject primary = rateLimits.getAsJsonObject(CodexJsonKeyEnum.PRIMARY.key());
        if (!primary.has(CodexJsonKeyEnum.USED_PERCENT.key()) || !primary.get(CodexJsonKeyEnum.USED_PERCENT.key()).isJsonPrimitive()) {
            return;
        }
        double usedPercent = primary.get(CodexJsonKeyEnum.USED_PERCENT.key()).getAsDouble();
        long windowDurationMins = primary.has(CodexJsonKeyEnum.WINDOW_DURATION_MINS.key()) && primary.get(CodexJsonKeyEnum.WINDOW_DURATION_MINS.key()).isJsonPrimitive()
                ? primary.get(CodexJsonKeyEnum.WINDOW_DURATION_MINS.key()).getAsLong() : 0L;
        long resetsAt = primary.has(CodexJsonKeyEnum.RESETS_AT.key()) && primary.get(CodexJsonKeyEnum.RESETS_AT.key()).isJsonPrimitive()
                ? primary.get(CodexJsonKeyEnum.RESETS_AT.key()).getAsLong() : 0L;
        CodexRateLimitEvent event = new CodexRateLimitEvent(usedPercent, windowDurationMins, resetsAt);
        listener.onAiProcessEvent(event);
        CodexAiImplementation.publishRateLimit(event);
    }

    private void handleTurnCompleted(JsonObject params) {
        String status = extractTurnStatus(params);
        if ("failed".equals(status)) {
            String message = buildFailedMessage(params);
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, message));
            return;
        }
        listener.onAiProcessEvent(new TurnCompleteEvent());
    }

    @Override
    public CompletableFuture<JsonObject> onServerRequest(String method, JsonObject params) {
        try {
            switch (method) {
                case METHOD_COMMAND_EXECUTION_APPROVAL:
                    return handleCommandExecutionApproval(params);
                case METHOD_FILE_CHANGE_APPROVAL:
                    return handleFileChangeApproval(params);
                case METHOD_MCP_ELICITATION:
                    return handleMcpElicitationRequest(params);
                default:
                    // Unrecognised server request — Slice 4 adds siblings (MCP tool call,
                    // network access). Refuse cleanly rather than leaving Codex's turn
                    // blocked forever on an answer this slice cannot give.
                    return CompletableFuture.failedFuture(
                            new UnsupportedOperationException("Unhandled Codex server request: " + method));
            }
        }
        catch (Throwable t) {
            // A handler throwing synchronously must still yield an answer: this
            // failed future routes through CodexJsonRpcClient's exceptionally
            // path as a JSON-RPC INTERNAL_ERROR response, so Codex's approval
            // request is never left hanging until its own timeout wedges the
            // turn. The throwable itself is logged here and deliberately kept
            // off the wire.
            LOG.log(Level.WARNING, "codex server request handler threw for " + method, t);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Internal error handling " + method));
        }
    }

    private CompletableFuture<JsonObject> handleCommandExecutionApproval(JsonObject params) {
        String reason = params.has(CodexJsonKeyEnum.REASON.key()) && params.get(CodexJsonKeyEnum.REASON.key()).isJsonPrimitive()
                ? params.get(CodexJsonKeyEnum.REASON.key()).getAsString() : null;
        String command = params.has(CodexJsonKeyEnum.COMMAND.key()) && params.get(CodexJsonKeyEnum.COMMAND.key()).isJsonPrimitive()
                ? params.get(CodexJsonKeyEnum.COMMAND.key()).getAsString() : null;
        String displayText = reason != null ? reason
                : command != null ? "Codex wants to run: " + command
                        : "Codex wants to run a command";
        return raiseConfirmAndReply("Command", displayText, null);
    }

    /**
     * Waits — without blocking the dispatch thread — for this item's {@code item/started} to be drained, then answers.
     *
     * <p>The approval carries no diff of its own (design doc §0a); the content arrives separately under the same item
     * id. Those two are handled on different executors and nothing orders them, so this COMPOSES on a future rather
     * than reading a map that may not be populated yet. Returning a chained future keeps the dispatch thread free:
     * sleeping or polling here would stall every other inbound message, which would be worse than the bug.</p>
     *
     * <p>Bounded by {@link CodexTimeoutEnum#FILE_CHANGE_CACHE_WAIT_MILLIS}; on expiry the changes are treated as absent
     * and the existing blind-confirm fallback runs, logged distinctly so "raced and lost" is never confused with
     * "genuinely no changes".</p>
     */
    private CompletableFuture<JsonObject> handleFileChangeApproval(JsonObject params) {
        String itemId = params.has(CodexJsonKeyEnum.ITEM_ID.key()) && params.get(CodexJsonKeyEnum.ITEM_ID.key()).isJsonPrimitive()
                ? params.get(CodexJsonKeyEnum.ITEM_ID.key()).getAsString() : null;
        if (itemId == null) {
            return respondToFileChange(null, null);
        }
        // computeIfAbsent, not get: if the approval won the race the entry does not exist yet, and installing the
        // future here is what lets the later notification hand the changes over.
        CompletableFuture<JsonArray> pendingChanges
                = fileChangeCache.computeIfAbsent(itemId, k -> new CompletableFuture<>());
        // copy() so the timeout completes only THIS wait — completing the cached future itself would poison it for a
        // notification still on its way and turn a slow arrival into a permanent null.
        long waitMillis = fileChangeWaitMillis;
        return pendingChanges.copy()
                .completeOnTimeout(null, waitMillis, TimeUnit.MILLISECONDS)
                .thenCompose(changes -> {
                    // Removed on BOTH routes, so a consumed entry cannot linger and a timed-out one cannot leak. A
                    // notification arriving afterwards completes an orphan nothing references, which is collected.
                    fileChangeCache.remove(itemId);
                    if (changes == null) {
                        LOG.log(Level.WARNING,
                                "codex fileChange approval: item/started for {0} did not arrive within {1} ms — "
                                + "falling back to the blind confirm. The diff was unavailable, NOT absent.",
                                new Object[]{itemId, waitMillis});
                    }
                    return respondToFileChange(changes, itemId);
                });
    }

    private CompletableFuture<JsonObject> respondToFileChange(JsonArray changes, String itemId) {
        // Refuse kinds this client cannot show, BEFORE anything is raised. No panel, no review, no log line claiming a
        // decision the user never made — and a reason on the wire, which a decline cannot carry. Applies to a lone
        // change as much as to a batch: a deletion that is unreviewable in a set of three is not made reviewable by
        // arriving on its own, and letting it through the blind confirm would be the one silently approvable route
        // left. pendingPermission is deliberately untouched — no future is created here, so there is no slot to leak.
        String unsupported = unsupportedChangeReason(changes);
        if (unsupported != null) {
            LOG.log(Level.INFO, "codex fileChange approval refused: {0}", unsupported);
            listener.onAiProcessEvent(new SystemNotificationEvent(
                    "Codex requested a file change this review cannot display, so nothing was applied. "
                    + unsupported));
            return CompletableFuture.failedFuture(new UnsupportedOperationException(unsupported));
        }

        // Multi-file change: one review over the whole ordered set. Before this existed, every multi-file edit fell
        // through to the blind ConfirmEvent below — one line saying "Codex wants to modify 3 files", Yes/No, no diff —
        // which is the approval-sight-unseen this feature removes.
        if (changes != null && changes.size() > 1) {
            List<MultiPermissionItem> items = buildChangeSet(changes);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "codex fileChange approval: multi-file review path ({0} files)", items.size());
            }
            return raiseMultiPermissionAndReply(items);
        }

        // Single-file change with a diff: upgrade to PermissionEvent so the user
        // reviews Codex edits in the same diff panel as the plugin's own edits.
        if (changes != null && changes.size() == 1 && changes.get(0).isJsonObject()) {
            JsonObject c = changes.get(0).getAsJsonObject();
            String fp = c.has(CodexJsonKeyEnum.PATH.key()) && c.get(CodexJsonKeyEnum.PATH.key()).isJsonPrimitive() ? c.get(CodexJsonKeyEnum.PATH.key()).getAsString() : null;
            String diffHunk = c.has(CodexJsonKeyEnum.DIFF.key()) && c.get(CodexJsonKeyEnum.DIFF.key()).isJsonPrimitive() ? c.get(CodexJsonKeyEnum.DIFF.key()).getAsString() : null;
            if (fp != null && diffHunk != null && !diffHunk.isBlank()) {
                // Same kind-driven rendering as a batch, so a single new file gets a real diff panel instead of the
                // blind Yes/No it used to fall through to, and a deletion or rename is not shown as a content edit.
                String proposed = proposedContentFor(fp, diffHunk, changeKind(c), changeMovePath(c));
                if (proposed != null) {
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO, "codex fileChange approval: diff panel path for {0}", fp);
                    }
                    return raisePermissionAndReply(fp, proposed);
                }
                // Nothing we can render honestly — fall through to the ConfirmEvent path below. proposedContentFor has
                // already logged why.
            }
        }

        // Fallback, reached only for a SINGLE change we could not turn into a diff — missing diff field, unreadable
        // file, or a patch that would not apply (stale content / CRLF / whitespace mismatch) — or for a request whose
        // cached changes[] is absent or empty.
        //
        // A multi-file set never arrives here: the branch above takes every array larger than one, and buildChangeSet
        // keeps malformed entries rather than shedding them, so the size cannot shrink on the way through. This path
        // therefore describes one file, never a count, and must not grow back into a bulk "modify N files" prompt —
        // approving a set sight unseen is the defect the multi-file review exists to remove.
        if (PluginSettings.isDebugJson()) {
            // "changes=0" here means the payload genuinely carried none. A request whose item/started never arrived is
            // logged at WARNING by the caller instead — the two used to be indistinguishable in the log, which is how
            // the race hid behind a bug that looked identical.
            LOG.log(Level.INFO, "codex fileChange approval: confirm path (changes={0}, itemId={1})",
                    new Object[]{changes != null ? changes.size() : 0, itemId});
        }
        String displayText = summarizeFileChanges(changes);
        String filePath = firstChangedPath(changes);
        return raiseConfirmAndReply("FileChange", displayText, filePath);
    }

    /**
     * Like {@link #raisePermissionAndReply} but for a whole ordered change set: ONE event, ONE future, ONE aggregate
     * reply. Same {@code decision} shape and the same decline-vs-cancel semantics — the review already carries that
     * distinction, so nothing about the mapping changes for a batch.
     */
    private CompletableFuture<JsonObject> raiseMultiPermissionAndReply(List<MultiPermissionItem> items) {
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        listener.onAiProcessEvent(new MultiPermissionEvent(items, decisionFuture));
        return decisionFuture.handle((decision, ex) -> {
            JsonObject result = new JsonObject();
            result.addProperty(CodexJsonKeyEnum.DECISION.key(), approvalDecision(decision, ex));
            return result;
        });
    }

    /**
     * Raises a {@link ConfirmEvent} and maps the eventual {@link PermissionDecision} to Codex's {@code decision} reply
     * shape (schema: FileChangeRequestApprovalResponse / CommandExecutionRequestApprovalResponse). Three distinct
     * values:
     * <ul>
     * <li>{@code "accept"} — user approved
     * <li>{@code "decline"} — user deliberately rejected; agent continues the turn
     * <li>{@code "cancel"} — future completed exceptionally (panel closed, process stopped); agent interrupts the turn
     * immediately
     * </ul>
     * Does not block the calling dispatch-thread — chain completes when the user answers.
     */
    private CompletableFuture<JsonObject> raiseConfirmAndReply(String toolName, String displayText, String filePath) {
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        listener.onAiProcessEvent(new ConfirmEvent(toolName, displayText, filePath, null, decisionFuture));
        return decisionFuture.handle((decision, ex) -> {
            JsonObject result = new JsonObject();
            result.addProperty(CodexJsonKeyEnum.DECISION.key(), approvalDecision(decision, ex));
            return result;
        });
    }

    /**
     * Like {@link #raiseConfirmAndReply} but raises a {@link PermissionEvent} so the user sees the full Accept/Reject
     * diff panel, identical to the plugin's own {@code ApplyEdit}/{@code WriteFile} review. Same {@code decision} reply
     * shape and decline-vs-cancel semantics.
     */
    private CompletableFuture<JsonObject> raisePermissionAndReply(String filePath, String proposed) {
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        listener.onAiProcessEvent(new PermissionEvent("Write", filePath, null, null, proposed, decisionFuture));
        return decisionFuture.handle((decision, ex) -> {
            JsonObject result = new JsonObject();
            result.addProperty(CodexJsonKeyEnum.DECISION.key(), approvalDecision(decision, ex));
            return result;
        });
    }

    /**
     * Handles {@code mcpServer/elicitation/request} — gating IDE MCP tool calls that Codex routes through this approval
     * channel. Unlike file-change/command approvals, the response field is {@code "action"} (not {@code "decision"}),
     * and the vocabulary has no {@code acceptForSession} — only {@code accept | decline | cancel} (schema:
     * McpServerElicitationRequestResponse).
     *
     * <p>
     * NOTE: {@code persist:["session","always"]} in {@code _meta} signals auto-accept intent. This is NOT wired here —
     * the decision to expose auto-accept to the user must be made deliberately (flagged for a future slice).
     */
    private CompletableFuture<JsonObject> handleMcpElicitationRequest(JsonObject params) {
        String message = params.has(CodexJsonKeyEnum.MESSAGE.key()) && params.get(CodexJsonKeyEnum.MESSAGE.key()).isJsonPrimitive()
                ? params.get(CodexJsonKeyEnum.MESSAGE.key()).getAsString() : null;
        String serverName = params.has(CodexJsonKeyEnum.SERVER_NAME.key()) && params.get(CodexJsonKeyEnum.SERVER_NAME.key()).isJsonPrimitive()
                ? params.get(CodexJsonKeyEnum.SERVER_NAME.key()).getAsString() : null;
        String displayText = message != null ? message
                : serverName != null ? "MCP server \'" + serverName + "\' requests approval"
                        : "MCP server requests approval";
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "codex mcpServer/elicitation/request: serverName={0}", serverName);
        }
        CompletableFuture<PermissionDecision> decisionFuture = new CompletableFuture<>();
        pendingPermission = decisionFuture;
        // Never auto-accept. An elicitation is some MCP server asking the user a
        // question of its own devising; this plugin gates its own tools and can
        // vouch for nothing else, and both paths are null so there is no subject
        // to show. Auto-accepting would answer, on the user's behalf, a question
        // neither they nor this code has seen.
        listener.onAiProcessEvent(new ConfirmEvent("McpElicitation", displayText, null, null,
                decisionFuture, true));
        return decisionFuture.handle((decision, ex) -> {
            JsonObject result = new JsonObject();
            result.addProperty(CodexJsonKeyEnum.ACTION.key(), approvalDecision(decision, ex));
            return result;
        });
    }

    @Override
    public void onDisconnected(Exception cause) {
        disconnectCallback.run();
    }
}
