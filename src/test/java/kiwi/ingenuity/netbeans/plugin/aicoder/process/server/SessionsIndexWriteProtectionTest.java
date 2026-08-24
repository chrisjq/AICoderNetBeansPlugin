package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files.WriteFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.SaveFileParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.SaveFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the read/write split on the files that sit at the ROOT of the serialized-conversation directory —
 * {@code sessions.json} and the two template files. They are exempt from the history veto so they stay READABLE
 * (McpHookServerScopeTest pins that, and an earlier whole-tree veto was rejected precisely to keep it), but that
 * exemption must not extend to writing.
 * <p>
 * {@code sessions.json} is where {@code SessionPersistenceManager#persist} records every session's own security posture
 * — {@code restrictToProjectFiles}, {@code autoAccept}, {@code allowWebRequests}. A session able to rewrite it could
 * widen its own permissions and have that survive an IDE restart, silently if it is running with Auto-Accept on. That
 * is a strictly larger hole than the per-session history tampering the veto already blocks.
 * <p>
 * Nothing here creates, writes or deletes anything under the real base directory: every assertion is either on a
 * predicate, or on a tool that refuses before it touches the filesystem. The two tool-level tests pass a listener that
 * REJECTS, so even with the fix removed the attempt still writes nothing and only the assertion fails.
 */
class SessionsIndexWriteProtectionTest {

    private McpHookServer server;
    private String sessionId;
    private Path baseDir;
    private Path sessionsIndex;

    @BeforeEach
    void setUp() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("sessions-index-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        server = McpServerRegistry.getServer();
        sessionId = "sessions-index-" + UUID.randomUUID();
        // restrict-to-project OFF: the only configuration in which these paths were ever
        // reachable, since isFileAllowed's unrestricted shortcut is what the base-level
        // read exemption used to fall through into.
        server.registerSession(sessionId, CLAUDE, List.of(), false);
        baseDir = SessionPersistenceManager.defaultBaseDir();
        sessionsIndex = baseDir.resolve("sessions.json");
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    void sessionsIndexRemainsReadable() {
        assertTrue(server.isFileAllowed(sessionId, sessionsIndex.toString()),
                "the read exemption must survive: removing a granted read is not what this fix is for");
        assertTrue(McpHookServer.isFileAccessible(server, sessionId, sessionsIndex.toString()),
                "the plain-scope read gate must still admit the session index");
    }

    @Test
    void sessionsIndexIsNotWritable() {
        assertFalse(McpHookServer.isProjectFileAllowed(server, sessionId, sessionsIndex.toString()),
                "the write gate used by ApplyEdit/WriteFile/SaveFile must refuse the session index");
        assertFalse(McpHookServer.isFileWritable(server, sessionId, sessionsIndex.toString()),
                "the write gate used by Delete/Move/Copy-destination must refuse the session index");
    }

    @Test
    void templateFilesAreReadableButNotWritable() {
        for (String name : List.of("config-templates.json", "instruction-templates.json")) {
            String path = baseDir.resolve(name).toString();
            assertTrue(server.isFileAllowed(sessionId, path), name + " must stay readable");
            assertFalse(McpHookServer.isProjectFileAllowed(server, sessionId, path),
                    name + " must not be writable");
            assertFalse(McpHookServer.isFileWritable(server, sessionId, path),
                    name + " must not be deletable or move-able");
        }
    }

    @Test
    void persistenceBaseDirectoryItselfIsNotWritable() {
        // The bare base directory is not a "dir file" and so was never covered by the read
        // veto at all. Deleting or moving it destroys every session's history at once,
        // which is strictly worse than the single-file tampering the veto already blocks.
        assertFalse(McpHookServer.isFileWritable(server, sessionId, baseDir.toString()),
                "the persistence base directory must not be a legal delete/move/copy target");
    }

    @Test
    void writeFileToolRefusesTheSessionIndexWithoutReachingTheDiffPanel() {
        RecordingListener listener = new RecordingListener();
        JsonObject o = new JsonObject();
        o.addProperty(McpToolPropertyEnum.FILE_PATH.key(), sessionsIndex.toString());
        o.addProperty(McpToolPropertyEnum.CONTENT.key(), "[]");
        String result = new WriteFileTool().handle(new ToolRequestArguments(o),
                new FakeSession(sessionId, listener));
        assertTrue(result.startsWith("Access denied"), result);
        assertTrue(result.contains("never writable"),
                "the denial must say why, not just refuse: " + result);
        assertTrue(listener.events.isEmpty(),
                "must be refused outright, never offered to the user as a reviewable diff");
    }

    @Test
    void saveFileToolRefusesTheSessionIndex() throws Exception {
        RecordingListener listener = new RecordingListener();
        JsonObject o = new JsonObject();
        o.addProperty(SaveFileParamEnum.FILE_PATH.key(), sessionsIndex.toString());
        o.addProperty(SaveFileParamEnum.CONTENT.key(), "[]");
        String result = new SaveFileTool(server).handle(new ToolRequestArguments(o),
                new FakeSession(sessionId, listener));
        assertTrue(result.startsWith("Access denied"), result);
        assertTrue(listener.events.isEmpty(),
                "must be refused outright, never offered to the user as a reviewable diff");
    }

    @Test
    void ordinaryFilesAreUnaffected(@TempDir Path tmp) {
        // Proves the new refusal is narrow. An unrestricted session must still be able to
        // write anywhere it could before.
        String path = tmp.resolve("notes.txt").toString();
        assertTrue(McpHookServer.isProjectFileAllowed(server, sessionId, path), path);
        assertTrue(McpHookServer.isFileWritable(server, sessionId, path), path);
    }

    private static final class NoopRegistrar extends AiMcpRegistrar {

        NoopRegistrar(String sessionId) {
            super(sessionId, AiTypeEnum.CLAUDE);
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

        @Override
        public void onAiProcessEvent(AiProcessEvent event) {
            events.add(event);
            if (event instanceof PermissionEvent pe) {
                // Reject, so that a regression which lets the call through still writes
                // nothing to the user's real session index.
                pe.response().complete(PermissionDecision.denied("test"));
            }
        }
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;
        private final AiProcessEventListener listener;

        FakeSession(String id, AiProcessEventListener listener) {
            super(AiSession.create(null, AiTypeEnum.CLAUDE));
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
