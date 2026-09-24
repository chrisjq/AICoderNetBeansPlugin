package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves end-to-end that a Claude-shaped {@code PreToolUse} hook POST carrying a pi session's OWN PLUGIN UUID
 * — exactly what {@code aicoder-pi-extension.ts.template}'s {@code AICODER_CONFIG.sessionId} bakes in at
 * generation time, per {@code PiExtensionGenerator} — resolves that session in {@link McpHookServer} and
 * reaches a {@link PermissionEvent}, without any {@link SessionRegistry#registerAlias} step. Unlike Claude
 * (whose own CLI-internal session id is discovered from its stream and mapped onto the plugin session as a
 * SEPARATE alias — see {@code ClaudeAiSession#registerClaudeSessionAlias}), pi needs no alias at all: the id
 * the hook POST carries and the id the session is registered under are the SAME plugin UUID, so only the
 * primary {@link SessionRegistry#register} ever runs for a pi session — this test never calls
 * {@code registerAlias}.
 *
 * <p>
 * Drives the real HTTP endpoint (an actual {@link McpHookServer} bound to an OS-assigned port via
 * {@link McpServerRegistry#portOverride}, POSTed to with {@link HttpClient}) rather than reflecting into the
 * private {@code handle}/{@code handleRequest} methods, since a real request/response round trip is the more
 * direct proof of "resolves end-to-end" the review asked for.
 */
class PiHookIntegrationTest {

    private String sessionId;
    private McpHookServer server;

    @BeforeEach
    void setUp() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        // Must be set BEFORE register() spawns the supervisor (mirrors McpServerRegistryTest's own setUp): the
        // supervisor reads this field fresh only at the START of each QUEUE.poll() call, so changing it after the
        // supervisor is already parked in its first (60-second default) poll would not take effect until that poll
        // times out — exactly the bug that made hookLockSurvivesAServerSwap's health-tick wait time out (build-12).
        McpServerRegistry.pollIntervalMillis = 100;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("pi-hook-test")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        server = McpServerRegistry.getServer();
        // The plugin session UUID — exactly what AICODER_CONFIG.sessionId is generated with (PiExtensionGenerator),
        // and what the hook POST below carries as session_id. No alias is ever registered for it.
        sessionId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        SessionRegistry.unregister(sessionId);
        // This fixture creates a uniquely generated test session's real config tree
        // to exercise the production own-config scope check; always remove it, even
        // when an assertion fails, so no test memory survives in the user's profile.
        PluginUtil.deleteAiSessionConfigDir(AiTypeEnum.PI, sessionId);
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
        McpServerRegistry.pollIntervalMillis = 60000;
    }

    @Test
    void denyDecision_resolvesTheSessionFiresPermissionEventAndRespondsWithTheDeniedShape(@TempDir Path projectDir) throws Exception {
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");
        RecordingListener listener = new RecordingListener(PermissionDecision.denied("test denial reason"));
        SessionRegistry.register(new FakeSession(sessionId, listener));
        server.registerSession(sessionId, AiTypeEnum.PI, List.of(projectDir.toFile()), true);

        JsonObject response = postHook(writeHookBody(sessionId, target.toString(), "changed"));

        assertEquals(1, listener.events.size(), "exactly one PermissionEvent must have fired");
        assertInstanceOf(PermissionEvent.class, listener.events.get(0));
        PermissionEvent event = (PermissionEvent) listener.events.get(0);
        assertEquals("Write", event.toolName());
        assertEquals(target.toString(), event.filePath());
        assertEquals("changed", event.writeContent());

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("deny", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertEquals("test denial reason", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION_REASON.key()).getAsString());
        assertEquals("original", Files.readString(target), "a denied write must never touch the file");
    }

    @Test
    void allowDecision_appliesTheWriteAndRespondsWithTheSuccessReasonPiStreamJsonParserRecognises(@TempDir Path projectDir) throws Exception {
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        SessionRegistry.register(new FakeSession(sessionId, listener));
        server.registerSession(sessionId, AiTypeEnum.PI, List.of(projectDir.toFile()), true);

        JsonObject response = postHook(writeHookBody(sessionId, target.toString(), "changed"));

        assertEquals(1, listener.events.size(), "exactly one PermissionEvent must have fired");
        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        // Verified quirk: an ALLOWED decision is applied server-side and still comes back as
        // permissionDecision:"deny" with a reason starting McpHookServerUtil.APPLIED_BY_PLUGIN_PREFIX — the extension
        // template matches that exact literal to rewrite it into "SUCCESS — the user accepted..." so pi's own
        // edit/write tool does not run a second time. Asserted against the constant, not a hardcoded literal, so
        // wording drift here fails this test instead of silently double-applying every accepted pi write.
        assertEquals("deny", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        String reason = output.get(ClaudeHookKeyEnum.PERMISSION_DECISION_REASON.key()).getAsString();
        assertTrue(reason.startsWith(McpHookServerUtil.APPLIED_BY_PLUGIN_PREFIX), reason);
        assertEquals("changed", Files.readString(target), "an allowed write must actually land on disk");
    }

    @Test
    void unregisteredSessionId_defersRatherThanReachingPermissionEvent(@TempDir Path projectDir) throws Exception {
        // Negative control: sessionId was generated in setUp() but NEVER registered anywhere (no SessionRegistry
        // entry, no registerSession() hook lock) — proving the two positive tests above succeed BECAUSE of that
        // registration, not because of some pre-existing bypass.
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");

        JsonObject response = postHook(writeHookBody(sessionId, target.toString(), "changed"));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("defer", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertEquals("original", Files.readString(target));
    }

    @Test
    void unregisteredSessionId_newlyMatchedToolAllowsRatherThanDeferring() throws Exception {
        JsonObject response = postHook(toolHookBody(sessionId, "Read"));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("allow", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
    }

    @Test
    void qualifiedPluginMcpTool_fastPathsBeforeSessionLookup() throws Exception {
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        FakeSession knownSession = new FakeSession(sessionId, listener);
        knownSession.getSettings().setMcpSteering(true);
        SessionRegistry.register(knownSession);

        JsonObject response = postHook(toolHookBody(sessionId, qualifiedMcpToolName(McpToolEnum.GET_FILE_CONTENT)));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("allow", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertTrue(listener.events.isEmpty(), "the plugin MCP call must bypass steering before session policy");
    }

    @Test
    void wrongSessionIdDoesNotInheritAnotherSessionsSteering() throws Exception {
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        FakeSession knownSession = new FakeSession(sessionId, listener);
        knownSession.getSettings().setMcpSteering(true);
        SessionRegistry.register(knownSession);

        String wrongSessionId = UUID.randomUUID().toString();
        JsonObject response = postHook(toolHookBody(wrongSessionId, "Read"));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("allow", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString(),
                "a wrong session ID must not inherit the registered session's steering policy");
        assertTrue(listener.events.isEmpty());
    }

    @Test
    void matchingSessionIdSelectsItsOwnSteeringPolicy() throws Exception {
        String offSessionId = UUID.randomUUID().toString();
        RecordingListener onListener = new RecordingListener(PermissionDecision.allowed());
        RecordingListener offListener = new RecordingListener(PermissionDecision.allowed());
        FakeSession onSession = new FakeSession(sessionId, onListener);
        FakeSession offSession = new FakeSession(offSessionId, offListener);
        onSession.getSettings().setMcpSteering(true);
        offSession.getSettings().setMcpSteering(false);
        SessionRegistry.register(onSession);
        SessionRegistry.register(offSession);

        try {
            JsonObject onResponse = postHook(toolHookBody(sessionId, "Read"));
            JsonObject offResponse = postHook(toolHookBody(offSessionId, "Read"));

            assertEquals("deny",
                    onResponse.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                            .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
            assertEquals("allow",
                    offResponse.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                            .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
            assertTrue(onListener.events.isEmpty());
            assertTrue(offListener.events.isEmpty());
        } finally {
            SessionRegistry.unregister(offSessionId);
        }
    }

    @Test
    void steeringDisabled_newlyMatchedToolKeepsItsExistingAllowResponse() throws Exception {
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        FakeSession knownSession = new FakeSession(sessionId, listener);
        knownSession.getSettings().setMcpSteering(false);
        SessionRegistry.register(knownSession);

        JsonObject response = postHook(toolHookBody(sessionId, "Read"));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("allow", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertTrue(listener.events.isEmpty(), "read must not reach a write PermissionEvent");
    }

    @Test
    void steeringSettingChangesAreObservedAndOffKeepsWriteReviewGate(@TempDir Path projectDir) throws Exception {
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");
        RecordingListener listener = new RecordingListener(PermissionDecision.denied("review denied"));
        FakeSession knownSession = new FakeSession(sessionId, listener);
        SessionRegistry.register(knownSession);
        server.registerSession(sessionId, AiTypeEnum.PI, List.of(projectDir.toFile()), true);

        knownSession.getSettings().setMcpSteering(false);
        JsonObject offRead = postHook(toolHookBody(sessionId, "Read"));
        assertEquals("allow", offRead.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());

        knownSession.getSettings().setMcpSteering(true);
        JsonObject onRead = postHook(toolHookBody(sessionId, "Read"));
        assertEquals("deny", onRead.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());

        knownSession.getSettings().setMcpSteering(false);
        JsonObject offWrite = postHook(writeHookBody(sessionId, target.toString(), "changed"));
        assertEquals("deny", offWrite.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertEquals(1, listener.events.size(), "steering OFF must retain the write review event");
        assertEquals("original", Files.readString(target));
    }

    @Test
    void steeringEnabled_deniesNativeToolWithReplacementGuidance() throws Exception {
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        FakeSession knownSession = new FakeSession(sessionId, listener);
        knownSession.getSettings().setMcpSteering(true);
        SessionRegistry.register(knownSession);

        JsonObject response = postHook(toolHookBody(sessionId, "Read"));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("deny", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertTrue(output.get(ClaudeHookKeyEnum.PERMISSION_DECISION_REASON.key()).getAsString()
                .contains(McpToolEnum.GET_FILE_CONTENT.toolName()));
        assertTrue(listener.events.isEmpty(), "steering must deny before a permission event");
    }

    @Test
    void steeringEnabled_ownSessionConfigWriteKeepsItsSilentAllow() throws Exception {
        RecordingListener listener = new RecordingListener(PermissionDecision.denied("must not be asked"));
        FakeSession knownSession = new FakeSession(sessionId, listener);
        knownSession.getSettings().setMcpSteering(true);
        SessionRegistry.register(knownSession);
        server.registerSession(sessionId, AiTypeEnum.PI, List.of(), true);
        Path memory = server.sessionConfigDirOrNull(sessionId).resolve("memory.json");
        Files.createDirectories(memory.getParent());
        Files.writeString(memory, "{}");

        JsonObject response = postHook(writeHookBody(sessionId, memory.toString(), "updated"));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("allow", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertTrue(listener.events.isEmpty(), "own-session memory must not reach a permission event");
    }

    @Test
    void steeringEnabled_unrestrictedOffProjectWriteKeepsItsSilentAllow(@TempDir Path outsideDir) throws Exception {
        RecordingListener listener = new RecordingListener(PermissionDecision.denied("must not be asked"));
        FakeSession knownSession = new FakeSession(sessionId, listener);
        knownSession.getSettings().setMcpSteering(true);
        SessionRegistry.register(knownSession);
        server.registerSession(sessionId, AiTypeEnum.PI, List.of(), false);
        Path target = outsideDir.resolve("outside.txt");
        Files.writeString(target, "original");

        JsonObject response = postHook(writeHookBody(sessionId, target.toString(), "updated"));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("allow", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertTrue(listener.events.isEmpty(), "an off-project silent allow must not reach a permission event");
    }

    @Test
    void steeringEnabled_allConfirmedClaudeNativeToolsAreRefused(@TempDir Path projectDir) throws Exception {
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        FakeSession knownSession = new FakeSession(sessionId, listener);
        knownSession.getSettings().setMcpSteering(true);
        SessionRegistry.register(knownSession);
        server.registerSession(sessionId, AiTypeEnum.PI, List.of(projectDir.toFile()), true);

        for (String toolName : List.of("Read", "NotebookEdit", "Bash", "Grep", "Glob", "WebFetch")) {
            JsonObject output = postHook(steeredNativeToolHookBody(sessionId, toolName, target))
                    .getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
            assertEquals("deny", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString(), toolName);
        }
        assertEquals("original", Files.readString(target));
        assertTrue(listener.events.isEmpty(), "steering must deny before a permission event");
    }

    @Test
    void steeringEnabled_nativeWriteStillReachesReviewGate(@TempDir Path projectDir) throws Exception {
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");
        RecordingListener listener = new RecordingListener(PermissionDecision.denied("review denied"));
        SessionRegistry.register(new FakeSession(sessionId, listener));
        server.registerSession(sessionId, AiTypeEnum.PI, List.of(projectDir.toFile()), true);
        SessionRegistry.get(sessionId).getSettings().setMcpSteering(true);

        JsonObject response = postHook(writeHookBody(sessionId, target.toString(), "changed"));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("deny", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertEquals("review denied",
                output.get(ClaudeHookKeyEnum.PERMISSION_DECISION_REASON.key()).getAsString());
        assertEquals(1, listener.events.size(), "native write must retain the existing review gate");
        assertEquals("original", Files.readString(target));
    }

    @Test
    void steeringEnabled_nativeEditStillReachesReviewGate(@TempDir Path projectDir) throws Exception {
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");
        RecordingListener listener = new RecordingListener(PermissionDecision.denied("review denied"));
        SessionRegistry.register(new FakeSession(sessionId, listener));
        server.registerSession(sessionId, AiTypeEnum.PI, List.of(projectDir.toFile()), true);
        SessionRegistry.get(sessionId).getSettings().setMcpSteering(true);

        JsonObject response = postHook(steeredNativeToolHookBody(sessionId, "Edit", target));

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("deny", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertEquals("review denied",
                output.get(ClaudeHookKeyEnum.PERMISSION_DECISION_REASON.key()).getAsString());
        assertEquals(1, listener.events.size(), "native edit must retain the existing review gate");
        assertEquals("original", Files.readString(target));
    }

    @Test
    void claudeNativeWriteAndEditReachReviewButNotebookEditRemainsSteered(@TempDir Path projectDir) throws Exception {
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");
        RecordingListener listener = new RecordingListener(PermissionDecision.denied("review denied"));
        SessionRegistry.register(new FakeSession(sessionId, listener, AiTypeEnum.CLAUDE));
        server.registerSession(sessionId, AiTypeEnum.CLAUDE, List.of(projectDir.toFile()), true);
        SessionRegistry.get(sessionId).getSettings().setMcpSteering(true);

        JsonObject writeResponse = postHook(writeHookBody(sessionId, target.toString(), "changed"));
        JsonObject editResponse = postHook(steeredNativeToolHookBody(sessionId, "Edit", target));
        JsonObject notebookResponse = postHook(toolHookBody(sessionId, "NotebookEdit"));

        assertEquals("deny", writeResponse.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertEquals("review denied", writeResponse.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                .get(ClaudeHookKeyEnum.PERMISSION_DECISION_REASON.key()).getAsString());
        assertEquals("deny", editResponse.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertEquals("review denied", editResponse.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                .get(ClaudeHookKeyEnum.PERMISSION_DECISION_REASON.key()).getAsString());
        assertEquals("deny", notebookResponse.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertEquals(2, listener.events.size(), "only Edit and Write reach the diff review gate");
        assertEquals("original", Files.readString(target));
    }

    @Test
    void successfulNativeUseIsLoggedButSteeringRefusalIsNot() throws Exception {
        boolean previous = PluginSettings.isLogToolUse();
        Logger logger = Logger.getLogger(McpHookServerUtil.class.getName());
        List<String> captured = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            PluginSettings.setLogToolUse(true);
            FakeSession knownSession = new FakeSession(sessionId, new RecordingListener(PermissionDecision.allowed()));
            SessionRegistry.register(knownSession);

            knownSession.getSettings().setMcpSteering(false);
            JsonObject allowed = postHook(toolHookBody(sessionId, "Read"));
            assertEquals("allow", allowed.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                    .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
            assertEquals(1, captured.size());
            assertTrue(captured.get(0).contains("Tool Used: Read"));

            captured.clear();
            knownSession.getSettings().setMcpSteering(true);
            JsonObject refused = postHook(toolHookBody(sessionId, "Read"));
            assertEquals("deny", refused.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                    .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
            assertTrue(captured.isEmpty(), "a steered refusal must not be logged as a successful tool use");
        } finally {
            logger.removeHandler(capture);
            PluginSettings.setLogToolUse(previous);
        }
    }

    @Test
    void successfulNativeWriteIsLoggedAfterReviewApplies(@TempDir Path projectDir) throws Exception {
        boolean previous = PluginSettings.isLogToolUse();
        Logger logger = Logger.getLogger(McpHookServerUtil.class.getName());
        List<String> captured = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            PluginSettings.setLogToolUse(true);
            Path target = projectDir.resolve("Foo.txt");
            Files.writeString(target, "original");
            SessionRegistry.register(new FakeSession(sessionId,
                    new RecordingListener(PermissionDecision.allowed())));
            server.registerSession(sessionId, AiTypeEnum.PI, List.of(projectDir.toFile()), true);

            JsonObject response = postHook(writeHookBody(sessionId, target.toString(), "changed"));

            assertEquals("deny", response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key())
                    .get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
            assertEquals("changed", Files.readString(target));
            assertEquals(1, captured.size());
            assertTrue(captured.get(0).contains("Tool Used: Write"));
        } finally {
            logger.removeHandler(capture);
            PluginSettings.setLogToolUse(previous);
        }
    }

    @Test
    void steeringEnabled_harnessAndOtherServerToolsKeepTheirAllowResponse() throws Exception {
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        FakeSession knownSession = new FakeSession(sessionId, listener);
        knownSession.getSettings().setMcpSteering(true);
        SessionRegistry.register(knownSession);

        for (String toolName : List.of("ToolSearch", "Task", "mcp__context7__resolve-library-id")) {
            JsonObject output = postHook(toolHookBody(sessionId, toolName))
                    .getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
            assertEquals("allow", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString(), toolName);
        }
        assertTrue(listener.events.isEmpty(), "non-native tools must not reach a permission event");
    }

    @Test
    void steeringEnabled_missingToolNameAllowsRatherThanFailing() throws Exception {
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        FakeSession knownSession = new FakeSession(sessionId, listener);
        knownSession.getSettings().setMcpSteering(true);
        SessionRegistry.register(knownSession);
        JsonObject body = toolHookBody(sessionId, "Read");
        body.remove(ClaudeHookKeyEnum.TOOL_NAME.key());

        JsonObject response = postHook(body);

        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("allow", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertTrue(listener.events.isEmpty(), "unrecognised tools must not reach a permission event");
    }

    @Test
    void sessionKnownButNeverScoped_selfHealsToAnEmptyScopeAndDeniesRatherThanReachingPermissionEvent(@TempDir Path projectDir) throws Exception {
        // Corrected after a real full-suite failure (Boss build-7): this does NOT isolate "hook lock missing" as
        // originally intended — see the long note below for why that specific claim is not provable through this
        // public API — but it is still a genuine, useful fail-closed proof in its own right: a session the plugin
        // knows about (SessionRegistry) but never explicitly scoped (server.registerSession(...) was deliberately
        // never called) must still refuse rather than treat "unscoped" as "anything goes".
        //
        // What actually happens: SessionFileScopeRegistry.isWithinProjectDirs() self-heals an unscoped-but-known
        // session from SessionRegistry + the currently open projects (McpHookServer.java's own comment: "the
        // specific failure mode behind #15/#21"). In this test JVM there are no open projects, so the healed scope
        // has an EMPTY project-dir list — the file (outside every project) is denied by the EARLIER "outside every
        // open project" branch (McpHookServer.handleRequest, ~569-574), which returns immediately for BOTH its
        // allow and deny sub-cases and therefore NEVER reaches the per-session hook lock a few lines further down.
        //
        // Why "hook lock is a separate necessary gate" cannot be isolated here: the ONLY way to give a session a
        // non-empty, matching project-dir list — the one thing that lets a request past that earlier branch at all
        // — is server.registerSession()/registerScope(), and that SAME call also creates the hook lock in the same
        // breath (see PiHookIntegrationTest's positive tests above, and McpHookServer.registerSession()'s own
        // fileScope.registerScope() + hookLocks.put() pair). There is no public path that populates one without the
        // other, short of opening a real NetBeans project in this test (so isUnderAnyOpenProject() alone lets the
        // file through) or reflecting into the private hookLocks map — both disproportionate to this one gap.
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        SessionRegistry.register(new FakeSession(sessionId, listener));

        JsonObject response = postHook(writeHookBody(sessionId, target.toString(), "changed"));

        assertTrue(listener.events.isEmpty(), "an unscoped session must never reach PermissionEvent");
        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertEquals("deny", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString());
        assertEquals(target + " is outside the allowed project scope for this session.",
                stripAccessDeniedPrefix(output.get(ClaudeHookKeyEnum.PERMISSION_DECISION_REASON.key()).getAsString()));
        assertEquals("original", Files.readString(target));
    }

    @Test
    void hookLockSurvivesAServerSwap_stillReachesPermissionEventInsteadOfDeferringForever(@TempDir Path projectDir) throws Exception {
        // McpServerRegistry#reconcile replaces an unresponsive/dead McpHookServer
        // with a fresh instance whose hookLocks/activeSessions start empty — fileScope survives (it's the one shared
        // registry, see scopeRegistrySurvivesServerReplacement in McpServerRegistryTest), but until McpHookServer's
        // new rehydrateSession() re-populates them, the next gated Edit/Write for a session that survived the swap
        // finds hookLocks.get(sessionId) == null and is answered "defer" forever.
        Path target = projectDir.resolve("Foo.txt");
        Files.writeString(target, "original");
        RecordingListener listener = new RecordingListener(PermissionDecision.allowed());
        SessionRegistry.register(new FakeSession(sessionId, listener));
        server.registerSession(sessionId, AiTypeEnum.PI, List.of(projectDir.toFile()), true);
        // McpServerRegistry#reconcile's rehydrate loop walks its OWN `registrations` map, keyed by each session's
        // AiMcpRegistrar id — see PiAiProcessManager.start(), which registers `new PiAiMcpRegistrar(sessionId, ...)`
        // using the SAME id passed to registerSession above. setUp()'s NoopRegistrar uses the unrelated "pi-hook-test"
        // key, so without also registering THIS session's id here, reconcile() has no way to know it needs
        // rehydrating — the first attempt at this test missed this and failed with 0 PermissionEvents (build-15).
        assertTrue(McpServerRegistry.register(new NoopRegistrar(sessionId)).get(5, TimeUnit.SECONDS));

        McpHookServer original = server;
        original.stop(); // kill the listener behind the registry's back, exactly like a real unresponsive server
        McpHookServer replaced = awaitServerReplaced(original, 3000);
        assertNotNull(replaced, "health tick should resurrect the server");
        assertNotSame(original, replaced, "fixture sanity: the server instance must actually have changed");
        server = replaced; // postHook() below must hit the NEW instance's baseUrl/port

        JsonObject response = postHook(writeHookBody(sessionId, target.toString(), "changed"));

        assertEquals(1, listener.events.size(),
                "a session registered before the swap must still reach PermissionEvent, not defer forever");
        JsonObject output = response.getAsJsonObject(ClaudeHookKeyEnum.HOOK_SPECIFIC_OUTPUT.key());
        assertNotEquals("defer", output.get(ClaudeHookKeyEnum.PERMISSION_DECISION.key()).getAsString(),
                "the rehydrated hook lock must let this request reach a real allow/deny decision");
        assertEquals("changed", Files.readString(target), "an allowed write must actually land on disk");
    }

    private static McpHookServer awaitServerReplaced(McpHookServer original, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            McpHookServer current = McpServerRegistry.getServer();
            if (current != null && current != original) {
                return current;
            }
            Thread.sleep(20);
        }
        return null;
    }

    private static String stripAccessDeniedPrefix(String reason) {
        String prefix = "Access denied: ";
        assertTrue(reason.startsWith(prefix), reason);
        return reason.substring(prefix.length());
    }

    private JsonObject postHook(JsonObject body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /**
     * A pi {@code write} translated into Claude's hook vocabulary exactly as {@code reviewGatedCall} in
     * aicoder-pi-extension.ts.template builds it — {@code session_id} is the plugin UUID, not anything
     * pi-internal.
     */
    private static JsonObject writeHookBody(String sessionId, String filePath, String content) {
        JsonObject body = toolHookBody(sessionId, "Write");
        JsonObject input = body.getAsJsonObject(ClaudeHookKeyEnum.TOOL_INPUT.key());
        input.addProperty(ClaudeHookKeyEnum.FILE_PATH.key(), filePath);
        input.addProperty(ClaudeHookKeyEnum.CONTENT.key(), content);
        return body;
    }

    private static JsonObject steeredNativeToolHookBody(String sessionId, String toolName, Path target) {
        if ("Write".equals(toolName)) {
            return writeHookBody(sessionId, target.toString(), "changed");
        }
        JsonObject body = toolHookBody(sessionId, toolName);
        if ("Edit".equals(toolName)) {
            JsonObject input = body.getAsJsonObject(ClaudeHookKeyEnum.TOOL_INPUT.key());
            input.addProperty(ClaudeHookKeyEnum.FILE_PATH.key(), target.toString());
            input.addProperty(ClaudeHookKeyEnum.OLD_STRING.key(), "original");
            input.addProperty(ClaudeHookKeyEnum.NEW_STRING.key(), "changed");
        }
        return body;
    }

    private static String qualifiedMcpToolName(McpToolEnum tool) {
        return Arrays.stream(McpToolEnum.allMcpNames().split(","))
                .filter(name -> name.endsWith("__" + tool.toolName()))
                .findFirst()
                .orElseThrow();
    }

    private static JsonObject toolHookBody(String sessionId, String toolName) {
        JsonObject body = new JsonObject();
        body.addProperty(ClaudeHookKeyEnum.HOOK_EVENT_NAME.key(), "PreToolUse");
        body.addProperty(ClaudeHookKeyEnum.SESSION_ID.key(), sessionId);
        body.addProperty(ClaudeHookKeyEnum.TOOL_NAME.key(), toolName);
        body.add(ClaudeHookKeyEnum.TOOL_INPUT.key(), new JsonObject());
        return body;
    }

    private static final class NoopRegistrar extends AiMcpRegistrar {

        NoopRegistrar(String sessionId) {
            super(sessionId, AiTypeEnum.PI);
        }

        @Override
        public void addMcpEndpoint(String endpointUrl) {
        }

        @Override
        public void removeMcpEndpoint() {
        }

        @Override
        public boolean registerHooks(String serverBaseUrl) {
            return true;
        }

        @Override
        public void unregisterHooks() {
        }
    }

    private static final class RecordingListener implements AiProcessEventListener {

        final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        private final PermissionDecision decision;

        RecordingListener(PermissionDecision decision) {
            this.decision = decision;
        }

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            events.add(event);
            if (event instanceof PermissionEvent pe) {
                pe.response().complete(decision);
            }
        }
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;
        private final AiProcessEventListener listener;

        FakeSession(String id, AiProcessEventListener listener) {
            this(id, listener, AiTypeEnum.PI);
        }

        FakeSession(String id, AiProcessEventListener listener, AiTypeEnum type) {
            super(AiSession.create(null, type));
            this.id = id;
            this.listener = listener;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return listener;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
