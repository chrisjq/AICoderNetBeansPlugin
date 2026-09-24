package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.McpSteeringPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.McpSteeringRefusalEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * A permission request that only asks to LOOK at a path must not surface as "Write", must not render a diff,
 * and — when the path is inside the session's own {@code ~/.ai-coder/{type}/{sessionId}/} tree, which is
 * where the plugin spools oversized tool results — must not ask at all.
 *
 * <p>
 * The {@code external_directory} fixtures reproduce what OpenCode's ACP agent actually sends for that
 * permission (read out of its source): {@code kind:"other"} (its kind mapping has no entry for
 * {@code external_directory}), the parent directory as the title, file and directory as {@code locations},
 * the raw metadata as {@code rawInput}, and no {@code content}.
 */
class OpenCodeAcpClientHandlerPermissionRoutingTest {

    private static final String OWN_TREE = "/home/u/.ai-coder/opencode/sess-1";
    private static final String OWN_SPOOL_DIR = OWN_TREE + "/tmp/tool_results";
    private static final String OWN_SPOOL_FILE = OWN_SPOOL_DIR + "/git-diff-sess-1-9973162764047654929.log";

    private static Predicate<String> ownTree() {
        return path -> path.equals(OWN_TREE) || path.startsWith(OWN_TREE + "/");
    }

    private static OpenCodeAcpClientHandler handler(List<AiProcessEvent> fired, Predicate<String> ownTree) {
        return new OpenCodeAcpClientHandler(fired::add, () -> {
        }, null, ownTree);
    }

    private static OpenCodeAcpClientHandler handlerWithSteering(List<AiProcessEvent> fired, Predicate<String> ownTree,
            Predicate<String> steeringIsActive) {
        return new OpenCodeAcpClientHandler(fired::add, () -> {
        }, null, ownTree, steeringIsActive);
    }

