package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
 * Pi backend round-2 review, finding 3 (spec *Testing*, Task 8 Step 7 — never written): proves end-to-end that a
 * Claude-shaped {@code PreToolUse} hook POST carrying a pi session's OWN PLUGIN UUID — exactly what
 * {@code aicoder-pi-extension.ts.template}'s {@code AICODER_CONFIG.sessionId} bakes in at generation time, per
 * {@code PiExtensionGenerator} — resolves that session in {@link McpHookServer} and reaches a {@link PermissionEvent},
 * without any {@link SessionRegistry#registerAlias} step. Unlike Claude (whose own CLI-internal session id is
 * discovered from its stream and mapped onto the plugin session as a SEPARATE alias — see
 * {@code ClaudeAiSession#registerClaudeSessionAlias}), pi needs no alias at all: the id the hook POST carries and the
 * id the session is registered under are the SAME plugin UUID, so only the primary {@link SessionRegistry#register}
 * ever runs for a pi session — this test never calls {@code registerAlias}.
 *
 * <p>
 * Drives the real HTTP endpoint (an actual {@link McpHookServer} bound to an OS-assigned port via
 * {@link McpServerRegistry#portOverride}, POSTed to with {@link HttpClient}) rather than reflecting into the private
 * {@code handle}/{@code handleRequest} methods, since a real request/response round trip is the more direct proof of
 * "resolves end-to-end" the review asked for.
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
        // Verified quirk (spec *Review gate*): an ALLOWED decision is applied server-side and still comes back as
        // permissionDecision:"deny" with a reason starting McpHookServerUtil.APPLIED_BY_PLUGIN_PREFIX — the extension
        // template matches that exact literal to rewrite it into "SUCCESS — the user accepted..." so pi's own
        // edit/write tool does not run a second time. Asserted against the constant (round-3 review, BigP_2, finding
        // F2), not a hardcoded literal, so wording drift here fails this test instead of silently double-applying
        // every accepted pi write.
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
        // Round-3 review, BigP_2 finding F1: McpServerRegistry#reconcile replaces an unresponsive/dead McpHookServer
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
     * aicoder-pi-extension.ts.template builds it — {@code session_id} is the plugin UUID, not anything pi-internal.
     */
    private static JsonObject writeHookBody(String sessionId, String filePath, String content) {
        JsonObject body = new JsonObject();
        body.addProperty(ClaudeHookKeyEnum.HOOK_EVENT_NAME.key(), "PreToolUse");
        body.addProperty(ClaudeHookKeyEnum.SESSION_ID.key(), sessionId);
        body.addProperty(ClaudeHookKeyEnum.TOOL_NAME.key(), "Write");
        JsonObject input = new JsonObject();
        input.addProperty(ClaudeHookKeyEnum.FILE_PATH.key(), filePath);
        input.addProperty(ClaudeHookKeyEnum.CONTENT.key(), content);
        body.add(ClaudeHookKeyEnum.TOOL_INPUT.key(), input);
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
            super(AiSession.create(null, AiTypeEnum.PI));
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
