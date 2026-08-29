package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.github.difflib.patch.PatchFailedException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexAppServerHandlerTest {

    @TempDir
    Path tempDir;

    // ---- Static extraction helpers ----
    @Test
    void extractFileChangeChanges_nullParams_returnsNull() {
        assertNull(CodexAppServerHandler.extractFileChangeChanges(null));
    }

    @Test
    void extractFileChangeChanges_nonFileChangeItem_returnsNull() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "commandExecution");
        JsonObject params = new JsonObject();
        params.add("item", item);
        assertNull(CodexAppServerHandler.extractFileChangeChanges(params));
    }

    @Test
    void extractFileChangeChanges_fileChangeItem_returnsChanges() {
        JsonArray changes = new JsonArray();
        changes.add(new JsonObject());
        JsonObject item = new JsonObject();
        item.addProperty("type", "fileChange");
        item.add("changes", changes);
        JsonObject params = new JsonObject();
        params.add("item", item);
        assertSame(changes, CodexAppServerHandler.extractFileChangeChanges(params));
    }

    @Test
    void extractItemId_nullParams_returnsNull() {
        assertNull(CodexAppServerHandler.extractItemId(null));
    }

    @Test
    void extractItemId_returnsItemId() {
        JsonObject item = new JsonObject();
        item.addProperty("id", "exec-8ba");
        JsonObject params = new JsonObject();
        params.add("item", item);
        assertEquals("exec-8ba", CodexAppServerHandler.extractItemId(params));
    }

    @Test
    void extractTurnStatus_nullParams_returnsNull() {
        assertNull(CodexAppServerHandler.extractTurnStatus(null));
    }

    @Test
    void extractTurnStatus_returnsTurnStatus() {
        JsonObject turn = new JsonObject();
        turn.addProperty("status", "completed");
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        assertEquals("completed", CodexAppServerHandler.extractTurnStatus(params));
    }

    @Test
    void extractTurnErrorMessage_noError_returnsNull() {
        JsonObject turn = new JsonObject();
        turn.addProperty("status", "completed");
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        assertNull(CodexAppServerHandler.extractTurnErrorMessage(params));
    }

    @Test
    void extractTurnErrorMessage_returnsMessage() {
        JsonObject error = new JsonObject();
        error.addProperty("message", "rate limit exceeded");
        JsonObject turn = new JsonObject();
        turn.add("error", error);
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        assertEquals("rate limit exceeded", CodexAppServerHandler.extractTurnErrorMessage(params));
    }

    @Test
    void summarizeFileChanges_null_returnsGenericMessage() {
        assertTrue(CodexAppServerHandler.summarizeFileChanges(null).contains("modify a file"));
    }

    @Test
    void summarizeFileChanges_singleFile_includesPath() {
        JsonObject change = new JsonObject();
        change.addProperty("path", "/src/Foo.java");
        JsonArray changes = new JsonArray();
        changes.add(change);
        String summary = CodexAppServerHandler.summarizeFileChanges(changes);
        assertTrue(summary.contains("Foo.java"), "summary must mention the file path");
    }

    /**
     * Replaces summarizeFileChanges_multipleFiles_includesCount, which asserted the old "Codex wants to modify 3 files"
     * text — the blind bulk approval the multi-file review removed. A larger set can no longer reach this formatter,
     * and the guarantee worth pinning is the inverse of what that test asserted: whatever it is handed, it must never
     * offer to approve a COUNT of unseen files. This is the regression guard for a nearby edit reviving the plural
     * form, which is the actual risk in leaving that text around.
     */
    @Test
    void summarizeFileChanges_neverClaimsAFileCount() {
        JsonArray changes = new JsonArray();
        changes.add(new JsonObject());
        changes.add(new JsonObject());
        changes.add(new JsonObject());

        String summary = CodexAppServerHandler.summarizeFileChanges(changes);

        assertFalse(summary.contains("3"), "the summary must never advertise a file count: " + summary);
        assertFalse(summary.contains("files"), "the summary must describe one file, never a set: " + summary);
    }

    /**
     * The remaining non-obvious branch: a single entry that is not a JSON object has no path to name, so it falls back
     * to the generic wording rather than rendering something misleading.
     */
    @Test
    void summarizeFileChanges_singleNonObjectEntry_returnsGenericMessage() {
        JsonArray changes = new JsonArray();
        changes.add("not-an-object");

        assertEquals("Codex wants to modify a file", CodexAppServerHandler.summarizeFileChanges(changes));
    }

    @Test
    void firstChangedPath_returnsPath() {
        JsonObject change = new JsonObject();
        change.addProperty("path", "/tmp/foo.txt");
        JsonArray changes = new JsonArray();
        changes.add(change);
        assertEquals("/tmp/foo.txt", CodexAppServerHandler.firstChangedPath(changes));
    }

    // ---- Notification mappings ----
    private CodexAppServerHandler newHandler(List<AiProcessEvent> captured) {
        return new CodexAppServerHandler(captured::add, () -> {
        });
    }

    @Test
    void agentMessageDelta_firesTextDeltaEvent() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject params = new JsonObject();
        params.addProperty("delta", "Hello");
        params.addProperty("turnId", "tu_1");
        handler.onNotification(CodexAppServerHandler.METHOD_AGENT_MESSAGE_DELTA, params);

        assertEquals(1, events.size());
        assertInstanceOf(TextDeltaEvent.class, events.get(0));
        TextDeltaEvent te = (TextDeltaEvent) events.get(0);
        assertEquals("Hello", te.text());
        assertEquals("tu_1", te.turnId());
    }

    /**
     * Item shape copied from a live item/started notification, not invented: null null null     {@code {"item":{"type":"mcpToolCall","tool":"ListAiSessions",
     * "server":"aicoder-nb-ki-plugin","status":"inProgress",...}}}.
     */
    @Test
    void mcpToolCallItemStarted_firesToolUseEventSoNarrationStaysSeparated() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject item = new JsonObject();
        item.addProperty("type", "mcpToolCall");
        item.addProperty("id", "exec-c29c4888");
        item.addProperty("server", "aicoder-nb-ki-plugin");
        item.addProperty("tool", "ListAiSessions");
        item.addProperty("status", "inProgress");
        JsonObject params = new JsonObject();
        params.add("item", item);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED, params);

        assertEquals(1, events.size());
        ToolUseEvent tu = assertInstanceOf(ToolUseEvent.class, events.get(0));
        assertEquals("ListAiSessions", tu.toolName());
        assertEquals(ToolUseEvent.Kind.OTHER, tu.kind());
        assertFalse(tu.isFileModification(), "an MCP call must not trigger the diff panel");
    }

    /**
     * Only tool calls announce themselves. Reasoning and agentMessage items also arrive as item/started, and raising an
     * event for those would insert a paragraph break where no tool ran.
     */
    @Test
    void nonToolCallItemStarted_firesNothing() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject item = new JsonObject();
        item.addProperty("type", "reasoning");
        item.addProperty("id", "rs-1");
        JsonObject params = new JsonObject();
        params.add("item", item);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED, params);

        assertTrue(events.isEmpty(), "only mcpToolCall items should announce a tool run");
    }

    @Test
    void turnStarted_firesThinkingStatus() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_TURN_STARTED, new JsonObject());

        assertEquals(1, events.size());
        assertInstanceOf(StatusEvent.class, events.get(0));
        assertEquals(StatusEventTypeEnum.THINKING, ((StatusEvent) events.get(0)).type());
    }

    @Test
    void turnCompleted_success_firesTurnCompleteEvent() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject turn = new JsonObject();
        turn.addProperty("status", "completed");
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        handler.onNotification(CodexAppServerHandler.METHOD_TURN_COMPLETED, params);

        assertEquals(1, events.size());
        assertInstanceOf(TurnCompleteEvent.class, events.get(0),
                "successful turn must fire TurnCompleteEvent, not StatusEvent");
    }

    @Test
    void turnCompleted_failed_firesFailedStatus_notTurnCompleteEvent() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject error = new JsonObject();
        error.addProperty("message", "context window exceeded");
        JsonObject turn = new JsonObject();
        turn.addProperty("status", "failed");
        turn.add("error", error);
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        handler.onNotification(CodexAppServerHandler.METHOD_TURN_COMPLETED, params);

        assertEquals(1, events.size());
        assertInstanceOf(StatusEvent.class, events.get(0),
                "failed turn must fire StatusEvent(FAILED), not TurnCompleteEvent");
        StatusEvent se = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.FAILED, se.type());
        assertEquals("context window exceeded", se.text());
    }

    @Test
    void turnCompleted_failedNoErrorMessage_usesDefaultText() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject turn = new JsonObject();
        turn.addProperty("status", "failed");
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        handler.onNotification(CodexAppServerHandler.METHOD_TURN_COMPLETED, params);

        StatusEvent se = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.FAILED, se.type());
        assertFalse(se.text().isBlank(), "fallback failure message must not be blank");
    }

    @Test
    void turnCompleted_unauthorizedCodexErrorInfo_surfacesActionableLoginMessage() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        // codexErrorInfo == "unauthorized" (schema: CodexErrorInfo plain-string variant)
        JsonObject error = new JsonObject();
        error.addProperty("message", "401 Unauthorized");
        error.addProperty("codexErrorInfo", "unauthorized");
        JsonObject turn = new JsonObject();
        turn.addProperty("status", "failed");
        turn.add("error", error);
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        handler.onNotification(CodexAppServerHandler.METHOD_TURN_COMPLETED, params);

        StatusEvent se = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.FAILED, se.type());
        assertTrue(se.text().contains("codex login"),
                "unauthorized error must include actionable 'codex login' hint, got: " + se.text());
    }

    @Test
    void turnCompleted_codexErrorInfoOtherWith401MessageText_stillSurfacesActionableLoginMessage() {
        // The case actually observed live: codexErrorInfo collapses to the generic
        // string "other" by the time turn/completed fires (confirmed by probing a
        // real 401 — missing bearer token on the Responses websocket) — the schema's
        // "unauthorized" plain-string variant is not what this build sends. Only the
        // message text still carries the signal.
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject error = new JsonObject();
        error.addProperty("message",
                "unexpected status 401 Unauthorized: Missing bearer or basic authentication in header, "
                + "url: https://api.openai.com/v1/responses");
        error.addProperty("codexErrorInfo", "other");
        JsonObject turn = new JsonObject();
        turn.addProperty("status", "failed");
        turn.add("error", error);
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        handler.onNotification(CodexAppServerHandler.METHOD_TURN_COMPLETED, params);

        StatusEvent se = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.FAILED, se.type());
        assertTrue(se.text().contains("codex login"),
                "a 401 in the message text must map to the actionable login hint even when "
                + "codexErrorInfo is the generic \"other\", got: " + se.text());
    }

    @Test
    void turnCompleted_unrelated401SubstringOutsideAuthContext_doesNotFalsePositive() {
        // Guard against over-matching: contains("401 Unauthorized") is deliberately a
        // literal substring check, not a bare "401" check, so an unrelated numeric
        // coincidence in a different failure message must not be misclassified.
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject error = new JsonObject();
        error.addProperty("message", "line 401 of the generated patch failed to apply");
        error.addProperty("codexErrorInfo", "other");
        JsonObject turn = new JsonObject();
        turn.addProperty("status", "failed");
        turn.add("error", error);
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        handler.onNotification(CodexAppServerHandler.METHOD_TURN_COMPLETED, params);

        StatusEvent se = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.FAILED, se.type());
        assertEquals("line 401 of the generated patch failed to apply", se.text(),
                "an unrelated message containing the digits \"401\" must pass through verbatim, "
                + "not be misclassified as an auth failure");
    }

    @Test
    void extractTurnCodexErrorInfo_returnsUnauthorized() {
        JsonObject error = new JsonObject();
        error.addProperty("message", "401");
        error.addProperty("codexErrorInfo", "unauthorized");
        JsonObject turn = new JsonObject();
        turn.add("error", error);
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        assertEquals("unauthorized", CodexAppServerHandler.extractTurnCodexErrorInfo(params));
    }

    @Test
    void extractTurnCodexErrorInfo_objectVariant_returnsNull() {
        // Structured codexErrorInfo variants (e.g. httpConnectionFailed:{}) are objects, not strings
        JsonObject error = new JsonObject();
        error.add("codexErrorInfo", new JsonObject());
        JsonObject turn = new JsonObject();
        turn.add("error", error);
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        assertNull(CodexAppServerHandler.extractTurnCodexErrorInfo(params),
                "non-string codexErrorInfo must return null");
    }

    // ---- item/started caching ----
    /**
     * A PatchChangeKind as the schema actually defines it: an OBJECT with a {@code type}, plus {@code move_path} on an
     * update that is also a rename. The fixtures used to write {@code "kind": "update"} as a bare string, which no
     * longer matches what Codex sends.
     */
    private static JsonObject kind(String type, String movePath) {
        JsonObject kind = new JsonObject();
        kind.addProperty("type", type);
        if (movePath != null) {
            kind.addProperty("move_path", movePath);
        }
        return kind;
    }

    private JsonObject itemStartedParams(String itemId, String filePath, String diff) {
        return itemStartedParams(itemId, filePath, diff, kind("update", null));
    }

    private JsonObject itemStartedParams(String itemId, String filePath, String diff, JsonObject changeKind) {
        JsonObject item = new JsonObject();
        item.addProperty("id", itemId);
        item.addProperty("type", "fileChange");
        JsonArray changes = new JsonArray();
        JsonObject change = new JsonObject();
        change.addProperty("path", filePath);
        change.add("kind", changeKind);
        if (diff != null) {
            change.addProperty("diff", diff);
        }
        changes.add(change);
        item.add("changes", changes);
        JsonObject params = new JsonObject();
        params.add("item", item);
        return params;
    }

    private JsonObject approvalParams(String itemId) {
        JsonObject params = new JsonObject();
        params.addProperty("itemId", itemId);
        return params;
    }

    /**
     * item/started carrying SEVERAL changes, in the order given. Paths and diffs are paired by index; a null diff means
     * that entry has no diff field at all.
     */
    private JsonObject multiItemStartedParams(String itemId, List<String> paths, List<String> diffs) {
        return multiItemStartedParams(itemId, paths, diffs,
                paths.stream().map(p -> kind("update", null)).toList());
    }

    private JsonObject multiItemStartedParams(String itemId, List<String> paths, List<String> diffs,
            List<JsonObject> kinds) {
        JsonObject item = new JsonObject();
        item.addProperty("id", itemId);
        item.addProperty("type", "fileChange");
        JsonArray changes = new JsonArray();
        for (int i = 0; i < paths.size(); i++) {
            JsonObject change = new JsonObject();
            change.addProperty("path", paths.get(i));
            change.add("kind", kinds.get(i));
            if (diffs.get(i) != null) {
                change.addProperty("diff", diffs.get(i));
            }
            changes.add(change);
        }
        item.add("changes", changes);
        JsonObject params = new JsonObject();
        params.add("item", item);
        return params;
    }

    /**
     * Writes "red\ngreen\n" to a temp file and returns it, paired with a hunk that turns green into BLUE.
     */
    private Path renderableFile(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "red\ngreen\n", StandardCharsets.UTF_8);
        return file;
    }

    private static final String GREEN_TO_BLUE = "@@ -1,2 +1,2 @@\n red\n-green\n+BLUE\n";

    @Test
    void itemStarted_fileChange_cachesChanges() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("itm-cache", "/tmp/f.java", null));

        // Cache hit: the approval should find the item and mention its path
        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("itm-cache"));
        assertInstanceOf(ConfirmEvent.class, events.get(0));
        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        assertTrue(ce.displayText().contains("f.java"), "cached path must appear in display text");
        ce.response().complete(PermissionDecision.denied("test"));
        reply.get(1, TimeUnit.SECONDS);
    }

    @Test
    void itemStarted_nonFileChange_doesNotCache() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject item = new JsonObject();
        item.addProperty("id", "cmd-1");
        item.addProperty("type", "commandExecution");
        JsonObject params = new JsonObject();
        params.add("item", item);
        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED, params);

        // Approval for that id: nothing was cached, so it waits the bound and then falls back.
        handler.setFileChangeWaitMillisForTest(50L);
        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("cmd-1"));
        ConfirmEvent ce = awaitConfirm(events);
        assertTrue(ce.displayText().contains("modify a file"), "cache miss must produce generic message");
        ce.response().complete(PermissionDecision.denied("test"));
        reply.get(2, TimeUnit.SECONDS);
    }

    @Test
    void onTurnStarting_clearsCacheBeforeNextApproval() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);
        handler.setFileChangeWaitMillisForTest(50L);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("itm-old", "/tmp/old.java", null));
        handler.onTurnStarting(); // new turn: cache cleared

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("itm-old"));
        ConfirmEvent ce = awaitConfirm(events);
        assertTrue(ce.displayText().contains("modify a file"),
                "cleared cache must produce generic message, not the old item path");
        ce.response().complete(PermissionDecision.denied("test"));
        reply.get(2, TimeUnit.SECONDS);
    }

    /**
     * Waits for the bounded fallback to fire. Since the race fix, an approval whose item/started has not arrived does
     * NOT fall through immediately — it waits, because "not yet" and "never" are indistinguishable at that moment. The
     * tests shorten the bound rather than sleep for the real ten seconds.
     */
    private ConfirmEvent awaitConfirm(List<AiProcessEvent> events) throws InterruptedException {
        for (int i = 0; i < 100 && events.isEmpty(); i++) {
            Thread.sleep(20L);
        }
        assertEquals(1, events.size(), "the bound must expire to exactly one fallback confirm");
        return assertInstanceOf(ConfirmEvent.class, events.get(0),
                "an unavailable item/started must fall back to ConfirmEvent");
    }

    // ---- handleFileChangeApproval: cache-miss and PermissionEvent denied / exceptional ----

    /**
     * THE DEFINED FALLBACK when the changes never arrive at all. It is bounded, and it is the same blind confirm as
     * before — acceptable only because the bound is far longer than any real notification backlog. The distinct WARNING
     * the handler logs is what separates "raced and lost" from "genuinely no changes"; those used to be the same line.
     */
    @Test
    void handleFileChangeApproval_changesNeverArrive_fallsBackAfterTheBound() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);
        handler.setFileChangeWaitMillisForTest(50L);

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("unknown-id"));

        ConfirmEvent ce = awaitConfirm(events);
        ce.response().complete(PermissionDecision.denied("test"));
        assertEquals("decline", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
        assertTrue(handler.fileChangeCacheIsEmpty(), "a timed-out entry must not leak");
    }

    @Test
    void handleFileChangeApproval_permissionDenied_repliesDecline() throws Exception {
        Path file = tempDir.resolve("Denied.java");
        Files.writeString(file, "red\ngreen\n", StandardCharsets.UTF_8);

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("itm-deny", file.toString(), "@@ -1,2 +1,2 @@\n red\n-green\n+BLUE\n"));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("itm-deny"));

        assertInstanceOf(PermissionEvent.class, events.get(0));
        PermissionEvent pe = (PermissionEvent) events.get(0);
        pe.response().complete(PermissionDecision.denied("user said no"));
        // "decline" = deliberate rejection; Codex continues the turn (schema: FileChangeApprovalDecision)
        assertEquals("decline", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    @Test
    void handleFileChangeApproval_permissionExceptionally_repliesCancel() throws Exception {
        Path file = tempDir.resolve("Except.java");
        Files.writeString(file, "red\ngreen\n", StandardCharsets.UTF_8);

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("itm-exc", file.toString(), "@@ -1,2 +1,2 @@\n red\n-green\n+BLUE\n"));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("itm-exc"));

        assertInstanceOf(PermissionEvent.class, events.get(0));
        PermissionEvent pe = (PermissionEvent) events.get(0);
        // Exceptional completion (panel closed, process stopped) → "cancel" (interrupts the turn),
        // NOT "decline" (which would let Codex continue the turn on a now-stopped process).
        pe.response().completeExceptionally(new RuntimeException("panel closed"));
        JsonObject result = reply.get(2, TimeUnit.SECONDS);
        assertEquals("cancel", result.get("decision").getAsString(),
                "exceptional completion must reply 'cancel' to interrupt the turn, not 'decline'");
    }

    // ---- cancelPendingPermissions ----
    @Test
    void cancelPendingPermissions_noApprovalInFlight_isNoop() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);
        // Must not throw when nothing is pending
        handler.cancelPendingPermissions();
        assertTrue(events.isEmpty());
    }

    @Test
    void cancelPendingPermissions_withConfirmPending_repliesCancel() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_COMMAND_EXECUTION_APPROVAL, new JsonObject());

        // Turn is now awaiting approval — cancel it
        handler.cancelPendingPermissions();

        JsonObject result = reply.get(2, TimeUnit.SECONDS);
        // "cancel" interrupts the turn immediately; "decline" would let it continue (wrong)
        assertEquals("cancel", result.get("decision").getAsString(),
                "cancelPendingPermissions must reply 'cancel', not 'decline', to interrupt the turn");
    }

    @Test
    void cancelPendingPermissions_withPermissionPending_repliesCancel() throws Exception {
        Path file = tempDir.resolve("Cancel.java");
        Files.writeString(file, "red\ngreen\n", StandardCharsets.UTF_8);

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("itm-cancel", file.toString(), "@@ -1,2 +1,2 @@\n red\n-green\n+BLUE\n"));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("itm-cancel"));

        assertInstanceOf(PermissionEvent.class, events.get(0));
        handler.cancelPendingPermissions();

        JsonObject result = reply.get(2, TimeUnit.SECONDS);
        assertEquals("cancel", result.get("decision").getAsString(),
                "cancelPendingPermissions on a diff-panel approval must also reply 'cancel'");
    }

    // ---- Unknown server request and disconnect ----
    @Test
    void unknownServerRequest_failsCompletableFuture() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        CompletableFuture<JsonObject> reply = handler.onServerRequest("item/unknownTool/approve", new JsonObject());

        assertTrue(events.isEmpty(), "unknown request must not fire any event");
        assertTrue(reply.isCompletedExceptionally(),
                "unknown request must fail the future immediately rather than hang Codex's turn");
    }

    @Test
    void onDisconnected_callsDisconnectCallback() {
        AtomicBoolean called = new AtomicBoolean(false);
        CodexAppServerHandler handler = new CodexAppServerHandler(e -> {
        }, () -> called.set(true));

        handler.onDisconnected(new Exception("test-disconnect"));

        assertTrue(called.get(), "disconnect must invoke the callback");
    }

    // ---- applyUnifiedDiff ----
    @Test
    void applyUnifiedDiff_simpleUpdate() throws PatchFailedException {
        String original = "red\ngreen\n";
        String hunk = "@@ -1,2 +1,2 @@\n red\n-green\n+BLUE\n";
        String result = CodexAppServerHandler.applyUnifiedDiff(original, hunk);
        assertEquals("red\nBLUE\n", result);
    }

    /**
     * PROBE, not a specification. Reproduces what a hunk that ALREADY carries ---/+++ headers does today, when
     * applyUnifiedDiff prepends a second pair. Captures the failure mode so it can be compared against the errors seen
     * in the live run.
     */
    private static final String REAL_ORIGINAL
            = "/*\n"
            + " * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license\n"
            + " */\n"
            + "\n"
            + "package kiwi.ingenuity.cc.mavenproject1;\n"
            + "\n"
            + "/**\n"
            + " *\n"
            + " * @author chris\n"
            + " */\n"
            + "public class Mavenproject1 {\n"
            + "\n"
            + "    public static void main(String[] args) {\n"
            + "        System.out.println(\"Hello World!\");\n"
            + "    }\n"
            + "}\n";

    private static final String FAILED_HUNK
            = "@@ -7,2 +7,3 @@\n /**\n+ * Entry point for the sample Maven application.\n  *\n";

    private static final String WORKED_HUNK
            = "@@ -7,3 +7,3 @@\n /**\n- *\n+ * A small Maven application entry point.\n  * @author chris\n"
            + "@@ -13,4 +13,9 @@\n     public static void main(String[] args) {\n"
            + "+        // Print the application greeting.\n         System.out.println(\"Hello World!\");\n"
            + "     }\n+\n+    public static String applicationName() {\n+        return \"Mavenproject1\";\n"
            + "+    }\n }\n";

    /**
     * THE DIFFERENTIAL. Both hunks were captured from live Codex runs on 2026-08-29 against the same file; one failed
     * with CONTENT_DOES_NOT_MATCH_TARGET and one rendered and applied. Pinning both together is what locks in the
     * difference, because either alone is consistent with several wrong explanations.
     *
     * <p>The cause is the trailing empty element {@code split("\n", -1)} leaves when a hunk ends with a newline: the
     * parser reads it as one more CONTEXT line. Whether that is fatal depends on where the hunk's last line sits.</p>
     *
     * <ul>
     * <li>The FAILING hunk ends at original line 8 (" *"). The phantom context line expects line 9 to be empty; it is
     * " * @author chris", so the patch is rejected.</li>
     * <li>The WORKING hunk's last hunk ends at line 16 ("}"), the final line. {@code split("\n", -1)} on an original
     * that ends with a newline leaves its OWN trailing empty element there, so the phantom context line matches it by
     * coincidence and the patch applies.</li>
     * </ul>
     *
     * <p>That coincidence is why a working hunk that also ends in a newline does not disprove the cause: a hunk
     * reaching end-of-file gets away with it, and every other hunk does not.</p>
     */
    @Test
    void capturedFailingHunkNowApplies() throws Exception {
        String result = CodexAppServerHandler.applyUnifiedDiff(REAL_ORIGINAL, FAILED_HUNK);

        assertTrue(result.contains(" * Entry point for the sample Maven application."),
                "the inserted line must be present: " + result);
        assertTrue(result.contains("/**\n * Entry point for the sample Maven application.\n *\n * @author chris"),
                "it must be inserted between the javadoc opener and the existing lines: " + result);
    }

    @Test
    void capturedWorkingHunkStillApplies() throws Exception {
        String result = CodexAppServerHandler.applyUnifiedDiff(REAL_ORIGINAL, WORKED_HUNK);

        assertTrue(result.contains(" * A small Maven application entry point."), result);
        assertTrue(result.contains("        // Print the application greeting."), result);
        assertTrue(result.contains("    public static String applicationName() {"), result);
    }

    /**
     * THE THIRD LIVE CAPTURE, 2026-08-29: a 3-file batch, all kind=update, every one of which failed with
     * CONTENT_DOES_NOT_MATCH_TARGET against files whose contents were then verified byte by byte to match the hunks.
     *
     * <p>All three end their last body line on a CONTEXT line that is not the file's last line, which is precisely the
     * shape the phantom trailing element breaks — the same cause as {@link #capturedFailingHunkNowApplies()}. Kept as
     * distinct cases rather than folded into one because they are the real payload, and a fixture we invent is what let
     * this pass over 1700 tests while being wrong about the protocol.</p>
     *
     * <p>Do not tidy these strings.</p>
     */
    @Test
    void capturedThirdRunHunksAllApply() throws Exception {
        String farewell = "package kiwi.ingenuity.cc.mavenproject1;\n"
                + "\n"
                + "public class Farewell {\n"
                + "\n"
                + "    public String farewell() {\n"
                + "        return \"Goodbye!\";\n"
                + "    }\n"
                + "}\n";
        String farewellHunk = "@@ -5,3 +5,3 @@\n     public String farewell() {\n"
                + "-        return \"Goodbye!\";\n+        return \"Goodbye from Farewell!\";\n     }\n";

        String greeter = "package kiwi.ingenuity.cc.mavenproject1;\n"
                + "\n"
                + "public class Greeter {\n"
                + "\n"
                + "    public String greeting() {\n"
                + "        return \"Hello!\";\n"
                + "    }\n"
                + "}\n";
        String greeterHunk = "@@ -5,3 +5,3 @@\n     public String greeting() {\n"
                + "-        return \"Hello!\";\n+        return \"Hello from Greeter!\";\n     }\n";

        String mainHunk = "@@ -13,3 +13,3 @@\n     public static void main(String[] args) {\n"
                + "-        // Print the application greeting.\n"
                + "+        // Print the application greeting to standard output.\n"
                + "         System.out.println(\"Hello World!\");\n";
        String mainFile = REAL_ORIGINAL.replace("    public static void main(String[] args) {\n",
                "    public static void main(String[] args) {\n        // Print the application greeting.\n");

        assertTrue(CodexAppServerHandler.applyUnifiedDiff(farewell, farewellHunk)
                .contains("return \"Goodbye from Farewell!\";"), "Farewell.java hunk must apply");
        assertTrue(CodexAppServerHandler.applyUnifiedDiff(greeter, greeterHunk)
                .contains("return \"Hello from Greeter!\";"), "Greeter.java hunk must apply");
        assertTrue(CodexAppServerHandler.applyUnifiedDiff(mainFile, mainHunk)
                .contains("// Print the application greeting to standard output."),
                "Mavenproject1.java hunk must apply");
    }

    /**
     * PROVES THE CAUSE, rather than just that the captures now pass.
     *
     * <p>A hunk with ONE trailing newline is what Codex sends and now applies. Give it TWO and the strip removes only
     * one, leaving exactly the phantom trailing element the old code always had — and the failure comes back, with the
     * same CONTENT_DOES_NOT_MATCH_TARGET seen in all three live runs. So the trailing element is the mechanism, and
     * removing it is what fixed these, not any other change made alongside.</p>
     */
    @Test
    void aPhantomTrailingElementIsWhatBreaksAHunk() {
        String oneNewline = "@@ -7,2 +7,3 @@\n /**\n+ * Entry point for the sample Maven application.\n  *\n";

        assertDoesNotThrow(() -> CodexAppServerHandler.applyUnifiedDiff(REAL_ORIGINAL, oneNewline),
                "the shape Codex actually sends must apply");

        PatchFailedException thrown = assertThrows(PatchFailedException.class,
                () -> CodexAppServerHandler.applyUnifiedDiff(REAL_ORIGINAL, oneNewline + "\n"),
                "an extra trailing element reproduces the old failure");
        assertTrue(thrown.getMessage().contains("CONTENT_DOES_NOT_MATCH_TARGET"),
                "and it fails the same way the live runs did: " + thrown.getMessage());
    }

    /**
     * The fix must not merely shift the off-by-one: a hunk with no trailing newline has no phantom element to drop and
     * must still apply.
     */
    @Test
    void aHunkWithoutATrailingNewlineStillApplies() throws Exception {
        String noTrailing = FAILED_HUNK.substring(0, FAILED_HUNK.length() - 1);

        String result = CodexAppServerHandler.applyUnifiedDiff(REAL_ORIGINAL, noTrailing);

        assertTrue(result.contains(" * Entry point for the sample Maven application."), result);
    }

    /**
     * The ORIGINAL keeps its {@code split("\n", -1)} untouched — its trailing empty element represents the file's final
     * newline, and dropping it there would silently strip that newline on every write.
     */
    @Test
    void theOriginalsTrailingNewlineSurvives() throws Exception {
        assertTrue(REAL_ORIGINAL.endsWith("}\n"), "fixture precondition");

        String result = CodexAppServerHandler.applyUnifiedDiff(REAL_ORIGINAL, FAILED_HUNK);

        assertTrue(result.endsWith("}\n"), "the file's final newline must survive: ..."
                + result.substring(Math.max(0, result.length() - 12)).replace("\n", "\\n"));
    }

    @Test
    void applyUnifiedDiff_wrongContext_throwsPatchFailed() {
        String original = "red\ngreen\n";
        // diff expects "yellow" at line 2 but file has "green"
        String hunk = "@@ -1,2 +1,2 @@\n red\n-yellow\n+BLUE\n";
        assertThrows(PatchFailedException.class,
                () -> CodexAppServerHandler.applyUnifiedDiff(original, hunk));
    }

    // ---- handleFileChangeApproval ----
    @Test
    void handleFileChangeApproval_withDiff_raisesPermissionEvent() throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "red\ngreen\n", StandardCharsets.UTF_8);

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("itm1", file.toString(), "@@ -1,2 +1,2 @@\n red\n-green\n+BLUE\n"));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("itm1"));

        assertEquals(1, events.size(), "exactly one event must be raised");
        assertInstanceOf(PermissionEvent.class, events.get(0),
                "single-file diff must raise PermissionEvent, not ConfirmEvent");
        PermissionEvent pe = (PermissionEvent) events.get(0);
        assertEquals(file.toString(), pe.filePath());
        assertTrue(pe.writeContent().contains("BLUE"), "proposed content must include patched text");

        pe.response().complete(PermissionDecision.allowed());
        assertEquals("accept", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    @Test
    void handleFileChangeApproval_patchFails_fallsBackToConfirmEvent() throws Exception {
        Path file = tempDir.resolve("Other.java");
        Files.writeString(file, "red\ngreen\n", StandardCharsets.UTF_8);

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        // diff expects "yellow" but file has "green" -> PatchFailedException -> fallback
        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("itm2", file.toString(), "@@ -1,2 +1,2 @@\n red\n-yellow\n+BLUE\n"));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("itm2"));

        assertEquals(1, events.size());
        assertInstanceOf(ConfirmEvent.class, events.get(0),
                "patch failure must fall back to ConfirmEvent, not block on a PermissionEvent");

        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        ce.response().complete(PermissionDecision.allowed());
        assertEquals("accept", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    @Test
    void handleFileChangeApproval_noDiff_fallsBackToConfirmEvent() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        // no diff field -> no patch attempt -> ConfirmEvent (no file read either)
        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("itm3", "/some/path.java", null));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("itm3"));

        assertEquals(1, events.size());
        assertInstanceOf(ConfirmEvent.class, events.get(0),
                "missing diff field must raise ConfirmEvent");
        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        assertTrue(ce.displayText().contains("path.java"));

        ce.response().complete(PermissionDecision.denied("user rejected"));
        assertEquals("decline", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    // ---- handleFileChangeApproval: multi-file ----
    /**
     * The defect this feature removes. A multi-file edit used to fall through to a single-line ConfirmEvent — "Codex
     * wants to modify 3 files", Yes/No, no diff — so the user approved it sight unseen. It must now raise ONE
     * MultiPermissionEvent carrying the whole set.
     */
    @Test
    void handleFileChangeApproval_multiFile_raisesMultiPermissionEventNotConfirm() throws Exception {
        Path a = renderableFile("A.java");
        Path b = renderableFile("B.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("multi-1", List.of(a.toString(), b.toString()),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE)));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("multi-1"));

        assertEquals(1, events.size(), "a batch must raise exactly one event, not one per file");
        assertInstanceOf(MultiPermissionEvent.class, events.get(0),
                "multi-file must raise MultiPermissionEvent, not the blind ConfirmEvent");
        MultiPermissionEvent mpe = (MultiPermissionEvent) events.get(0);
        assertEquals(2, mpe.items().size());
        assertTrue(mpe.items().get(0).proposedContent().contains("BLUE"));
        assertTrue(mpe.items().get(1).proposedContent().contains("BLUE"));

        mpe.response().complete(PermissionDecision.allowed());
        assertEquals("accept", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    /**
     * The order is Codex's — it reflects how the model sequenced its own work. The paths here are deliberately
     * non-alphabetical so any sorting would show as a different list.
     */
    @Test
    void handleFileChangeApproval_multiFile_keepsCodexOrder() throws Exception {
        Path c = renderableFile("C.java");
        Path a = renderableFile("A.java");
        Path b = renderableFile("B.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("multi-order", List.of(c.toString(), a.toString(), b.toString()),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE, GREEN_TO_BLUE)));

        handler.onServerRequest(CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("multi-order"));

        MultiPermissionEvent mpe = (MultiPermissionEvent) events.get(0);
        assertEquals(List.of(c.toString(), a.toString(), b.toString()),
                mpe.items().stream().map(MultiPermissionItem::filePath).toList());
    }

    /**
     * An unrenderable file must become an ITEM with null proposed content — not a dropped entry, and not an exception
     * escaping into the JSON-RPC dispatch. The review is what turns it into a whole-set decline that names the file;
     * dropping it would let the user approve a set smaller than the one Codex is about to write.
     */
    @Test
    void handleFileChangeApproval_multiFile_unrenderableBecomesNullContentItem() throws Exception {
        Path good = renderableFile("Good.java");
        Path stale = renderableFile("Stale.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        // Second hunk expects "yellow" where the file has "green" -> PatchFailedException.
        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("multi-bad", List.of(good.toString(), stale.toString()),
                        Arrays.asList(GREEN_TO_BLUE, "@@ -1,2 +1,2 @@\n red\n-yellow\n+BLUE\n")));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("multi-bad"));

        MultiPermissionEvent mpe = (MultiPermissionEvent) events.get(0);
        assertEquals(2, mpe.items().size(), "the unrenderable entry must be kept, not dropped");
        assertNotNull(mpe.items().get(0).proposedContent());
        assertNull(mpe.items().get(1).proposedContent(), "a patch that will not apply yields null content");
        assertEquals(stale.toString(), mpe.items().get(1).filePath(), "the item must still name its file");

        mpe.response().complete(PermissionDecision.denied("could not render"));
        assertEquals("decline", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    /**
     * A missing file and a missing diff are the same class of problem as a failed patch, and take the same route.
     */
    @Test
    void handleFileChangeApproval_multiFile_missingFileOrDiffYieldsNullContent() throws Exception {
        Path good = renderableFile("Present.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("multi-missing",
                        List.of(good.toString(), tempDir.resolve("Absent.java").toString(), "/x/NoDiff.java"),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE, null)));

        handler.onServerRequest(CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("multi-missing"));

        MultiPermissionEvent mpe = (MultiPermissionEvent) events.get(0);
        assertEquals(3, mpe.items().size());
        assertNotNull(mpe.items().get(0).proposedContent());
        assertNull(mpe.items().get(1).proposedContent(), "a file that cannot be read yields null content");
        assertNull(mpe.items().get(2).proposedContent(), "a change with no diff field yields null content");
    }

    /**
     * An exceptional completion is still a cancel for a batch — the review carries the interruption-versus-denial
     * distinction, so nothing about the mapping changes.
     */
    @Test
    void handleFileChangeApproval_multiFile_exceptionalCompletionRepliesCancel() throws Exception {
        Path a = renderableFile("Cx1.java");
        Path b = renderableFile("Cx2.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("multi-cancel", List.of(a.toString(), b.toString()),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE)));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("multi-cancel"));

        MultiPermissionEvent mpe = (MultiPermissionEvent) events.get(0);
        mpe.response().completeExceptionally(new CancellationException("turn cancelled"));

        assertEquals("cancel", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    /**
     * cancelPendingPermissions must reach a batch's future too — the pending slot is written once for the whole set, so
     * a stop while a review is open replies cancel rather than leaving Codex waiting.
     */
    @Test
    void cancelPendingPermissions_cancelsAnOpenMultiFileReview() throws Exception {
        Path a = renderableFile("Pend1.java");
        Path b = renderableFile("Pend2.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("multi-pending", List.of(a.toString(), b.toString()),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE)));

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("multi-pending"));

        handler.cancelPendingPermissions();

        assertEquals("cancel", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    /**
     * acceptForSession suppresses future prompts for the same files, which defeats per-file review entirely.
     *
     * <p>
     * Looks for the STRING LITERAL, not the bare word: a value only reaches Codex as a quoted literal, while the word
     * itself legitimately appears in prose — the elicitation javadoc records that that channel's vocabulary has no
     * acceptForSession, which is documentation of the absence, not a use of it.</p>
     */
    @Test
    void acceptForSessionIsNeverSent() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/impl/codex/CodexAppServerHandler.java"));

        assertFalse(source.contains("\"acceptForSession\""),
                "acceptForSession must never be sent — it suppresses the prompts this feature exists to show");
    }

    // ---- item/started vs approval ordering ----

    /**
     * THE LIVE RACE, reproduced. item/started and the approval are handled on DIFFERENT executors — notifications on a
     * single codex-notify thread, requests on the codex-dispatch pool — so nothing orders them. Observed once in three
     * live runs: the approval won, read an empty cache, and the user got the blind one-line Yes/No instead of three
     * diffs.
     *
     * <p>Here the approval arrives FIRST and must still reach the multi-file review once the notification lands.</p>
     */
    @Test
    void approvalArrivingBeforeItemStartedStillReachesTheReview() throws Exception {
        Path a = renderableFile("RaceA.java");
        Path b = renderableFile("RaceB.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        // Approval first — the notification has not been drained yet.
        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("raced"));

        assertTrue(events.isEmpty(), "nothing may be raised until the changes arrive");
        assertFalse(reply.isDone(), "the approval must wait rather than fall through to a blind confirm");

        // Now the notification is drained.
        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("raced", List.of(a.toString(), b.toString()),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE)));

        MultiPermissionEvent mpe = (MultiPermissionEvent) events.stream()
                .filter(MultiPermissionEvent.class::isInstance).findFirst().orElse(null);
        assertNotNull(mpe, "the batch must reach the multi-file review, not the blind confirm");
        assertEquals(2, mpe.items().size());

        mpe.response().complete(PermissionDecision.allowed());
        assertEquals("accept", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    /**
     * The ordinary order must keep working — the fix removes the assumption, it does not invert it.
     */
    @Test
    void itemStartedArrivingFirstStillWorks() throws Exception {
        Path a = renderableFile("OrderA.java");
        Path b = renderableFile("OrderB.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("ordered", List.of(a.toString(), b.toString()),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE)));
        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("ordered"));

        MultiPermissionEvent mpe = (MultiPermissionEvent) events.stream()
                .filter(MultiPermissionEvent.class::isInstance).findFirst().orElse(null);
        assertNotNull(mpe);
        mpe.response().complete(PermissionDecision.allowed());
        assertEquals("accept", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    /**
     * A consumed entry must not linger and a timed-out one must not leak, or a long turn accumulates map entries for
     * every file change it ever made.
     */
    @Test
    void theCacheEntryIsReleasedOnceConsumed() throws Exception {
        Path a = renderableFile("ReleaseA.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("released", a.toString(), GREEN_TO_BLUE));
        handler.onServerRequest(CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("released"));

        assertTrue(handler.fileChangeCacheIsEmpty(),
                "the entry must be removed once the approval has consumed it");
    }

    // ---- change kind ----

    /**
     * kind is an OBJECT in the schema ({@code {"type":"add"}}), not a bare string. Reading it wrongly sends every
     * change down the update path, which is how creating a file came to decline a whole batch.
     */
    @Test
    void changeKind_readsTheSchemaObjectShape() {
        JsonObject change = new JsonObject();
        change.add("kind", kind("add", null));

        assertEquals("add", CodexAppServerHandler.changeKind(change));
    }

    @Test
    void changeKind_toleratesABareString() {
        JsonObject change = new JsonObject();
        change.addProperty("kind", "delete");

        assertEquals("delete", CodexAppServerHandler.changeKind(change),
                "a flattened variant must not read as 'kind unknown'");
    }

    @Test
    void changeKind_absentOrMalformedIsNull() {
        assertNull(CodexAppServerHandler.changeKind(new JsonObject()));
        assertNull(CodexAppServerHandler.changeKind(null));

        JsonObject noType = new JsonObject();
        noType.add("kind", new JsonObject());
        assertNull(CodexAppServerHandler.changeKind(noType));
    }

    @Test
    void changeMovePath_readsARename() {
        JsonObject change = new JsonObject();
        change.add("kind", kind("update", "/new/Name.java"));

        assertEquals("/new/Name.java", CodexAppServerHandler.changeMovePath(change));
    }

    @Test
    void changeMovePath_absentForAnOrdinaryUpdate() {
        JsonObject change = new JsonObject();
        change.add("kind", kind("update", null));

        assertNull(CodexAppServerHandler.changeMovePath(change));
    }

    // ---- handleFileChangeApproval: change kinds ----

    /**
     * FOR AN add, THE "diff" FIELD IS NOT A DIFF — it is the complete raw file content, with no @@ header and no +
     * prefixes. Captured verbatim from a live Codex run on 2026-08-29; do not tidy it. Feeding this to a unified-diff
     * parser is what produced POSITION_OUT_OF_TARGET for every new file in the first live run, so the proposed content
     * must be the field's value EXACTLY, byte for byte.
     */
    private static final String CAPTURED_ADD_CONTENT
            = "package kiwi.ingenuity.cc.mavenproject1;\n"
            + "\n"
            + "public class Farewell {\n"
            + "\n"
            + "    public String farewell() {\n"
            + "        return \"Goodbye!\";\n"
            + "    }\n"
            + "}\n";

    @Test
    void handleFileChangeApproval_multiFile_addUsesRawContentVerbatim() throws Exception {
        Path existing = renderableFile("Existing.java");
        Path created = tempDir.resolve("Farewell.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("multi-add", List.of(existing.toString(), created.toString()),
                        Arrays.asList(GREEN_TO_BLUE, CAPTURED_ADD_CONTENT),
                        List.of(kind("update", null), kind("add", null))));

        handler.onServerRequest(CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("multi-add"));

        MultiPermissionEvent mpe = (MultiPermissionEvent) events.get(0);
        assertEquals(2, mpe.items().size());
        assertNotNull(mpe.items().get(0).proposedContent(), "the edit must still render");
        assertEquals(CAPTURED_ADD_CONTENT, mpe.items().get(1).proposedContent(),
                "an add's content must be used verbatim, not parsed as a diff");
    }

    /**
     * The same raw-content rule on the single-file path, which shares proposedContentFor.
     */
    @Test
    void handleFileChangeApproval_singleAdd_usesRawContentVerbatim() throws Exception {
        Path created = tempDir.resolve("Greeter.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("single-add-raw", created.toString(), CAPTURED_ADD_CONTENT, kind("add", null)));

        handler.onServerRequest(CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("single-add-raw"));

        assertInstanceOf(PermissionEvent.class, events.get(0));
        assertEquals(CAPTURED_ADD_CONTENT, ((PermissionEvent) events.get(0)).writeContent());
    }

    /**
     * A single new file gets a real diff panel instead of the blind Yes/No it used to fall through to. The content
     * assertion lives in {@code handleFileChangeApproval_singleAdd_usesRawContentVerbatim}, which uses the captured
     * payload; this one pins the routing.
     */
    @Test
    void handleFileChangeApproval_singleAdd_raisesPermissionEvent() throws Exception {
        Path created = tempDir.resolve("BrandNew.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("single-add", created.toString(), CAPTURED_ADD_CONTENT, kind("add", null)));

        handler.onServerRequest(CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("single-add"));

        assertInstanceOf(PermissionEvent.class, events.get(0),
                "a new file must get a diff panel, not the blind confirm");
    }

    /**
     * THE DISTINCTION THAT MUST NOT COLLAPSE. A file that exists but cannot be read is genuinely unrenderable and must
     * still decline. If "missing means empty" were applied to it, the user would be shown a full-file addition and
     * would approve replacing content they never saw.
     */
    @Test
    void handleFileChangeApproval_updateOfAMissingFileStillDeclines() throws Exception {
        Path good = renderableFile("Fine.java");

        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                multiItemStartedParams("multi-missing-update",
                        List.of(good.toString(), tempDir.resolve("Vanished.java").toString()),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE),
                        List.of(kind("update", null), kind("update", null))));

        handler.onServerRequest(CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL,
                approvalParams("multi-missing-update"));

        MultiPermissionEvent mpe = (MultiPermissionEvent) events.get(0);
        assertNotNull(mpe.items().get(0).proposedContent());
        assertNull(mpe.items().get(1).proposedContent(),
                "an update whose file is absent must stay unrenderable — kind said it was there");
    }

    /**
     * Drives an approval whose changes contain an unsupported kind and returns the reason Codex receives.
     *
     * <p>Asserts the shared contract for every unsupported kind: NO MultiPermissionEvent or PermissionEvent is raised
     * (no panel, no review, no log line claiming a decision the user never made), the user is told what happened, and
     * the reply is a failed future — which the transport turns into a JSON-RPC error. That error is the only channel
     * that can carry a reason at all: the approval response has a decision field and nothing else, so a plain decline
     * would leave the model to retry the same unsupported patch.</p>
     */
    private String reasonForUnsupportedApproval(String itemId, JsonObject startedParams) throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED, startedParams);
        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams(itemId));

        assertTrue(events.stream().noneMatch(e -> e instanceof MultiPermissionEvent),
                "an unsupported kind must not raise a review");
        assertTrue(events.stream().noneMatch(e -> e instanceof PermissionEvent),
                "an unsupported kind must not raise a diff panel");
        assertTrue(events.stream().anyMatch(e -> e instanceof SystemNotificationEvent),
                "the user must be told the change was refused");

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> reply.get(2, TimeUnit.SECONDS),
                "the reply must be a JSON-RPC error, not a decision");
        return thrown.getCause().getMessage();
    }

    /**
     * A deletion cannot be shown by an item shape that only says "here is the file's new content". Applying the hunk
     * would show the file being EMPTIED, the user would approve that, and Codex would then delete it — a review of the
     * wrong operation that silently succeeds.
     */
    @Test
    void handleFileChangeApproval_deleteRepliesWithAReasonNamingIt() throws Exception {
        Path doomed = renderableFile("Doomed.java");

        String reason = reasonForUnsupportedApproval("multi-delete",
                multiItemStartedParams("multi-delete",
                        List.of(renderableFile("Kept.java").toString(), doomed.toString()),
                        Arrays.asList(GREEN_TO_BLUE, "@@ -1,2 +0,0 @@\n-red\n-green\n"),
                        List.of(kind("update", null), kind("delete", null))));

        assertTrue(reason.contains("delete"), reason);
        assertTrue(reason.contains(doomed.toString()), "the reason must name the file: " + reason);
    }

    /**
     * A rename's content diff is renderable, but nothing in the item shape can tell the user the file is also moving,
     * and approving a diff that silently relocates a file is the same class of surprise.
     */
    @Test
    void handleFileChangeApproval_renameRepliesWithAReasonNamingBothPaths() throws Exception {
        Path moving = renderableFile("Moving.java");

        String reason = reasonForUnsupportedApproval("multi-rename",
                multiItemStartedParams("multi-rename",
                        List.of(renderableFile("Static.java").toString(), moving.toString()),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE),
                        List.of(kind("update", null), kind("update", "/elsewhere/Moved.java"))));

        assertTrue(reason.contains(moving.toString()), reason);
        assertTrue(reason.contains("/elsewhere/Moved.java"), "the reason must name where it would go: " + reason);
    }

    /**
     * THE FUTURE-PROOFING CASE. A kind Codex has not shipped yet must error, not fall through to the update path — a
     * blocklist is wrong by default, an allowlist is safe by default. Treating an unknown kind as an ordinary edit
     * would show the user a content diff for an operation that is not one: the delete failure mode again, arriving
     * unannounced in a future Codex release.
     */
    @Test
    void handleFileChangeApproval_unrecognisedKindRepliesWithAReasonQuotingIt() throws Exception {
        Path target = renderableFile("Chmodded.java");

        String reason = reasonForUnsupportedApproval("multi-unknown",
                multiItemStartedParams("multi-unknown",
                        List.of(renderableFile("Ordinary.java").toString(), target.toString()),
                        Arrays.asList(GREEN_TO_BLUE, GREEN_TO_BLUE),
                        List.of(kind("update", null), kind("chmod", null))));

        assertTrue(reason.contains("chmod"),
                "the raw kind must appear verbatim — this string is how we learn Codex changed: " + reason);
        assertTrue(reason.contains(target.toString()), reason);
    }

    /**
     * kind is REQUIRED by the schema, so an absent one is a protocol violation. It must error rather than default to
     * update — this was a live latent defect: a null kind fell through to the update path.
     */
    @Test
    void handleFileChangeApproval_absentKindRepliesWithAReason() throws Exception {
        Path target = renderableFile("NoKind.java");

        JsonObject item = new JsonObject();
        item.addProperty("id", "multi-nokind");
        item.addProperty("type", "fileChange");
        JsonArray changes = new JsonArray();
        JsonObject change = new JsonObject();
        change.addProperty("path", target.toString());
        change.addProperty("diff", GREEN_TO_BLUE);
        changes.add(change);
        JsonObject other = new JsonObject();
        other.addProperty("path", renderableFile("Sane.java").toString());
        other.addProperty("diff", GREEN_TO_BLUE);
        other.add("kind", kind("update", null));
        changes.add(other);
        item.add("changes", changes);
        JsonObject params = new JsonObject();
        params.add("item", item);

        String reason = reasonForUnsupportedApproval("multi-nokind", params);

        assertTrue(reason.contains("absent"), "an absent kind must be reported as absent: " + reason);
        assertTrue(reason.contains(target.toString()), reason);
    }

    /**
     * The rule applies to a lone change too. A deletion that is unreviewable in a set of three is not made reviewable
     * by arriving on its own, and the single-file path's ConfirmEvent fallback would otherwise be the one silently
     * approvable route left.
     */
    @Test
    void handleFileChangeApproval_singleDeleteAlsoErrorsInsteadOfBlindConfirm() throws Exception {
        Path doomed = renderableFile("LoneDoomed.java");

        String reason = reasonForUnsupportedApproval("single-delete",
                itemStartedParams("single-delete", doomed.toString(),
                        "@@ -1,2 +0,0 @@\n-red\n-green\n", kind("delete", null)));

        assertTrue(reason.contains("delete"), reason);
    }

    /**
     * The allowlist must not refuse what it exists to permit: add, and update without move_path, both pass.
     */
    @Test
    void unsupportedChangeReason_allowsAddAndPlainUpdate() {
        JsonArray changes = new JsonArray();
        JsonObject add = new JsonObject();
        add.addProperty("path", "/p/New.java");
        add.add("kind", kind("add", null));
        changes.add(add);
        JsonObject update = new JsonObject();
        update.addProperty("path", "/p/Old.java");
        update.add("kind", kind("update", null));
        changes.add(update);

        assertNull(CodexAppServerHandler.unsupportedChangeReason(changes));
        assertNull(CodexAppServerHandler.unsupportedChangeReason(null));
    }

    @Test
    void threadTokenUsageUpdated_firesCodexTokenUsageEvent() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject total = new JsonObject();
        total.addProperty("totalTokens", 5_000_000L);
        total.addProperty("inputTokens", 4_000_000L);
        total.addProperty("outputTokens", 1_000_000L);
        total.addProperty("cachedInputTokens", 0L);
        total.addProperty("reasoningOutputTokens", 0L);
        JsonObject last = new JsonObject();
        last.addProperty("totalTokens", 12345L);
        last.addProperty("inputTokens", 10000L);
        last.addProperty("outputTokens", 2345L);
        last.addProperty("cachedInputTokens", 0L);
        last.addProperty("reasoningOutputTokens", 0L);
        JsonObject tokenUsage = new JsonObject();
        tokenUsage.add("total", total);
        tokenUsage.add("last", last);
        tokenUsage.addProperty("modelContextWindow", 200000L);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", "th_1");
        params.addProperty("turnId", "tu_1");
        params.add("tokenUsage", tokenUsage);

        handler.onNotification(CodexAppServerHandler.METHOD_THREAD_TOKEN_USAGE, params);

        assertEquals(1, events.size());
        assertInstanceOf(CodexTokenUsageEvent.class, events.get(0));
        CodexTokenUsageEvent te = (CodexTokenUsageEvent) events.get(0);
        assertEquals(12345L, te.usedTokens());
        assertEquals(200000L, te.contextWindow());
    }

    @Test
    void accountRateLimitsUpdated_firesPrimaryUsageEvent() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject primary = new JsonObject();
        primary.addProperty("usedPercent", 51.0);
        primary.addProperty("windowDurationMins", 43200L);
        primary.addProperty("resetsAt", 1789468349L);
        JsonObject rateLimits = new JsonObject();
        rateLimits.add("primary", primary);
        JsonObject params = new JsonObject();
        params.add("rateLimits", rateLimits);

        handler.onNotification(CodexAppServerHandler.METHOD_ACCOUNT_RATE_LIMITS_UPDATED, params);

        assertEquals(1, events.size());
        assertInstanceOf(CodexRateLimitEvent.class, events.get(0));
        CodexRateLimitEvent event = (CodexRateLimitEvent) events.get(0);
        assertEquals(51.0, event.usedPercent());
        assertEquals(43200L, event.windowDurationMins());
        assertEquals(1789468349L, event.resetsAtEpochSeconds());
    }

    @Test
    void accountRateLimitsUpdated_withoutPrimaryUsage_doesNothing() {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject rateLimits = new JsonObject();
        rateLimits.add("primary", new JsonObject());
        JsonObject params = new JsonObject();
        params.add("rateLimits", rateLimits);
        handler.onNotification(CodexAppServerHandler.METHOD_ACCOUNT_RATE_LIMITS_UPDATED, params);

        assertTrue(events.isEmpty());
    }

    @Test
    void commandExecutionApproval_raisesConfirmEvent() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject params = new JsonObject();
        params.addProperty("command", "npm install");

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_COMMAND_EXECUTION_APPROVAL, params);

        assertEquals(1, events.size());
        assertInstanceOf(ConfirmEvent.class, events.get(0),
                "commandExecution approval must always raise ConfirmEvent, not PermissionEvent");
        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        assertTrue(ce.displayText().contains("npm install"));

        ce.response().complete(PermissionDecision.denied("blocked"));
        assertEquals("decline", reply.get(2, TimeUnit.SECONDS).get("decision").getAsString());
    }

    // ---- mcpServer/elicitation/request ----
    @Test
    void mcpElicitationRequest_raisesConfirmEventWithMessage() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject meta = new JsonObject();
        meta.addProperty("codex_approval_kind", "mcp_tool_call");
        JsonObject params = new JsonObject();
        params.addProperty("threadId", "th_1");
        params.addProperty("serverName", "probe");
        params.addProperty("mode", "form");
        params.addProperty("message", "Allow the probe MCP server to run tool \"probe_echo\"?");
        params.add("_meta", meta);
        params.add("requestedSchema", new JsonObject());

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_MCP_ELICITATION, params);

        assertEquals(1, events.size());
        assertInstanceOf(ConfirmEvent.class, events.get(0),
                "MCP elicitation must raise ConfirmEvent with the human-readable message");
        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        assertTrue(ce.displayText().contains("probe_echo"),
                "ConfirmEvent display text must include the MCP message");

        // Response field is "action", not "decision" (schema: McpServerElicitationRequestResponse)
        ce.response().complete(PermissionDecision.allowed());
        JsonObject result = reply.get(2, TimeUnit.SECONDS);
        assertTrue(result.has("action"), "MCP elicitation reply must use field 'action', not 'decision'");
        assertFalse(result.has("decision"), "MCP elicitation must NOT use 'decision' field");
        assertEquals("accept", result.get("action").getAsString());
    }

    @Test
    void mcpElicitationRequest_declined_repliesDecline() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject params = new JsonObject();
        params.addProperty("threadId", "th_1");
        params.addProperty("serverName", "probe");
        params.addProperty("mode", "form");
        params.addProperty("message", "Allow probe tool?");
        params.add("requestedSchema", new JsonObject());

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_MCP_ELICITATION, params);

        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        // Deliberate rejection → "decline" (agent continues turn), not "cancel"
        ce.response().complete(PermissionDecision.denied("user said no"));
        assertEquals("decline", reply.get(2, TimeUnit.SECONDS).get("action").getAsString());
    }

    @Test
    void mcpElicitationRequest_exceptionally_repliesCancel() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        JsonObject params = new JsonObject();
        params.addProperty("threadId", "th_1");
        params.addProperty("serverName", "probe");
        params.addProperty("mode", "form");
        params.addProperty("message", "Allow probe tool?");
        params.add("requestedSchema", new JsonObject());

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_MCP_ELICITATION, params);

        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        // Exceptional completion (process stopped) → "cancel" (interrupts the turn)
        ce.response().completeExceptionally(new RuntimeException("stopped"));
        assertEquals("cancel", reply.get(2, TimeUnit.SECONDS).get("action").getAsString());
    }

    // ---- Malformed-payload hardening: reverting any single guard turns exactly the matching
    // assertion red (event lost behind the Throwable net, or a "handler threw" warning). ----
    private final List<AiProcessEvent> hardeningEvents = new ArrayList<>();
    private final AiProcessEventListener hardeningListener = hardeningEvents::add;
    private CodexAppServerHandler hardenedHandler;
    private WarningCapture warnings;

    @BeforeEach
    void setUp() {
        hardenedHandler = new CodexAppServerHandler(hardeningListener, () -> {
        });
        warnings = new WarningCapture();
        Logger.getLogger(CodexAppServerHandler.class.getName()).addHandler(warnings);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(CodexAppServerHandler.class.getName()).removeHandler(warnings);
    }

    private static JsonObject json(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static class WarningCapture extends Handler {

        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        boolean anyContains(String fragment) {
            return records.stream().map(WarningCapture::render)
                    .anyMatch(text -> text.contains(fragment));
        }

        private static String render(LogRecord record) {
            String text = String.valueOf(record.getMessage());
            Object[] params = record.getParameters();
            if (params != null) {
                for (Object param : params) {
                    text += " " + param;
                }
            }
            return text;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    void agentMessageDelta_objectDelta_stillEmitsTextDelta() {
        hardenedHandler.onNotification(CodexAppServerHandler.METHOD_AGENT_MESSAGE_DELTA,
                json("{\"delta\":{\"boom\":1},\"turnId\":\"t1\"}"));

        TextDeltaEvent delta = hardeningEvents.stream()
                .filter(TextDeltaEvent.class::isInstance)
                .map(TextDeltaEvent.class::cast)
                .findFirst().orElse(null);
        assertTrue(delta != null, "malformed delta must degrade to \"\", not lose the event");
        assertEquals("", delta.text());
        assertEquals("t1", delta.turnId());
        assertFalse(warnings.anyContains("handler threw"),
                "guarded payloads must stay off the Throwable net");
    }

    @Test
    void tokenUsage_nullContextWindow_emitsZeroedContextWindow() {
        hardenedHandler.onNotification(CodexAppServerHandler.METHOD_THREAD_TOKEN_USAGE,
                json("{\"tokenUsage\":{\"modelContextWindow\":null,\"last\":{\"totalTokens\":5}}}"));

        CodexTokenUsageEvent usage = hardeningEvents.stream()
                .filter(CodexTokenUsageEvent.class::isInstance)
                .map(CodexTokenUsageEvent.class::cast)
                .findFirst().orElse(null);
        assertTrue(usage != null);
        assertEquals(5L, usage.usedTokens());
        assertEquals(0L, usage.contextWindow());
    }

    @Test
    void rateLimits_nonPrimitiveWindowFields_stillPublishes() {
        hardenedHandler.onNotification(CodexAppServerHandler.METHOD_ACCOUNT_RATE_LIMITS_UPDATED,
                json("{\"rateLimits\":{\"primary\":{\"usedPercent\":12.5,"
                        + "\"windowDurationMins\":[5],\"resetsAt\":null}}}"));

        CodexRateLimitEvent limits = hardeningEvents.stream()
                .filter(CodexRateLimitEvent.class::isInstance)
                .map(CodexRateLimitEvent.class::cast)
                .findFirst().orElse(null);
        assertTrue(limits != null);
        assertEquals(12.5, limits.usedPercent());
        assertEquals(0L, limits.windowDurationMins());
        assertEquals(0L, limits.resetsAtEpochSeconds());
    }

    @Test
    void serverRequest_nullParams_yieldsFailedFutureInsteadOfThrowing() {
        CompletableFuture<JsonObject> reply = assertDoesNotThrow(()
                -> hardenedHandler.onServerRequest(CodexAppServerHandler.METHOD_COMMAND_EXECUTION_APPROVAL, null));

        assertTrue(reply.isDone());
        assertTrue(reply.isCompletedExceptionally(), "null params must fail the reply, not throw");
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> reply.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException,
                "net converts any synchronous throw into the INTERNAL_ERROR failed future");
        assertTrue(warnings.anyContains("server request handler threw"));
    }

    @Test
    void notificationThrow_doesNotKillWorker_laterNotificationsStillProcessed() {
        hardenedHandler.onNotification(CodexAppServerHandler.METHOD_AGENT_MESSAGE_DELTA, null);

        hardenedHandler.onNotification(CodexAppServerHandler.METHOD_TURN_STARTED, new JsonObject());

        assertTrue(hardeningEvents.stream().anyMatch(StatusEvent.class::isInstance),
                "notify worker must survive a throwing notification");
        assertTrue(warnings.anyContains("notification handler threw"));
    }

    @Test
    void elicitation_objectMessage_fallsBackToGenericText_andCancelsCleanly() {
        CompletableFuture<JsonObject> reply = hardenedHandler.onServerRequest(
                CodexAppServerHandler.METHOD_MCP_ELICITATION,
                json("{\"message\":{\"deep\":true}}"));

        ConfirmEvent confirm = hardeningEvents.stream()
                .filter(ConfirmEvent.class::isInstance)
                .map(ConfirmEvent.class::cast)
                .findFirst().orElse(null);
        assertTrue(confirm != null);
        assertEquals("McpElicitation", confirm.toolName());
        assertEquals("MCP server requests approval", confirm.displayText());

        confirm.response().completeExceptionally(new CancellationException("panel closed"));
        JsonObject decision = assertDoesNotThrow(() -> reply.get(1, TimeUnit.SECONDS));
        assertEquals("cancel", decision.get("action").getAsString());
    }

    @Test
    void commandApproval_unreadableFields_useFallbackDisplayText() {
        hardenedHandler.onServerRequest(CodexAppServerHandler.METHOD_COMMAND_EXECUTION_APPROVAL,
                json("{\"reason\":{},\"command\":[]}"));

        ConfirmEvent confirm = hardeningEvents.stream()
                .filter(ConfirmEvent.class::isInstance)
                .map(ConfirmEvent.class::cast)
                .findFirst().orElse(null);
        assertTrue(confirm != null);
        assertEquals("Command", confirm.toolName());
        assertEquals("Codex wants to run a command", confirm.displayText());
    }

    /**
     * A malformed change — non-primitive path, and no kind at all — must still yield a clean, prompt answer rather
     * than hanging or throwing. That property is unchanged; the answer is not.
     *
     * <p>This used to assert a blind ConfirmEvent ("Codex wants to modify a file", Yes/No). Under the allowlist an
     * absent kind is a protocol violation — the schema makes it required — and is refused with a JSON-RPC error
     * instead. Approving a change we cannot even identify was the weakest remaining path, and the old assertion was
     * pinning it.</p>
     */
    @Test
    void fileChangeApproval_malformedChange_repliesWithAnErrorNotABlindConfirm() {
        hardenedHandler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                json("{\"item\":{\"type\":\"fileChange\",\"id\":\"i1\","
                        + "\"changes\":[{\"path\":{\"deep\":1}}]}}"));

        CompletableFuture<JsonObject> reply = hardenedHandler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL,
                json("{\"itemId\":\"i1\"}"));

        assertTrue(hardeningEvents.stream().noneMatch(ConfirmEvent.class::isInstance),
                "a change we cannot identify must not become an approvable confirm");
        assertTrue(hardeningEvents.stream().anyMatch(SystemNotificationEvent.class::isInstance),
                "the user must be told it was refused");

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> reply.get(1, TimeUnit.SECONDS));
        assertTrue(thrown.getCause().getMessage().contains("absent"),
                "the reason must say the kind was absent: " + thrown.getCause().getMessage());
    }

    @Test
    void extractors_tolerateWrongJsonTypes() {
        assertNull(CodexAppServerHandler.extractItemId(json("{\"item\":{\"id\":{}}}")));
        assertNull(CodexAppServerHandler.extractTurnStatus(json("{\"turn\":{\"status\":{\"deep\":1}}}")));
        assertNull(CodexAppServerHandler.extractFileChangeChanges(
                json("{\"item\":{\"type\":null,\"changes\":[]}}")));
        assertNull(CodexAppServerHandler.firstChangedPath(
                JsonParser.parseString("[{\"path\":[]}]").getAsJsonArray()));
        assertEquals("Codex wants to modify a file",
                CodexAppServerHandler.summarizeFileChanges(
                        JsonParser.parseString("[{\"path\":{}}]").getAsJsonArray()));
    }
}
