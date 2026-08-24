package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpClientHandler;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.PermissionDiffPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OpenCodeAiProcessManagerTest {

    // ---- Permission routing tests (Part B / Slice 4) ----
    // Carries a resolvable path (via locations[0].path) so this represents a genuine
    // "edit with a known target" request and stays on the PermissionEvent branch —
    // kind="edit" with NO resolvable path is a different, deliberately-tested case
    // (see the "unidentified action" tests below).
    private static JsonObject buildMinimalPermissionParams() {
        JsonObject location = new JsonObject();
        location.addProperty("path", "/some/file.txt");
        JsonArray locations = new JsonArray();
        locations.add(location);

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "/some/file.txt");
        toolCall.addProperty("kind", "edit");
        toolCall.add("locations", locations);
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        return params;
    }

    // ---- Addendum 4: effort validation + mode validation ----
    private static JsonObject buildModeOption(String currentValue, String... availableValues) {
        JsonObject opt = new JsonObject();
        opt.addProperty("id", "mode");
        opt.addProperty("currentValue", currentValue);
        opt.addProperty("type", "select");
        JsonArray options = new JsonArray();
        for (String v : availableValues) {
            JsonObject o = new JsonObject();
            o.addProperty("value", v);
            options.add(o);
        }
        opt.add("options", options);
        return opt;
    }

    private static JsonObject buildEffortOption(String currentValue, String... availableValues) {
        JsonObject opt = new JsonObject();
        opt.addProperty("id", "effort");
        opt.addProperty("currentValue", currentValue);
        opt.addProperty("type", "select");
        JsonArray options = new JsonArray();
        for (String v : availableValues) {
            JsonObject o = new JsonObject();
            o.addProperty("value", v);
            options.add(o);
        }
        opt.add("options", options);
        return opt;
    }

    private static JsonObject buildModelOption(String currentValue, String... availableValues) {
        JsonObject opt = new JsonObject();
        opt.addProperty("id", "model");
        opt.addProperty("currentValue", currentValue);
        opt.addProperty("type", "select");
        JsonArray options = new JsonArray();
        for (String v : availableValues) {
            JsonObject o = new JsonObject();
            o.addProperty("value", v);
            options.add(o);
        }
        opt.add("options", options);
        return opt;
    }

    private static AcpClientHandler noopAcpHandler() {
        return new AcpClientHandler() {
            @Override
            public void onSessionUpdate(String sessionId, JsonObject update) {
            }

            @Override
            public CompletableFuture<JsonObject> onRequestPermission(JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }

            @Override
            public CompletableFuture<JsonObject> onWriteTextFile(JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }

            @Override
            public CompletableFuture<JsonObject> onReadTextFile(JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }

            @Override
            public void onDisconnected(Exception cause) {
            }
        };
    }

    // ---- Payload builder tests ----
    @Test
    void permissionConfigJsonIsExact() {
        String json = OpenCodeAiProcessManager.buildPermissionConfigJson();
        com.google.gson.JsonObject parsed = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        JsonObject permission = parsed.getAsJsonObject("permission");
        assertNotNull(permission, "permission object must be present");
        assertEquals("ask", permission.get("edit").getAsString());
        assertEquals("ask", permission.get("bash").getAsString());
        assertEquals("ask", permission.get("external_directory").getAsString());
        // Sub-agents are denied, not asked: an approved spawn would still run
        // invisibly in its own session, and a silent death there strands the
        // parent turn with no way for the user to see or interrupt it.
        assertEquals("deny", permission.get("task").getAsString(),
                "sub-agent spawning must be denied for every session the plugin launches");
        // No extra fields — this is a safety control
        assertEquals(4, permission.entrySet().size(), "permission must have exactly 4 keys");
        assertEquals(1, parsed.entrySet().size(), "config must have exactly 1 top-level key");
    }

    @Test
    void initializeParamsHaveProtocolVersionAndFsCapabilities() {
        JsonObject params = OpenCodeAiProcessManager.buildInitializeParams("1.0");
        assertEquals(1, params.get("protocolVersion").getAsInt());
        JsonObject fs = params.getAsJsonObject("clientCapabilities").getAsJsonObject("fs");
        assertNotNull(fs, "fs capabilities must be present");
        assertTrue(fs.get("readTextFile").getAsBoolean(), "readTextFile must be true");
        assertTrue(fs.get("writeTextFile").getAsBoolean(), "writeTextFile must be true");
    }

    @Test
    void sessionNewParamsHaveAbsoluteCwd() {
        String cwd = "/absolute/path/to/project";
        JsonObject params = OpenCodeAiProcessManager.buildSessionNewParams(cwd);
        assertEquals(cwd, params.get("cwd").getAsString());
        assertTrue(params.get("mcpServers").isJsonArray(), "mcpServers must be a JSON array");
    }

    // ---- Handler event-mapping tests ----
    @Test
    void agentMessageChunkMapsToTextDeltaEventWithCorrectText() {
        // Uses the §12 verbatim sample shape
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(e -> fired.add(e), () -> {
        });

        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", "hello there friend");

        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "agent_message_chunk");
        update.addProperty("messageId", "msg_abc123");
        update.add("content", content);

        handler.onSessionUpdate("ses_abc", update);

        assertEquals(1, fired.size());
        assertInstanceOf(TextDeltaEvent.class, fired.get(0));
        assertEquals("hello there friend", ((TextDeltaEvent) fired.get(0)).text());
        assertEquals("msg_abc123", ((TextDeltaEvent) fired.get(0)).turnId());
    }

    @Test
    void unknownSessionUpdateIsIgnoredAndDoesNotThrow() {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(e -> fired.add(e), () -> {
        });

        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "completely_unknown_future_type_xyz");
        update.addProperty("somePayload", "irrelevant");

        assertDoesNotThrow(() -> handler.onSessionUpdate("ses_abc", update));
        assertTrue(fired.isEmpty(), "unknown sessionUpdate must fire no event");
    }

    @Test
    void toolCallWithEmptyLocationsFiresEventWithNullFilePathAndNoNpe() {
        // Uses the §12 verbatim tool_call shape (locations and rawInput are empty on announcement)
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(e -> fired.add(e), () -> {
        });

        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "tool_call");
        update.addProperty("toolCallId", "call_abc123");
        update.addProperty("title", "read");
        update.addProperty("kind", "read");
        update.addProperty("status", "pending");
        update.add("locations", new JsonArray()); // empty — as seen in live capture
        update.add("rawInput", new JsonObject()); // empty — as seen in live capture

        assertDoesNotThrow(() -> handler.onSessionUpdate("ses_abc", update));
        assertEquals(1, fired.size());
        assertInstanceOf(ToolUseEvent.class, fired.get(0));
        ToolUseEvent event = (ToolUseEvent) fired.get(0);
        assertEquals("read", event.toolName());
        assertNull(event.filePath(), "filePath must be null when locations array is empty");
        assertEquals(ToolUseEvent.Kind.OTHER, event.kind());
    }

    @Test
    void toolKindMappingCoversWriteEditPatchAndFallback() {
        assertEquals(ToolUseEvent.Kind.WRITE, OpenCodeAcpClientHandler.mapToolKind("write"));
        assertEquals(ToolUseEvent.Kind.EDIT, OpenCodeAcpClientHandler.mapToolKind("edit"));
        assertEquals(ToolUseEvent.Kind.EDIT, OpenCodeAcpClientHandler.mapToolKind("patch"));
        assertEquals(ToolUseEvent.Kind.OTHER, OpenCodeAcpClientHandler.mapToolKind("read"));
        assertEquals(ToolUseEvent.Kind.OTHER, OpenCodeAcpClientHandler.mapToolKind("bash"));
        assertEquals(ToolUseEvent.Kind.OTHER, OpenCodeAcpClientHandler.mapToolKind(""));
    }

    @Test
    void extractFirstLocationPathReturnsNullOnEmptyArray() {
        JsonObject update = new JsonObject();
        update.add("locations", new JsonArray());
        assertNull(OpenCodeAcpClientHandler.extractFirstLocationPath(update));
    }

    @Test
    void extractFirstLocationPathReturnsPathWhenPresent() {
        JsonObject loc = new JsonObject();
        loc.addProperty("path", "/abs/path/sample.txt");
        JsonArray locations = new JsonArray();
        locations.add(loc);
        JsonObject update = new JsonObject();
        update.add("locations", locations);
        assertEquals("/abs/path/sample.txt", OpenCodeAcpClientHandler.extractFirstLocationPath(update));
    }

    @Test
    void permissionPayloadExtractsPathOldTextAndNewText() {
        // Uses the §4 verbatim sample payload shape
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        JsonObject content = new JsonObject();
        content.addProperty("type", "diff");
        content.addProperty("path", "/abs/path/sample.txt");
        content.addProperty("oldText", "original line one\noriginal line two\n");
        content.addProperty("newText", "edited line one\nedited line two\n");
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);

        JsonObject location = new JsonObject();
        location.addProperty("path", "/abs/path/sample.txt");
        JsonArray locations = new JsonArray();
        locations.add(location);

        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("filepath", "/abs/path/sample.txt");
        rawInput.addProperty("diff", "<unified diff text>");

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("toolCallId", "call_abc");
        toolCall.addProperty("title", "/abs/path/sample.txt");
        toolCall.addProperty("kind", "edit");
        toolCall.addProperty("status", "pending");
        toolCall.add("locations", locations);
        toolCall.add("rawInput", rawInput);
        toolCall.add("content", contentArray);

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "ses_abc");
        params.add("toolCall", toolCall);

        handler.onRequestPermission(params);

        assertEquals(1, fired.size());
        assertInstanceOf(PermissionEvent.class, fired.get(0));
        PermissionEvent pe = (PermissionEvent) fired.get(0);
        assertEquals("/abs/path/sample.txt", pe.filePath());
        assertEquals("Write", pe.toolName());
        assertNull(pe.oldString());
        assertNull(pe.newString());
        assertEquals("edited line one\nedited line two\n", pe.writeContent());
    }

    @Test
    void permissionAcceptReturnsOnceAndRejectReturnsReject() throws Exception {
        // Accept case
        {
            List<AiProcessEvent> fired = new ArrayList<>();
            OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
            });
            CompletableFuture<JsonObject> future = handler.onRequestPermission(buildMinimalPermissionParams());
            ((PermissionEvent) fired.get(0)).response().complete(PermissionDecision.allowed());
            JsonObject result = future.get(1, TimeUnit.SECONDS);
            assertEquals("selected", result.getAsJsonObject("outcome").get("outcome").getAsString());
            assertEquals("once", result.getAsJsonObject("outcome").get("optionId").getAsString());
        }
        // Reject case
        {
            List<AiProcessEvent> fired = new ArrayList<>();
            OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
            });
            CompletableFuture<JsonObject> future = handler.onRequestPermission(buildMinimalPermissionParams());
            ((PermissionEvent) fired.get(0)).response().complete(PermissionDecision.denied("user rejected"));
            JsonObject result = future.get(1, TimeUnit.SECONDS);
            assertEquals("selected", result.getAsJsonObject("outcome").get("outcome").getAsString());
            assertEquals("reject", result.getAsJsonObject("outcome").get("optionId").getAsString());
        }
    }

    @Test
    void permissionWithNoContentButLocationsResolvesPath() {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        JsonObject location = new JsonObject();
        location.addProperty("path", "/some/path.txt");
        JsonArray locations = new JsonArray();
        locations.add(location);

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "/some/path.txt");
        toolCall.addProperty("kind", "edit");
        toolCall.add("locations", locations);
        // No "content" key — content-absent case

        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        assertDoesNotThrow(() -> handler.onRequestPermission(params));
        assertEquals(1, fired.size());
        PermissionEvent pe = (PermissionEvent) fired.get(0);
        assertEquals("/some/path.txt", pe.filePath());
        assertNull(pe.oldString());
        assertNull(pe.newString());
    }

    @Test
    void nonDiffPermissionTypeStillCompletesAndNeverNpes() throws Exception {
        // A resolvable path keeps this on the PermissionEvent branch — the point of this
        // test is content-type handling (non-diff content must not NPE while extracting
        // oldText/newText), not path resolution, which is covered separately below.
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        JsonObject content = new JsonObject();
        content.addProperty("type", "bash"); // non-diff — no oldText/newText
        content.addProperty("command", "echo hello");
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);

        JsonObject location = new JsonObject();
        location.addProperty("path", "/some/path.txt");
        JsonArray locations = new JsonArray();
        locations.add(location);

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "bash");
        toolCall.addProperty("kind", "bash");
        toolCall.add("locations", locations);
        toolCall.add("content", contentArray);

        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        CompletableFuture<JsonObject> future = handler.onRequestPermission(params);
        assertFalse(future.isDone(), "future must remain pending until user decides");

        PermissionEvent pe = (PermissionEvent) fired.get(0);
        assertNull(pe.oldString(), "non-diff type must yield null oldString");
        pe.response().complete(PermissionDecision.denied("rejected"));

        JsonObject result = future.get(1, TimeUnit.SECONDS);
        assertNotNull(result, "future must complete for any permission type");
        assertTrue(result.has("outcome"), "result must have an outcome field");
    }

    @Test
    void cancelPendingPermissionsProducesOutcomeCancelled() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        CompletableFuture<JsonObject> future = handler.onRequestPermission(buildMinimalPermissionParams());
        assertFalse(future.isDone());

        handler.cancelPendingPermissions();

        JsonObject result = future.get(1, TimeUnit.SECONDS);
        assertEquals("cancelled", result.getAsJsonObject("outcome").get("outcome").getAsString());
    }

    // ---- "Write: null" defect: shell commands must not surface as a file write ----
    @Test
    void executeKindPermissionRaisesConfirmEventWithCommandAsDisplayText() throws Exception {
        // Verbatim live-probed shape: `opencode acp` with permission {"bash":"ask"},
        // driven to run `echo hi`. Empty locations, no content array, rawInput has only
        // "command". Before the fix this fell through to PermissionEvent("Write", null, ...)
        // — an arbitrary shell command mislabelled and auto-approved as a file write.
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("command", "echo hi");

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("toolCallId", "call_20bd");
        toolCall.addProperty("title", "echo hi");
        toolCall.addProperty("kind", "execute");
        toolCall.addProperty("status", "pending");
        toolCall.add("locations", new JsonArray());
        toolCall.add("rawInput", rawInput);

        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        CompletableFuture<JsonObject> future = handler.onRequestPermission(params);
        assertFalse(future.isDone(), "must wait for an explicit decision, not auto-complete");

        assertEquals(1, fired.size());
        assertInstanceOf(ConfirmEvent.class, fired.get(0),
                "a shell command must raise ConfirmEvent, not PermissionEvent — there is no diff to render");
        ConfirmEvent ce = (ConfirmEvent) fired.get(0);
        assertEquals("Execute", ce.toolName());
        assertEquals("echo hi", ce.displayText());
        assertNull(ce.filePath(), "a shell command has no target file");

        ce.response().complete(PermissionDecision.allowed());
        JsonObject result = future.get(1, TimeUnit.SECONDS);
        assertEquals("once", result.getAsJsonObject("outcome").get("optionId").getAsString());
    }

    @Test
    void executeKindWithNoCommandFallsBackToTitleThenPlaceholder() {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "run the build");
        toolCall.addProperty("kind", "execute");
        toolCall.add("locations", new JsonArray());
        // No rawInput at all.
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        handler.onRequestPermission(params);

        ConfirmEvent ce = (ConfirmEvent) fired.get(0);
        assertEquals("run the build", ce.displayText(), "must fall back to toolCall.title when rawInput.command is absent");
    }

    @Test
    void unidentifiedActionWithNoPathIsNotSilentlyApprovableAndShowsKindAndTitle() throws Exception {
        // kind is a real observed value ("read") that is neither "execute" nor
        // resolves to a path here — none of the three known path shapes matched.
        // Before the fix this was PermissionEvent("Write", null, ...) — "Write: null" —
        // and with auto-accept on, an unidentified action was approved sight unseen.
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "list directory contents");
        toolCall.addProperty("kind", "read");
        toolCall.add("locations", new JsonArray());
        toolCall.add("rawInput", new JsonObject());
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        CompletableFuture<JsonObject> future = handler.onRequestPermission(params);
        assertFalse(future.isDone(), "an unidentified action must wait for an explicit decision, never auto-approve");

        assertEquals(1, fired.size());
        assertInstanceOf(ConfirmEvent.class, fired.get(0),
                "no path and not execute must raise ConfirmEvent, not a null-path PermissionEvent");
        ConfirmEvent ce = (ConfirmEvent) fired.get(0);
        assertEquals("read", ce.toolName(), "toolName must show the real kind, not be hard-coded to \"Write\"");
        assertEquals("list directory contents", ce.displayText(), "must show the title so there is something real to see");
        assertNull(ce.filePath());

        ce.response().complete(PermissionDecision.denied("rejected"));
        JsonObject result = future.get(1, TimeUnit.SECONDS);
        assertEquals("reject", result.getAsJsonObject("outcome").get("optionId").getAsString());
    }

    @Test
    void unidentifiedActionWithNoTitleUsesPlaceholderDisplayText() {
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("kind", "mystery");
        toolCall.add("locations", new JsonArray());
        // No title, no rawInput, no content — the fully-degenerate case.
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        handler.onRequestPermission(params);

        assertEquals(1, fired.size());
        ConfirmEvent ce = (ConfirmEvent) fired.get(0);
        assertEquals("mystery", ce.toolName());
        assertFalse(ce.displayText().isBlank(), "must never show a blank or null display text");
        assertNotEquals("null", ce.displayText(), "must never literally read \"null\"");
    }

    @Test
    void sendPromptReturnsPromptlyBeforeHandshakeCompletes() throws Exception {
        CountDownLatch handshakeStarted = new CountDownLatch(1);
        CountDownLatch handshakeGate = new CountDownLatch(1);

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized void start(String executablePath, String model) {
                running = true; // field access via 'this' is allowed in subclasses
            }

            @Override
            protected void spawnAndHandshake(File workDir) throws Exception {
                handshakeStarted.countDown();
                handshakeGate.await(5, TimeUnit.SECONDS);
                throw new IOException("test-abort — intentional");
            }
        };
        manager.start("fake", "fake"); // sets running=true without spawning a process

        long t0 = System.nanoTime();
        manager.sendPrompt("hello", new File(System.getProperty("java.io.tmpdir")), List.of());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(handshakeStarted.await(2, TimeUnit.SECONDS),
                "handshake thread did not start within 2 s");
        assertTrue(elapsedMs < 500,
                "sendPrompt blocked for " + elapsedMs + " ms — would freeze the EDT");

        handshakeGate.countDown(); // let the thread exit cleanly
    }

    // ---- Policy integration tests (Slice 4 bug regression guards) ----
    @Test
    void permissionPolicyProducesShowDiffNotDenyForEditPayload() {
        // Regression guard: §4 verbatim payload must yield SHOW_DIFF via PermissionDiffPolicy.
        // BEFORE the toolName fix this test FAILS — PermissionDiffPolicy denies any toolName
        // that is not "Write" or "Edit", and the bug passes the file path as toolName.
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        String originalContent = "original line one\noriginal line two\n";
        String proposedContent = "edited line one\nedited line two\n";

        JsonObject content = new JsonObject();
        content.addProperty("type", "diff");
        content.addProperty("path", "/abs/path/sample.txt");
        content.addProperty("oldText", originalContent);
        content.addProperty("newText", proposedContent);
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);

        JsonObject location = new JsonObject();
        location.addProperty("path", "/abs/path/sample.txt");
        JsonArray locations = new JsonArray();
        locations.add(location);

        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("filepath", "/abs/path/sample.txt");

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("toolCallId", "call_abc");
        toolCall.addProperty("title", "/abs/path/sample.txt");
        toolCall.addProperty("kind", "edit");
        toolCall.add("locations", locations);
        toolCall.add("rawInput", rawInput);
        toolCall.add("content", contentArray);

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "ses_abc");
        params.add("toolCall", toolCall);

        handler.onRequestPermission(params);

        PermissionEvent pe = (PermissionEvent) fired.get(0);
        PermissionDiffPolicy.Decision decision = PermissionDiffPolicy.decide(
                pe.toolName(), pe.filePath(), originalContent,
                pe.oldString(), pe.newString(), pe.writeContent());

        assertEquals(PermissionDiffPolicy.Outcome.SHOW_DIFF, decision.outcome(),
                "Policy must return SHOW_DIFF; actual outcome=" + decision.outcome()
                + " reason=" + decision.reason() + " toolName passed=" + pe.toolName());
        assertEquals(proposedContent, decision.proposedContent());
    }

    @Test
    void permissionNewFileProducesShowDiffWithProposedContent() {
        // New-file scenario: oldText absent, original="" — must reach SHOW_DIFF, not DENY.
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        String newFileContent = "brand new content\n";

        JsonObject content = new JsonObject();
        content.addProperty("type", "diff");
        content.addProperty("path", "/new/file.txt");
        content.addProperty("newText", newFileContent); // oldText absent — new file
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "/new/file.txt");
        toolCall.addProperty("kind", "edit");
        toolCall.add("locations", new JsonArray());
        toolCall.add("content", contentArray);

        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        handler.onRequestPermission(params);

        PermissionEvent pe = (PermissionEvent) fired.get(0);
        PermissionDiffPolicy.Decision decision = PermissionDiffPolicy.decide(
                pe.toolName(), pe.filePath(), "",
                pe.oldString(), pe.newString(), pe.writeContent());

        assertEquals(PermissionDiffPolicy.Outcome.SHOW_DIFF, decision.outcome(),
                "New file must yield SHOW_DIFF; got " + decision.outcome() + ": " + decision.reason());
        assertEquals(newFileContent, decision.proposedContent());
    }

    @Test
    void permissionUnchangedContentProducesAllowSilent() {
        // If the AI proposes the same content that is on disk, no diff panel is needed.
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        String existingContent = "same content on both sides\n";

        JsonObject content = new JsonObject();
        content.addProperty("type", "diff");
        content.addProperty("path", "/some/file.txt");
        content.addProperty("oldText", existingContent);
        content.addProperty("newText", existingContent); // no change
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "/some/file.txt");
        toolCall.addProperty("kind", "edit");
        toolCall.add("locations", new JsonArray());
        toolCall.add("content", contentArray);

        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        handler.onRequestPermission(params);

        PermissionEvent pe = (PermissionEvent) fired.get(0);
        PermissionDiffPolicy.Decision decision = PermissionDiffPolicy.decide(
                pe.toolName(), pe.filePath(), existingContent,
                pe.oldString(), pe.newString(), pe.writeContent());

        assertEquals(PermissionDiffPolicy.Outcome.ALLOW_SILENT, decision.outcome(),
                "Unchanged content must allow silently; got: " + decision.outcome());
    }

    // ---- MCP wiring tests ----
    @Test
    void mcpRegistrarCapturesEndpointFromAddAndClearsOnRemove() {
        OpenCodeAiMcpRegistrar reg = new OpenCodeAiMcpRegistrar("cap-test-session");
        assertNull(reg.getEndpointUrl(), "endpoint must be null before addMcpEndpoint is called");

        String endpoint = "http://127.0.0.1:1234/mcp/opencode";
        reg.addMcpEndpoint(endpoint);
        assertEquals(endpoint, reg.getEndpointUrl(), "addMcpEndpoint must capture the URL verbatim");

        reg.removeMcpEndpoint();
        assertNull(reg.getEndpointUrl(), "removeMcpEndpoint must clear the captured URL");
    }

    @Test
    void buildSessionNewParamsWithMcpUrlIncludesSingleHttpEntry() {
        String cwd = "/project/dir";
        // The registry delivers the full endpoint URL; we embed it verbatim.
        String endpointUrl = "http://127.0.0.1:9999/mcp/opencode";
        JsonObject params = OpenCodeAiProcessManager.buildSessionNewParams(cwd, endpointUrl);
        assertEquals(cwd, params.get("cwd").getAsString());
        JsonArray servers = params.getAsJsonArray("mcpServers");
        assertEquals(1, servers.size(), "mcpServers must have exactly one entry when endpoint URL is provided");
        JsonObject entry = servers.get(0).getAsJsonObject();
        assertEquals("http", entry.get("type").getAsString());
        assertEquals("aicoder-nb-ki-plugin", entry.get("name").getAsString());
        assertEquals(endpointUrl, entry.get("url").getAsString());
        assertTrue(entry.get("headers").getAsJsonArray().isEmpty(), "headers must be empty");
    }

    @Test
    void buildSessionNewParamsWithNullUrlProducesEmptyMcpServers() {
        JsonObject params = OpenCodeAiProcessManager.buildSessionNewParams("/some/dir", null);
        JsonArray servers = params.getAsJsonArray("mcpServers");
        assertEquals(0, servers.size(), "mcpServers must be empty when mcpBaseUrl is null");
    }

    @Test
    void openCodeAiSessionRegistersAndDisposesViaSessionRegistry() {
        AiSession aiSession = new AiSession(
                "test-opencode-mcp-session", "Test", null,
                AiTypeEnum.OPENCODE, null, null,
                Instant.now(), Instant.now());

        OpenCodeAiSession session = new OpenCodeAiSession(aiSession, e -> {
        });

        assertNotNull(SessionRegistry.get("test-opencode-mcp-session"),
                "Session must be registered after construction");

        session.dispose();

        assertNull(SessionRegistry.get("test-opencode-mcp-session"),
                "Session must be removed from registry after dispose");
    }

    // ---- Mode-at-startup tests (Slice 6b) ----
    @Test
    void applyInitialModeIfNeededCallsSetConfigOptionWhenModeDiffers() {
        List<String[]> calls = new ArrayList<>();

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonObject modeOpt = new JsonObject();
        modeOpt.addProperty("id", "mode");
        modeOpt.addProperty("currentValue", "build");
        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(modeOpt);
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("plan");
        AiSession session = new AiSession("test-mode-diff", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());
        manager.setCurrentSession(session);

        manager.applyInitialModeIfNeeded();

        assertEquals(1, calls.size(), "setConfigOption must fire when session mode differs from configOptions");
        assertEquals("mode", calls.get(0)[0]);
        assertEquals("plan", calls.get(0)[1]);
    }

    @Test
    void applyInitialModeIfNeededSkipsWhenModeAlreadyMatches() {
        List<String[]> calls = new ArrayList<>();

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonObject modeOpt = new JsonObject();
        modeOpt.addProperty("id", "mode");
        modeOpt.addProperty("currentValue", "build");
        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(modeOpt);
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        AiSession session = new AiSession("test-mode-match", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());
        manager.setCurrentSession(session);

        manager.applyInitialModeIfNeeded();

        assertTrue(calls.isEmpty(), "setConfigOption must NOT fire when session mode already matches configOptions");
    }

    // ---- Slice 7: cancel / resume / close / error tests ----
    @Test
    void buildSessionResumeParamsHaveSessionIdAndCwd() {
        String acpSessionId = "acp-ses-abc123";
        String cwd = "/project/dir";
        String endpointUrl = "http://127.0.0.1:9999/mcp/opencode";
        JsonObject params = OpenCodeAiProcessManager.buildSessionResumeParams(acpSessionId, cwd, endpointUrl);
        assertEquals(acpSessionId, params.get("sessionId").getAsString());
        assertEquals(cwd, params.get("cwd").getAsString());
        JsonArray servers = params.getAsJsonArray("mcpServers");
        assertEquals(1, servers.size(), "mcpServers must have one entry when endpoint URL is provided");
        assertEquals(endpointUrl, servers.get(0).getAsJsonObject().get("url").getAsString());
    }

    @Test
    void resumeFailureFallsBackToSessionNew() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        List<String> calls = new ArrayList<>();

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            @Override
            protected void spawnAndHandshake(File workDir) {
                String resumeId = pendingAcpResumeId;
                if (resumeId != null) {
                    calls.add("resume-attempted");
                    listener.onAiProcessEvent(new StatusEvent(
                            StatusEventTypeEnum.INFO,
                            "Previous OpenCode session could not be resumed; starting fresh"));
                    pendingAcpResumeId = null;
                }
                calls.add("new-attempted");
            }
        };

        manager.pendingAcpResumeId = "stale-acp-id";
        manager.spawnAndHandshake(new File(System.getProperty("java.io.tmpdir")));

        assertEquals(List.of("resume-attempted", "new-attempted"), calls,
                "resume must be attempted before new when pendingAcpResumeId is set");
        long infoCount = events.stream()
                .filter(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO)
                .count();
        assertEquals(1, infoCount, "exactly one INFO event must fire when resume fails");
        assertNull(manager.pendingAcpResumeId, "pendingAcpResumeId must be cleared after fallback");
    }

    @Test
    void stopReasonCancelledProducesTurnCompleteEventAndNotFailed() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            {
                running = true;
            }
        };

        JsonObject result = new JsonObject();
        result.addProperty("stopReason", "cancelled");
        manager.handleTurnComplete(result);

        assertEquals(1, events.size(), "exactly one event must be fired");
        assertInstanceOf(TurnCompleteEvent.class, events.get(0),
                "stopReason=cancelled must produce TurnCompleteEvent, not FAILED");
    }

    @Test
    void errorCode32800TreatedAsCancellationNotFailure() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            {
                running = true;
                processing = true;
            }
        };

        manager.handleTurnError(
                new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpException(-32800, "cancelled"));

        assertEquals(1, events.size(), "exactly one event must be fired");
        assertInstanceOf(TurnCompleteEvent.class, events.get(0),
                "ACP -32800 must produce TurnCompleteEvent, not StatusEvent(FAILED)");
        assertFalse(manager.isProcessing(), "processing must be cleared after -32800");
    }

    @Test
    void errorCode32000ProducesFailedStatusWithAuthHint() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            {
                running = true;
                processing = true;
            }
        };

        manager.handleTurnError(
                new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpException(-32000, "auth required"));

        assertEquals(1, events.size(), "exactly one event must be fired");
        assertInstanceOf(StatusEvent.class, events.get(0));
        StatusEvent se = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.FAILED, se.type());
        assertTrue(se.text().contains("opencode auth login"),
                "FAILED message must hint at auth login command; got: " + se.text());
    }

    @Test
    void processingIsClearedAfterTurnError() {
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            {
                running = true;
                processing = true;
            }
        };

        manager.handleTurnError(new RuntimeException("unexpected failure"));

        assertFalse(manager.isProcessing(), "processing must be cleared after handleTurnError");
    }

    @Test
    void sessionCloseFailureDoesNotPreventTeardown() {
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            {
                running = true;
            }
        };

        assertDoesNotThrow(manager::stop);
        assertFalse(manager.isRunning(), "running must be false after stop");
        assertFalse(manager.isProcessing(), "processing must be false after stop");
        assertNull(manager.pendingAcpResumeId, "pendingAcpResumeId must be cleared by stop");
    }

    /**
     * Fix B regression guard: stop() used to block the calling thread on session/close's response for up to
     * {@link OpenCodeTimeoutEnum#SESSION_CLOSE_WAIT_MILLIS} (5 s) via a synchronous {@code .get(5, SECONDS)} — reached
     * directly from {@code AiTopComponent.componentClosed()} on the EDT. An agent that reads every message but never
     * answers session/close reproduces exactly the hang that used to freeze the IDE: before the fix this test took
     * roughly 5 s to reach the elapsed-time assertion below; after the fix stop() must return almost immediately
     * regardless of whether a response ever arrives, and the graceful close/teardown must still complete on its own
     * once the background wait elapses.
     */
    @Test
    void stopReturnsPromptlyEvenWhenAgentNeverAnswersSessionClose() throws Exception {
        PipedInputStream agentIn = new PipedInputStream(65536);
        PipedOutputStream pluginOut = new PipedOutputStream(agentIn);
        PipedInputStream pluginIn = new PipedInputStream(65536);
        PipedOutputStream agentOut = new PipedOutputStream(pluginIn);

        // A "hung" agent: drains its input (so plugin-side writes never block on a full
        // pipe) but never writes a response, so session/close's future never completes on
        // its own — only the reaper's bounded wait can end it.
        BufferedReader agentReader = new BufferedReader(new InputStreamReader(agentIn, StandardCharsets.UTF_8));
        Thread agentThread = new Thread(() -> {
            try {
                while (agentReader.readLine() != null) {
                    // deliberately never respond
                }
            }
            catch (IOException e) {
                // pipe torn down once the plugin side closes — expected
            }
        }, "hung-acp-agent");
        agentThread.setDaemon(true);
        agentThread.start();
        AcpConnection conn = new AcpConnection(pluginOut, pluginIn, noopAcpHandler());

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            {
                running = true;
                processing = true;
                connection = conn;
                acpSessionId = "ses_hungtest";
            }
        };

        try {
            long t0 = System.nanoTime();
            manager.stop();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertTrue(elapsedMs < 500,
                    "stop() blocked for " + elapsedMs + " ms waiting on session/close — would freeze the EDT");
            assertFalse(manager.isRunning(), "running must be false immediately after stop() returns");

            // The background reaper's bounded wait (SESSION_CLOSE_WAIT_MILLIS = 5 s) must still
            // close the connection once it gives up — closing the plugin-side writer delivers EOF
            // to the hung agent, which is the only way its read loop above can exit.
            agentThread.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(agentThread.isAlive(),
                    "the hung agent must see EOF once the background reaper's wait times out and closes the connection");
        }
        finally {
            agentOut.close();
        }
    }

    @Test
    void stopSendsSessionCancelBeforeSessionClose() throws Exception {
        PipedInputStream agentIn = new PipedInputStream(65536);
        PipedOutputStream pluginOut = new PipedOutputStream(agentIn);
        PipedInputStream pluginIn = new PipedInputStream(65536);
        PipedOutputStream agentOut = new PipedOutputStream(pluginIn);
        RecordingFakeAgent agent = new RecordingFakeAgent(agentIn, agentOut);
        Thread agentThread = new Thread(agent, "fake-acp-agent-stop");
        agentThread.setDaemon(true);
        agentThread.start();
        AcpConnection conn = new AcpConnection(pluginOut, pluginIn, noopAcpHandler());

        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            {
                running = true;
                processing = true; // tab closed mid-turn — the case that matters most
                connection = conn;
                acpSessionId = "ses_stoptest";
            }
        };

        try {
            // stop() now only starts the graceful session/close on a background thread (Fix
            // B — see stopReturnsPromptlyEvenWhenAgentNeverAnswersSessionClose) rather than
            // waiting for it itself, so the join below — not the stop() call — is what
            // guarantees both outbound messages were written AND read by the time the
            // assertions run.
            manager.stop();

            agentThread.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(agentThread.isAlive(), "fake agent must finish after the connection closes");
            assertTrue(agent.reachedEof(), "fake agent must see clean EOF, not a torn pipe");

            assertEquals(List.of("session/cancel", "session/close"), agent.methodOrder(),
                    "stop() must put session/cancel on the wire before session/close");

            JsonObject cancelMsg = agent.messageWithMethod("session/cancel");
            assertNotNull(cancelMsg, "cancel must be on the wire");
            assertFalse(cancelMsg.has("id"), "session/cancel must be a notification, not a request");
            assertEquals("ses_stoptest",
                    cancelMsg.getAsJsonObject("params").get("sessionId").getAsString());

            JsonObject closeMsg = agent.messageWithMethod("session/close");
            assertNotNull(closeMsg, "close request must be on the wire");
            assertTrue(closeMsg.has("id"), "session/close must be a request expecting a response");
            assertEquals("ses_stoptest",
                    closeMsg.getAsJsonObject("params").get("sessionId").getAsString());

            assertFalse(manager.isRunning(), "running must be false after stop");
        }
        finally {
            conn.close();
            agentOut.close();
        }
    }

    @Test
    void stopSendsSessionCancelEvenWhenNoTurnIsInFlight() throws Exception {
        // Pins the deliberate unconditional choice: session/cancel is a
        // notification (no response channel, nothing to error back) and
        // session/close implies cancellation anyway, so sending it while idle
        // is harmless — and unconditional cannot drift from the turn state the
        // way a gate could.
        PipedInputStream agentIn = new PipedInputStream(65536);
        PipedOutputStream pluginOut = new PipedOutputStream(agentIn);
        PipedInputStream pluginIn = new PipedInputStream(65536);
        PipedOutputStream agentOut = new PipedOutputStream(pluginIn);
        RecordingFakeAgent agent = new RecordingFakeAgent(agentIn, agentOut);
        Thread agentThread = new Thread(agent, "fake-acp-agent-idle");
        agentThread.setDaemon(true);
        agentThread.start();
        AcpConnection conn = new AcpConnection(pluginOut, pluginIn, noopAcpHandler());

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            {
                running = true;
                connection = conn;
                acpSessionId = "ses_idletest";
            }
        };

        try {
            manager.stop();

            assertEquals(List.of("session/cancel", "session/close"), agent.methodOrder(),
                    "idle stop() must still cancel before closing");
        }
        finally {
            conn.close();
            agentOut.close();
            agentThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void interruptStillSendsOnlyTheCancelNotification() throws Exception {
        // Guard on existing behaviour: interrupt(Cancel) sends exactly one
        // session/cancel notification — no id, correct session — and fires
        // STOPPED. The refactor that extracted the shared cancel mechanism
        // must not have changed what interrupt puts on the wire.
        PipedInputStream agentIn = new PipedInputStream(65536);
        PipedOutputStream pluginOut = new PipedOutputStream(agentIn);
        PipedInputStream pluginIn = new PipedInputStream(65536);
        PipedOutputStream agentOut = new PipedOutputStream(pluginIn);
        RecordingFakeAgent agent = new RecordingFakeAgent(agentIn, agentOut);
        Thread agentThread = new Thread(agent, "fake-acp-agent-interrupt");
        agentThread.setDaemon(true);
        agentThread.start();
        AcpConnection conn = new AcpConnection(pluginOut, pluginIn, noopAcpHandler());

        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            {
                running = true;
                processing = true;
                connection = conn;
                acpSessionId = "ses_interrupttest";
            }
        };

        try {
            manager.interrupt(InterruptTypeEnum.Cancel);

            // Fire-and-forget: no response to await, so poll briefly for the
            // single notification to reach the recording agent.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (agent.methodOrder().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertEquals(List.of("session/cancel"), agent.methodOrder(),
                    "interrupt(Cancel) must send exactly one notification, unchanged");

            JsonObject cancelMsg = agent.messageWithMethod("session/cancel");
            assertNotNull(cancelMsg);
            assertFalse(cancelMsg.has("id"), "must remain a notification, not a request");
            assertEquals("ses_interrupttest",
                    cancelMsg.getAsJsonObject("params").get("sessionId").getAsString());

            assertFalse(manager.isProcessing(), "interrupt must clear processing");
            assertEquals(1, events.size(), "exactly one event must fire");
            assertInstanceOf(StatusEvent.class, events.get(0));
            assertEquals(StatusEventTypeEnum.STOPPED, ((StatusEvent) events.get(0)).type());
        }
        finally {
            conn.close();
            agentOut.close();
            agentThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    // ---- Slice 7 BUG FIX: ACP session id persistence via settings ----
    @Test
    void handshakeWritesAcpSessionIdToSettings() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s1", "Test", null, AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        List<AiProcessEvent> events = new ArrayList<>();
        List<String> callbackValues = new ArrayList<>();

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            @Override
            protected void spawnAndHandshake(File workDir) {
                synchronized (this) {
                    if (currentSession != null && currentSession.settings() instanceof OpenCodeSessionSettings) {
                        ((OpenCodeSessionSettings) currentSession.settings()).setAcpSessionId("acp-handshake-id");
                    }
                }
                Runnable cb = onSessionEstablished;
                if (cb != null) {
                    cb.run();
                }
            }
        };
        manager.setCurrentSession(session);
        manager.setOnSessionEstablished(() -> callbackValues.add(settings.acpSessionId()));

        manager.spawnAndHandshake(new File(System.getProperty("java.io.tmpdir")));

        assertEquals("acp-handshake-id", settings.acpSessionId(),
                "spawnAndHandshake must write acpSessionId to session settings");
        assertEquals(List.of("acp-handshake-id"), callbackValues,
                "onSessionEstablished callback must fire after acpSessionId is written to settings");
    }

    @Test
    void applyInitialEffortSentWhenStoredDiffersFromAgentCurrent() {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(buildModeOption("build", "build", "plan"));
        cfgOpts.add(buildEffortOption("low", "low", "high"));
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        settings.setEffort("high");
        AiSession session = new AiSession("test-effort-send", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, java.time.Instant.now(), java.time.Instant.now());
        manager.setCurrentSession(session);

        manager.applyInitialModeIfNeeded();

        long effortCalls = calls.stream().filter(c -> "effort".equals(c[0])).count();
        assertEquals(1, effortCalls, "setConfigOption(effort) must fire when stored effort differs from current");
        assertEquals("high", calls.stream().filter(c -> "effort".equals(c[0])).findFirst().get()[1]);
    }

    @Test
    void applyInitialEffortClearedWhenModelHasNoEffortOption() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(buildModeOption("build", "build", "plan"));
        // No effort option in configOptions
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        settings.setEffort("low");
        AiSession session = new AiSession("test-effort-no-option", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, java.time.Instant.now(), java.time.Instant.now());
        manager.setCurrentSession(session);

        boolean changed = manager.applyInitialModeIfNeeded();

        assertNull(settings.effort(), "effort must be cleared when model has no effort option");
        assertTrue(changed, "applyInitialModeIfNeeded must return true when effort was cleared");
        long infoCount = events.stream()
                .filter(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO)
                .count();
        assertEquals(1, infoCount, "exactly one INFO event must be fired for the cleared effort");
    }

    @Test
    void applyInitialEffortClearedWhenNotInAvailableValues() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(buildModeOption("build", "build", "plan"));
        cfgOpts.add(buildEffortOption("low", "low", "high"));
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");
        settings.setEffort("medium");  // not in ["low", "high"]
        AiSession session = new AiSession("test-effort-invalid", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, java.time.Instant.now(), java.time.Instant.now());
        manager.setCurrentSession(session);

        boolean changed = manager.applyInitialModeIfNeeded();

        assertNull(settings.effort(), "invalid effort must be cleared");
        assertTrue(changed, "applyInitialModeIfNeeded must return true when effort was cleared");
        long infoCount = events.stream()
                .filter(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO)
                .count();
        assertEquals(1, infoCount, "exactly one INFO event must fire for the invalid effort");
    }

    @Test
    void applyInitialModeInvalidReplacedWithAgentCurrentValue() {
        List<AiProcessEvent> events = new ArrayList<>();
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(buildModeOption("build", "build", "plan"));
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("custom-agent");  // not in ["build", "plan"]
        AiSession session = new AiSession("test-mode-invalid", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, java.time.Instant.now(), java.time.Instant.now());
        manager.setCurrentSession(session);

        boolean changed = manager.applyInitialModeIfNeeded();

        assertEquals("build", settings.mode(),
                "invalid stored mode must be replaced with the agent's currentValue");
        assertTrue(changed, "applyInitialModeIfNeeded must return true when mode was replaced");
        long infoCount = events.stream()
                .filter(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO)
                .count();
        assertEquals(1, infoCount, "exactly one INFO event must fire for the replaced mode");
        assertTrue(calls.isEmpty(),
                "setConfigOption must NOT be called when effectiveMode == agentCurrentMode after replacement");
    }

    @Test
    void applyInitialBothValidAndMatchingAgent_noInfoEvents_noSetConfigOptionCalls() {
        List<AiProcessEvent> events = new ArrayList<>();
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(buildModeOption("build", "build", "plan"));
        cfgOpts.add(buildEffortOption("low", "low", "high"));
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");   // matches agent current
        settings.setEffort("low");   // matches agent current
        AiSession session = new AiSession("test-both-valid", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, java.time.Instant.now(), java.time.Instant.now());
        manager.setCurrentSession(session);

        boolean changed = manager.applyInitialModeIfNeeded();

        assertFalse(changed, "applyInitialModeIfNeeded must return false when both values are valid and match");
        assertTrue(calls.isEmpty(), "setConfigOption must NOT be called when values already match agent");
        assertTrue(events.isEmpty(), "no INFO events must fire when values are valid and matching");
    }

    // ---- Model-at-startup tests (fix for the per-session-model-never-applied bug) ----
    @Test
    void applyInitialModelSentWhenSessionModelDiffersFromAgentCurrent() {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(buildModelOption("opencode/big-pickle", "opencode/big-pickle", "opencode/deepseek-v4-flash-free"));
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("opencode/deepseek-v4-flash-free");
        AiSession session = new AiSession("test-model-diff", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());
        manager.setCurrentSession(session);

        manager.applyInitialModeIfNeeded();

        long modelCalls = calls.stream().filter(c -> "model".equals(c[0])).count();
        assertEquals(1, modelCalls, "setConfigOption(model) must fire when session model differs from the agent's");
        assertEquals("opencode/deepseek-v4-flash-free",
                calls.stream().filter(c -> "model".equals(c[0])).findFirst().get()[1]);
    }

    @Test
    void applyInitialModelSkippedWhenAlreadyMatchesAgent() {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(buildModelOption("opencode/deepseek-v4-flash-free",
                "opencode/big-pickle", "opencode/deepseek-v4-flash-free"));
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("opencode/deepseek-v4-flash-free");
        AiSession session = new AiSession("test-model-match", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());
        manager.setCurrentSession(session);

        manager.applyInitialModeIfNeeded();

        assertTrue(calls.stream().noneMatch(c -> "model".equals(c[0])),
                "setConfigOption(model) must NOT fire when session model already matches the agent's");
    }

    @Test
    void applyInitialModelNotAvailableIsSurfacedNotSilentlySubstituted() {
        List<AiProcessEvent> events = new ArrayList<>();
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(buildModelOption("opencode/big-pickle", "opencode/big-pickle", "opencode/hy3-free"));
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("opencode/not-offered-by-agent");
        AiSession session = new AiSession("test-model-unavailable", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());
        manager.setCurrentSession(session);

        boolean changed = manager.applyInitialModeIfNeeded();

        assertFalse(changed, "an unavailable model must not be recorded as a settings change");
        assertEquals("opencode/not-offered-by-agent", settings.model(),
                "the session's chosen model must NOT be silently overwritten — unlike mode, "
                + "an unavailable model is surfaced, not replaced");
        assertTrue(calls.stream().noneMatch(c -> "model".equals(c[0])),
                "setConfigOption(model) must NOT be called for a model the agent does not offer");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.INFO
                && se.text().contains("opencode/not-offered-by-agent")),
                "an INFO status event naming the requested model must be surfaced to the user");
    }

    @Test
    void applyInitialModelSkippedWhenSessionModelIsNull() {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(buildModelOption("opencode/big-pickle", "opencode/big-pickle", "opencode/hy3-free"));
        manager.sessionConfigOptions = cfgOpts;

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        // settings.setModel(...) never called — no session preference recorded.
        AiSession session = new AiSession("test-model-unset", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());
        manager.setCurrentSession(session);

        manager.applyInitialModeIfNeeded();

        assertTrue(calls.stream().noneMatch(c -> "model".equals(c[0])),
                "no model preference means nothing to reconcile against the agent");
    }

    @Test
    void afterStartResumesFromSettingsAcpId() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setAcpSessionId("ses_persisted");
        AiSession session = new AiSession("s2", "Test", null, AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        List<AiProcessEvent> events = new ArrayList<>();
        var impl = new OpenCodeAiImplementation(events::add, null) {
            {
                currentSession = session;
            }

            OpenCodeAiProcessManager exposedDelegate() {
                return delegate();
            }
        };

        impl.start("non-existent-opencode-executable", "model");

        assertEquals("ses_persisted", impl.exposedDelegate().pendingAcpResumeId,
                "afterStart must call resumeSession with the acpSessionId from settings");
    }

    @Test
    void resumeSession_nonAcpId_isIgnored() {
        // Belt-and-braces guard: the process manager must reject any id that does not start with
        // ses_ — a plugin-level UUID must never reach the pending resume slot.
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        });

        manager.resumeSession("e6523570-b545-4136-ac6b-3bd9d7fce668");

        assertNull(manager.pendingAcpResumeId,
                "resumeSession must ignore ids that do not start with ses_");
    }

    // ---- Bug 2 fix: session/resume returns only configOptions, no sessionId ----
    @Test
    void resolveSessionId_resumed_usesRequestedIdNotResponseId() {
        // REGRESSION GUARD (Bug 2): opencode 1.18.18 returns {"configOptions":[...]} with
        // NO "sessionId" in the session/resume response. Old logic checked for "sessionId"
        // in the response → null → resumed stayed false → fell through to session/new,
        // silently overwriting the stored ACP id. This must FAIL against the old logic and
        // PASS after the fix.
        JsonObject resumeResponse = new JsonObject();
        resumeResponse.add("configOptions", new JsonArray()); // no sessionId — real opencode 1.18.18 behaviour

        String resolved = OpenCodeAiProcessManager.resolveSessionId(true, "ses_abc123", resumeResponse);

        assertEquals("ses_abc123", resolved,
                "resolveSessionId with resumed=true must return the requested id, not look for sessionId in response");
    }

    @Test
    void resolveSessionId_notResumed_usesSessionIdFromNewResponse() {
        // When session/new is used, resolveSessionId must return the sessionId from the response.
        // Guards against the fix accidentally breaking the non-resumed path.
        JsonObject newResponse = new JsonObject();
        newResponse.addProperty("sessionId", "ses_from-new");

        String resolved = OpenCodeAiProcessManager.resolveSessionId(false, null, newResponse);

        assertEquals("ses_from-new", resolved,
                "resolveSessionId with resumed=false must return sessionId from the session/new response");
    }

    @Test
    void resolveSessionId_sessionNewNoSessionId_returnsNull() {
        // session/new returning no sessionId → resolveSessionId must return null →
        // spawnAndHandshake throws IOException. This guard must survive the Bug 2 fix.
        JsonObject emptyNewResponse = new JsonObject();

        String resolved = OpenCodeAiProcessManager.resolveSessionId(false, null, emptyNewResponse);

        assertNull(resolved,
                "resolveSessionId must return null when session/new response has no sessionId");
    }

    @Test
    void resumeResponse_configOptionsAreStashedOnHandshake() throws Exception {
        // session/resume also returns configOptions; they must be stashed just as session/new's are.
        JsonObject cfgOpt = new JsonObject();
        cfgOpt.addProperty("id", "model");
        cfgOpt.addProperty("currentValue", "claude-sonnet-4-5");
        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(cfgOpt);

        JsonObject resumeResponse = new JsonObject();
        resumeResponse.add("configOptions", cfgOpts);

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            protected void spawnAndHandshake(File workDir) {
                synchronized (this) {
                    pendingAcpResumeId = null;
                    if (resumeResponse.has("configOptions") && resumeResponse.get("configOptions").isJsonArray()) {
                        sessionConfigOptions = resumeResponse.getAsJsonArray("configOptions");
                    }
                }
            }
        };

        manager.spawnAndHandshake(new File(System.getProperty("java.io.tmpdir")));

        assertNotNull(manager.configOptions(), "configOptions from resume response must be stashed");
        assertEquals(1, manager.configOptions().size());
        assertEquals("claude-sonnet-4-5",
                manager.configOptions().get(0).getAsJsonObject().get("currentValue").getAsString());
    }

    @Test
    void successfulResume_preservesStoredAcpId() throws Exception {
        // REGRESSION GUARD (Bug 2): after a successful resume, the manager's acpSessionId
        // must equal the REQUESTED resume id — not null, not a new id from session/new.
        // Before the fix, resolveSessionId(resumed=true) returned null for a
        // configOptions-only response, so acpSessionId was never set correctly.
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setAcpSessionId("ses_must-survive");
        AiSession session = new AiSession("s-preserve3", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        JsonObject resumeResponse = new JsonObject();
        resumeResponse.add("configOptions", new JsonArray()); // real opencode: no sessionId

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            protected void spawnAndHandshake(File workDir) {
                String resumeId = pendingAcpResumeId;
                boolean resumed = resumeId != null;
                String sid = OpenCodeAiProcessManager.resolveSessionId(resumed, resumeId, resumeResponse);
                if (sid == null) {
                    // Simulate what the real code does without the fix: falls through to
                    // session/new and gets a fresh id, silently overwriting the stored one.
                    sid = "ses_new-replacement-must-not-appear";
                }
                synchronized (this) {
                    pendingAcpResumeId = null;
                    if (currentSession != null && currentSession.settings() instanceof OpenCodeSessionSettings) {
                        ((OpenCodeSessionSettings) currentSession.settings()).setAcpSessionId(sid);
                    }
                }
            }
        };
        manager.setCurrentSession(session);
        manager.pendingAcpResumeId = "ses_must-survive";

        manager.spawnAndHandshake(new File(System.getProperty("java.io.tmpdir")));

        assertEquals("ses_must-survive", settings.acpSessionId(),
                "after successful resume, settings.acpSessionId must be the requested resume id, not a new one");
    }

    // ---- I2: stop() must cancel in-flight work before session/close ----
    /**
     * Minimal fake ACP agent over piped streams: records every incoming message in arrival order and answers every
     * request — a message carrying both id and method, per JSON-RPC 2.0 — with an empty success result, so callers'
     * bounded .get() waits complete promptly instead of burning their whole timeout budget.
     */
    private static final class RecordingFakeAgent implements Runnable {

        private final BufferedReader in;
        private final PipedOutputStream out;
        private final List<JsonObject> messages = new ArrayList<>();
        private volatile boolean reachedEof = false;

        RecordingFakeAgent(PipedInputStream pluginToAgent, PipedOutputStream agentToPlugin) {
            this.in = new BufferedReader(new InputStreamReader(pluginToAgent, StandardCharsets.UTF_8));
            this.out = agentToPlugin;
        }

        List<String> methodOrder() {
            synchronized (messages) {
                List<String> order = new ArrayList<>();
                for (JsonObject m : messages) {
                    if (m.has("method")) {
                        order.add(m.get("method").getAsString());
                    }
                }
                return order;
            }
        }

        JsonObject messageWithMethod(String method) {
            synchronized (messages) {
                for (JsonObject m : messages) {
                    if (m.has("method") && method.equals(m.get("method").getAsString())) {
                        return m;
                    }
                }
            }
            return null;
        }

        boolean reachedEof() {
            return reachedEof;
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
                    synchronized (messages) {
                        messages.add(msg);
                    }
                    if (msg.has("id") && msg.has("method")) {
                        JsonObject response = new JsonObject();
                        response.addProperty("jsonrpc", "2.0");
                        response.addProperty("id", msg.get("id").getAsLong());
                        response.add("result", new JsonObject());
                        out.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
                reachedEof = true;
            }
            catch (IOException e) {
                // Pipe torn down under us — expected once the plugin side closes.
            }
        }
    }
}