    private static JsonObject params(JsonObject toolCall) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "ses_abc");
        params.add("toolCall", toolCall);
        return params;
    }

    private static JsonArray locations(String... paths) {
        JsonArray array = new JsonArray();
        for (String path : paths) {
            JsonObject location = new JsonObject();
            location.addProperty("path", path);
            array.add(location);
        }
        return array;
    }

    /**
     * The shape OpenCode sends for an {@code external_directory} ask: kind "other", title = parentDir, no
     * content.
     */
    private static JsonObject externalDirectory(String filepath, String parentDir) {
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("filepath", filepath);
        rawInput.addProperty("parentDir", parentDir);
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("toolCallId", "per_00f406e800018efhTkL7QMx8a9");
        toolCall.addProperty("title", parentDir);
        toolCall.addProperty("kind", "other");
        toolCall.addProperty("status", "pending");
        toolCall.add("locations", locations(filepath, parentDir));
        toolCall.add("rawInput", rawInput);
        return toolCall;
    }

    private static JsonObject simple(String kind, String title, String path) {
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", title);
        toolCall.addProperty("kind", kind);
        toolCall.add("locations", locations(path));
        return toolCall;
    }

    private static void assertNoWriteEvent(List<AiProcessEvent> fired) {
        assertTrue(fired.stream().noneMatch(e -> e instanceof PermissionEvent),
                "a request to look at a path must never raise the Write/diff flow");
    }

    private static String optionId(JsonObject result) {
        return result.getAsJsonObject("outcome").get("optionId").getAsString();
    }

    // ---- Fix 1: the session's own config tree is never asked about ----
    @Test
    void spooledToolResultInsideTheOwnTreeIsAllowedOnceWithNoEventAndNoPrompt() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();

        CompletableFuture<JsonObject> future = handler(fired, ownTree())
                .onRequestPermission(params(externalDirectory(OWN_SPOOL_FILE, OWN_SPOOL_DIR)));

        assertTrue(fired.isEmpty(), "the plugin's own spooled result must not ask the user anything");
        assertTrue(future.isDone(), "OpenCode must be answered immediately, not left waiting on a prompt");
        JsonObject result = future.get(1, TimeUnit.SECONDS);
        assertEquals("selected", result.getAsJsonObject("outcome").get("outcome").getAsString());
        assertEquals("once", optionId(result), "the same allow-once reply an accepted prompt gets");
    }

    @Test
    void anEditInsideTheOwnTreeIsAllowedWithNoPromptLikeEveryOtherWritePath() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        JsonObject toolCall = simple("edit", OWN_TREE + "/memory/notes.md", OWN_TREE + "/memory/notes.md");

        CompletableFuture<JsonObject> future = handler(fired, ownTree()).onRequestPermission(params(toolCall));

        assertTrue(fired.isEmpty());
        assertEquals("once", optionId(future.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void aRequestNamingAnOwnPathAndAnOutsidePathStillAsks() {
        List<AiProcessEvent> fired = new ArrayList<>();
        // The first path is ours, the second is not: exempting on the first would let the second through unasked.
        JsonObject toolCall = externalDirectory(OWN_SPOOL_FILE, "/etc");

        CompletableFuture<JsonObject> future = handler(fired, ownTree()).onRequestPermission(params(toolCall));

        assertFalse(future.isDone(), "one outside path means the user is asked");
        assertEquals(1, fired.size());
        assertInstanceOf(ConfirmEvent.class, fired.get(0));
    }

    @Test
    void anOutsidePathIsNotExemptedByTheOwnTreeRule() {
        List<AiProcessEvent> fired = new ArrayList<>();

        handler(fired, ownTree()).onRequestPermission(params(externalDirectory("/etc/passwd", "/etc")));

        assertEquals(1, fired.size(), "outside the own tree the user is still asked");
    }

    @Test
    void withoutTheExemptionWiredEveryPathIsStillPutToTheUser() {
        List<AiProcessEvent> fired = new ArrayList<>();
        // The three-argument constructor the process manager used before the exemption existed: nothing is exempt.
        OpenCodeAcpClientHandler legacy = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        CompletableFuture<JsonObject> future = legacy.onRequestPermission(params(externalDirectory(OWN_SPOOL_FILE, OWN_SPOOL_DIR)));

        assertFalse(future.isDone());
        assertEquals(1, fired.size(), "an unwired exemption fails closed");
    }

    @Test
    void aFailingOwnTreeCheckFailsClosedAndAsks() {
        List<AiProcessEvent> fired = new ArrayList<>();
        Predicate<String> broken = path -> {
            throw new IllegalStateException("registry unavailable");
        };

        CompletableFuture<JsonObject> future = handler(fired, broken)
                .onRequestPermission(params(externalDirectory(OWN_SPOOL_FILE, OWN_SPOOL_DIR)));

        assertFalse(future.isDone());
        assertEquals(1, fired.size());
    }

    @Test
    void theRealCheckDeniesAnUnregisteredSession() {
        assertFalse(OpenCodeAcpClientHandler.ownSessionConfigFileCheck("no-such-plugin-session").test(OWN_SPOOL_FILE),
                "an exemption that cannot be verified must never be granted");
    }

    // ---- Fix 2: a request to look is not a write, and never shows a blanking diff ----
    @Test
    void anExternalDirectoryRequestIsAnAccessConfirmNotAWriteWithADiff() {
        List<AiProcessEvent> fired = new ArrayList<>();
        String outside = "/Users/chris/Downloads/report/summary.txt";

        CompletableFuture<JsonObject> future = handler(fired, ownTree())
                .onRequestPermission(params(externalDirectory(outside, "/Users/chris/Downloads/report")));

        assertFalse(future.isDone());
        assertEquals(1, fired.size());
        assertNoWriteEvent(fired);
        ConfirmEvent ce = assertInstanceOf(ConfirmEvent.class, fired.get(0));
        assertEquals("Access", ce.toolName(), "kind \"other\" from an external_directory ask is an access request");
        assertNotEquals("Write", ce.toolName());
        assertEquals(outside, ce.filePath());
        assertEquals(outside, ce.displayText());
        assertFalse(ce.requireExplicitApproval(),
                "auto-accept keeps answering these as it did when they were mislabelled Write; only the label changes");
    }

    @Test
    void aReadKindRequestForAPathIsAConfirmNamedReadAndNeverAWrite() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();

        CompletableFuture<JsonObject> future = handler(fired, ownTree())
                .onRequestPermission(params(simple("read", "notes.txt", "/Users/chris/Documents/notes.txt")));

        assertNoWriteEvent(fired);
        ConfirmEvent ce = assertInstanceOf(ConfirmEvent.class, fired.get(0));
        assertEquals("Read", ce.toolName());
        assertEquals("/Users/chris/Documents/notes.txt", ce.filePath());
        assertNull(ce.targetPath());

        ce.response().complete(PermissionDecision.allowed());
        assertEquals("once", optionId(future.get(1, TimeUnit.SECONDS)), "accepting still answers OpenCode allow-once");
    }

    @Test
    void rejectingAnAccessConfirmRejectsTheRequest() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();

        CompletableFuture<JsonObject> future = handler(fired, ownTree())
                .onRequestPermission(params(simple("read", "x", "/srv/data/x.txt")));
        ((ConfirmEvent) fired.get(0)).response().complete(PermissionDecision.denied("no"));

        assertEquals("reject", optionId(future.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void aSearchRequestIsNamedSearchAndShowsPatternAndLocation() {
        List<AiProcessEvent> fired = new ArrayList<>();

        handler(fired, ownTree()).onRequestPermission(params(simple("search", "TODO|FIXME", "/srv/other-repo")));

        assertNoWriteEvent(fired);
        ConfirmEvent ce = assertInstanceOf(ConfirmEvent.class, fired.get(0));
        assertEquals("Search", ce.toolName());
        assertEquals("TODO|FIXME in /srv/other-repo", ce.displayText());
        assertEquals("/srv/other-repo", ce.filePath());
    }

    // ---- What must keep working exactly as before ----
    @Test
    void aGenuineEditWithADiffStillRaisesTheWriteFlowUnchanged() {
        List<AiProcessEvent> fired = new ArrayList<>();
        JsonObject content = new JsonObject();
        content.addProperty("type", "diff");
        content.addProperty("path", "/proj/src/Foo.java");
        content.addProperty("oldText", "a\n");
        content.addProperty("newText", "b\n");
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);
        JsonObject toolCall = simple("edit", "/proj/src/Foo.java", "/proj/src/Foo.java");
        toolCall.add("content", contentArray);

        handler(fired, ownTree()).onRequestPermission(params(toolCall));

        assertEquals(1, fired.size());
        PermissionEvent pe = assertInstanceOf(PermissionEvent.class, fired.get(0));
        assertEquals("Write", pe.toolName());
        assertEquals("/proj/src/Foo.java", pe.filePath());
        assertEquals("b\n", pe.writeContent());
    }

    @Test
    void aChangeProposedUnderAnOtherKindIsStillTreatedAsAWrite() {
        List<AiProcessEvent> fired = new ArrayList<>();
        // kind "other" alone would be an access request; a diff in rawInput is positive evidence of a change.
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("filepath", "/proj/src/Foo.java");
        rawInput.addProperty("diff", "@@ -1 +1 @@\n-a\n+b\n");
        JsonObject toolCall = simple("other", "/proj/src/Foo.java", "/proj/src/Foo.java");
        toolCall.add("rawInput", rawInput);

        handler(fired, ownTree()).onRequestPermission(params(toolCall));

        assertEquals(1, fired.size());
        assertInstanceOf(PermissionEvent.class, fired.get(0), "a proposed change must keep its diff review");
    }

    @Test
    void anUnrecognisedKindWithAPathKeepsTheExistingWriteFlowRatherThanAGuess() {
        List<AiProcessEvent> fired = new ArrayList<>();

        handler(fired, ownTree()).onRequestPermission(params(simple("bash", "bash", "/some/path.txt")));

        assertEquals(1, fired.size());
        assertInstanceOf(PermissionEvent.class, fired.get(0));
    }

    @Test
    void aShellCommandStillRaisesTheExecuteConfirm() {
        List<AiProcessEvent> fired = new ArrayList<>();
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("command", "echo hi");
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "echo hi");
        toolCall.addProperty("kind", "execute");
        toolCall.add("locations", new JsonArray());
        toolCall.add("rawInput", rawInput);

        handler(fired, ownTree()).onRequestPermission(params(toolCall));

        ConfirmEvent ce = assertInstanceOf(ConfirmEvent.class, fired.get(0));
        assertEquals("Execute", ce.toolName());
        assertTrue(ce.requireExplicitApproval());
    }

    @Test
    void aMultiFilePatchWithOneFileOutsideTheOwnTreeStillAsks() {
        List<AiProcessEvent> fired = new ArrayList<>();
        JsonArray files = new JsonArray();
        JsonObject inside = new JsonObject();
        inside.addProperty("filePath", OWN_TREE + "/memory/a.md");
        files.add(inside);
        JsonObject outside = new JsonObject();
        outside.addProperty("filePath", "/etc/hosts");
        files.add(outside);
        JsonObject rawInput = new JsonObject();
        rawInput.add("files", files);
        // The location list happens to name only the inside file: the outside one is visible only through rawInput.
        JsonObject toolCall = simple("edit", "patch", OWN_TREE + "/memory/a.md");
        toolCall.add("rawInput", rawInput);

        handler(fired, ownTree()).onRequestPermission(params(toolCall));

        assertEquals(1, fired.size(), "a patch touching a file outside the own tree must not be exempted");
    }

    // ---- MCP Steering ----
    @Test
    void steeringONRejectsExecuteWithNoConfirmEventAndPostsRefusalEvent() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        Predicate<String> steeringOn = sessionId -> true;
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "echo hello");
        toolCall.addProperty("kind", "execute");
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("command", "echo hello");
        toolCall.add("rawInput", rawInput);
        toolCall.add("locations", new JsonArray());

        CompletableFuture<JsonObject> future = handlerWithSteering(fired, ownTree(), steeringOn)
                .onRequestPermission(params(toolCall));

        assertTrue(future.isDone(), "steering auto-denies without waiting for user");
        assertEquals("reject", optionId(future.get(1, TimeUnit.SECONDS)));
        assertTrue(fired.stream().noneMatch(e -> e instanceof ConfirmEvent), "no ConfirmEvent raised");
        McpSteeringRefusalEvent event = (McpSteeringRefusalEvent) fired.stream()
                .filter(e -> e instanceof McpSteeringRefusalEvent).findFirst().get();
        assertEquals(1, event.refusals().size());
        assertEquals("Execute", event.refusals().get(0).toolLabel());
        assertEquals(McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.SHELL),
                event.refusals().get(0).steeringText());
    }

    @Test
    void steeringONRejectsAccessKindsWithNoConfirmEventAndPostsRefusalEvent() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        Predicate<String> steeringOn = sessionId -> true;

        CompletableFuture<JsonObject> future = handlerWithSteering(fired, ownTree(), steeringOn)
                .onRequestPermission(params(simple("read", "notes.txt", "/Users/chris/Documents/notes.txt")));

        assertTrue(future.isDone(), "steering auto-denies without waiting for user");
        assertEquals("reject", optionId(future.get(1, TimeUnit.SECONDS)));
        assertTrue(fired.stream().noneMatch(e -> e instanceof ConfirmEvent), "no ConfirmEvent raised for access kind");
        McpSteeringRefusalEvent event = (McpSteeringRefusalEvent) fired.stream()
                .filter(e -> e instanceof McpSteeringRefusalEvent).findFirst().get();
        assertEquals(1, event.refusals().size());
        assertEquals("Read", event.refusals().get(0).toolLabel());
        assertEquals(McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.READ),
                event.refusals().get(0).steeringText());
    }

    @Test
    void steeringONRejectsWriteWithNoPermissionEventAndPostsRefusalEvent() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        Predicate<String> steeringOn = sessionId -> true;
        JsonObject content = new JsonObject();
        content.addProperty("type", "diff");
        content.addProperty("path", "/proj/src/Foo.java");
        content.addProperty("oldText", "a\n");
        content.addProperty("newText", "b\n");
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);
        JsonObject toolCall = simple("edit", "/proj/src/Foo.java", "/proj/src/Foo.java");
        toolCall.add("content", contentArray);

        CompletableFuture<JsonObject> future = handlerWithSteering(fired, ownTree(), steeringOn)
                .onRequestPermission(params(toolCall));

        assertTrue(future.isDone(), "steering auto-denies without waiting for user");
        assertEquals("reject", optionId(future.get(1, TimeUnit.SECONDS)));
        assertTrue(fired.stream().noneMatch(e -> e instanceof PermissionEvent), "no PermissionEvent raised for write");
        McpSteeringRefusalEvent event = (McpSteeringRefusalEvent) fired.stream()
                .filter(e -> e instanceof McpSteeringRefusalEvent).findFirst().get();
        assertEquals(1, event.refusals().size());
        assertEquals("Write", event.refusals().get(0).toolLabel());
        assertEquals(McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.WRITE),
                event.refusals().get(0).steeringText());
    }

    @Test
    void steeringONRejectsUnknownWithNoConfirmEventAndPostsRefusalEvent() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        Predicate<String> steeringOn = sessionId -> true;
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "unknown action");
        toolCall.addProperty("kind", "unknown-kind");
        toolCall.add("locations", new JsonArray());

        CompletableFuture<JsonObject> future = handlerWithSteering(fired, ownTree(), steeringOn)
                .onRequestPermission(params(toolCall));

        assertTrue(future.isDone(), "steering auto-denies without waiting for user");
        assertEquals("reject", optionId(future.get(1, TimeUnit.SECONDS)));
        assertTrue(fired.stream().noneMatch(e -> e instanceof ConfirmEvent), "no ConfirmEvent raised");
        McpSteeringRefusalEvent event = (McpSteeringRefusalEvent) fired.stream()
                .filter(e -> e instanceof McpSteeringRefusalEvent).findFirst().get();
        assertEquals(1, event.refusals().size());
        assertEquals("unknown-kind", event.refusals().get(0).toolLabel());
        assertEquals(McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.UNKNOWN),
                event.refusals().get(0).steeringText());
    }

    @Test
    void steeringONExemptsOurOwnMcpToolsFromSteering() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        Predicate<String> steeringOn = sessionId -> true;
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", McpToolEnum.allMcpNames().split(",")[0]);
        toolCall.addProperty("kind", "execute");
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("command", "build");
        toolCall.add("rawInput", rawInput);
        toolCall.add("locations", new JsonArray());

        CompletableFuture<JsonObject> future = handlerWithSteering(fired, ownTree(), steeringOn)
                .onRequestPermission(params(toolCall));

        // Our tools should NOT be steered even with steering ON
        assertFalse(future.isDone(), "our MCP tools should raise ConfirmEvent, not be auto-denied");
        assertEquals(1, fired.size());
        assertInstanceOf(ConfirmEvent.class, fired.get(0), "our tool should go through normal flow");
        assertTrue(fired.stream().noneMatch(e -> e instanceof McpSteeringRefusalEvent),
                "no steering refusal for our own tools");
    }

    @Test
    void steeringONStillAllowsOwnSessionConfigTree() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        Predicate<String> steeringOn = sessionId -> true;

        CompletableFuture<JsonObject> future = handlerWithSteering(fired, ownTree(), steeringOn)
                .onRequestPermission(params(externalDirectory(OWN_SPOOL_FILE, OWN_SPOOL_DIR)));

        assertTrue(fired.isEmpty(), "own session config tree is exempt from steering");
        assertTrue(future.isDone(), "should be answered immediately");
        assertEquals("once", optionId(future.get(1, TimeUnit.SECONDS)), "own tree is still allowed");
    }

    @Test
    void steeringOFFIsCompletelyUnchanged() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        Predicate<String> steeringOff = sessionId -> false;

        CompletableFuture<JsonObject> future = handlerWithSteering(fired, ownTree(), steeringOff)
                .onRequestPermission(params(simple("read", "notes.txt", "/Users/chris/Documents/notes.txt")));

        assertFalse(future.isDone(), "steering OFF: user should be asked");
        assertEquals(1, fired.size());
        assertInstanceOf(ConfirmEvent.class, fired.get(0));
    }

    /**
     * Regression: production steering is resolved under the PLUGIN session UUID (the id
     * {@link SessionRegistry} is keyed by), never under the ACP session id an individual
     * {@code session/request_permission} request carries. The ACP id looks like {@code ses_...} and no plugin
     * session is ever registered under it, so the old code — which looked the ACP id up — got {@code null}
     * and steering silently never fired.
     */
    @Test
    void steeringFiresUnderThePluginSessionIdEvenWhenTheAcpSessionIdDiffers() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        String pluginSessionId = "plugin-session-uuid-1";
        registerSteeringSession(pluginSessionId);
        try {
            // Production path: no injected predicate; the session is registered in SessionRegistry.
            OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
            }, null, ownTree(), null, pluginSessionId);

            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("title", "echo hello");
            toolCall.addProperty("kind", "execute");
            JsonObject rawInput = new JsonObject();
            rawInput.addProperty("command", "echo hello");
            toolCall.add("rawInput", rawInput);
            toolCall.add("locations", new JsonArray());
            // The ACP session id sent on the wire is OpenCode's own, deliberately different.
            JsonObject params = params(toolCall);
            params.addProperty("sessionId", "ses_acpSessionId");

            CompletableFuture<JsonObject> future = handler.onRequestPermission(params);

            assertTrue(future.isDone(), "steering auto-denies without waiting for user");
            assertEquals("reject", optionId(future.get(1, TimeUnit.SECONDS)));
            assertTrue(fired.stream().noneMatch(e -> e instanceof ConfirmEvent), "no ConfirmEvent raised");
            McpSteeringRefusalEvent event = (McpSteeringRefusalEvent) fired.stream()
                    .filter(e -> e instanceof McpSteeringRefusalEvent).findFirst().get();
            assertEquals("Execute", event.refusals().get(0).toolLabel());
            assertEquals(McpSteeringPolicy.steeringFeedbackFor(McpSteeringPolicy.Category.SHELL),
                    event.refusals().get(0).steeringText());
        } finally {
            SessionRegistry.unregister(pluginSessionId);
        }
    }

    @Test
    void steeringWithoutARegisteredPluginSessionFailsClosedToAsk() {
        List<AiProcessEvent> fired = new ArrayList<>();
        // Production path with a plugin session id that is NOT in SessionRegistry.
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        }, null, ownTree(), null, "unregistered-plugin-session");

        CompletableFuture<JsonObject> future = handler.onRequestPermission(params(
                simple("read", "notes.txt", "/Users/chris/Documents/notes.txt")));

        assertEquals(1, fired.size(), "no registered session: steering is off, user is asked");
        assertInstanceOf(ConfirmEvent.class, fired.get(0));
    }

    private static void registerSteeringSession(String pluginSessionId) {
        AiSession aiSession = AiSession.create(null, AiTypeEnum.OPENCODE);
        aiSession.settings().setMcpSteering(true);
        AbstractAiSession wrapper = new AbstractAiSession(aiSession) {
            @Override
            public String getId() {
                return pluginSessionId;
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return e -> {
                };
            }

            @Override
            public java.util.Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
                return java.util.Map.of();
            }
        };
        SessionRegistry.register(wrapper);
    }

    // ---- Helpers ----
    @Test
    void everyNamedPathIsCollectedOnceAndInOrder() {
        JsonObject toolCall = externalDirectory("/a/file.txt", "/a");
        JsonArray directories = new JsonArray();
        directories.add("/b");
        directories.add("/a");
        toolCall.getAsJsonObject("rawInput").add("directories", directories);

        assertEquals(List.of("/a/file.txt", "/a", "/b"), OpenCodeAcpClientHandler.extractAllPermissionPaths(toolCall));
        assertEquals(List.of(), OpenCodeAcpClientHandler.extractAllPermissionPaths(null));
        assertEquals(List.of(), OpenCodeAcpClientHandler.extractAllPermissionPaths(new JsonObject()));
    }
}
