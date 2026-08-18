package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.PermissionDiffPolicy;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OpenCodeAiProcessManagerTest {

    // ---- Permission routing tests (Part B / Slice 4) ----
    private static JsonObject buildMinimalPermissionParams() {
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "/some/file.txt");
        toolCall.addProperty("kind", "edit");
        toolCall.add("locations", new JsonArray());
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
        // No extra fields — this is a safety control
        assertEquals(3, permission.entrySet().size(), "permission must have exactly 3 keys");
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
        List<AiProcessEvent> fired = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(fired::add, () -> {
        });

        JsonObject content = new JsonObject();
        content.addProperty("type", "bash"); // non-diff — no oldText/newText
        content.addProperty("command", "echo hello");
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "bash");
        toolCall.addProperty("kind", "bash");
        toolCall.add("locations", new JsonArray());
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
}
