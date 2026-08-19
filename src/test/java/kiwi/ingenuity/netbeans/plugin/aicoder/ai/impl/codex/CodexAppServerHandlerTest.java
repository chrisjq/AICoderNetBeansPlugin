package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.github.difflib.patch.PatchFailedException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexRateLimitEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.*;
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

    @Test
    void summarizeFileChanges_multipleFiles_includesCount() {
        JsonArray changes = new JsonArray();
        changes.add(new JsonObject());
        changes.add(new JsonObject());
        changes.add(new JsonObject());
        String summary = CodexAppServerHandler.summarizeFileChanges(changes);
        assertTrue(summary.contains("3"), "summary must mention the file count");
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
    private JsonObject itemStartedParams(String itemId, String filePath, String diff) {
        JsonObject item = new JsonObject();
        item.addProperty("id", itemId);
        item.addProperty("type", "fileChange");
        JsonArray changes = new JsonArray();
        JsonObject change = new JsonObject();
        change.addProperty("path", filePath);
        change.addProperty("kind", "update");
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

        // Approval for that id: cache miss → generic fallback message
        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("cmd-1"));
        assertEquals(1, events.size());
        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        assertTrue(ce.displayText().contains("modify a file"), "cache miss must produce generic message");
        ce.response().complete(PermissionDecision.denied("test"));
        reply.get(1, TimeUnit.SECONDS);
    }

    @Test
    void onTurnStarting_clearsCacheBeforeNextApproval() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        handler.onNotification(CodexAppServerHandler.METHOD_ITEM_STARTED,
                itemStartedParams("itm-old", "/tmp/old.java", null));
        handler.onTurnStarting(); // new turn: cache cleared

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("itm-old"));
        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        assertTrue(ce.displayText().contains("modify a file"),
                "cleared cache must produce generic message, not the old item path");
        ce.response().complete(PermissionDecision.denied("test"));
        reply.get(1, TimeUnit.SECONDS);
    }

    // ---- handleFileChangeApproval: cache-miss and PermissionEvent denied / exceptional ----
    @Test
    void handleFileChangeApproval_cacheMiss_fallsBackToConfirmEvent() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        CodexAppServerHandler handler = newHandler(events);

        CompletableFuture<JsonObject> reply = handler.onServerRequest(
                CodexAppServerHandler.METHOD_FILE_CHANGE_APPROVAL, approvalParams("unknown-id"));

        assertEquals(1, events.size());
        assertInstanceOf(ConfirmEvent.class, events.get(0),
                "unknown item id must fall back to ConfirmEvent");
        ConfirmEvent ce = (ConfirmEvent) events.get(0);
        ce.response().complete(PermissionDecision.denied("test"));
        assertEquals("decline", reply.get(1, TimeUnit.SECONDS).get("decision").getAsString());
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
}
