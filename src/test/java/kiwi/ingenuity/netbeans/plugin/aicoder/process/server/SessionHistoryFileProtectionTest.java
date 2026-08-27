package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files.ApplyEditTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.files.WriteFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search.FilterFileContentParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search.FilterFileContentTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search.FindFileParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search.FindFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.CopyFileParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.CopyFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.DeleteFileParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.DeleteFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.GetFileContentParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.GetFileContentTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.GetFileSizeAndMetaParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.GetFileSizeAndMetaTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.MoveFileParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.MoveFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.SaveFileParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.SaveFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the requirement that a session's own serialized conversation history (history.json / context.json, under
 * SessionPersistenceManager's per-session directory) is invisible to every file tool — read or write — even though the
 * rest of the session's own data (memory, tool_results, under the unrelated ~/.ai-coder/{type}/{sessionId}/ tree) is
 * freely accessible.
 */
class SessionHistoryFileProtectionTest {

    private static ToolRequestArguments args(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return new ToolRequestArguments(o);
    }

    private String sessionId;
    private Path historyFile;
    private McpHookServer server;

    @BeforeEach
    void setUp() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("registry-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        server = McpServerRegistry.getServer();
        sessionId = "history-protect-" + UUID.randomUUID();
        // restrict-to-project OFF: the configuration where the veto actually matters —
        // isFileAllowed's generic unrestricted-access shortcut would allow anything
        // that isn't vetoed first, which is exactly how the cross-session gap slipped
        // through before isSessionPersistenceDirFile stopped being keyed to the
        // caller's own session id.
        server.registerSession(sessionId, CLAUDE, List.of(), false);
        historyFile = new SessionPersistenceManager().historyPath(sessionId);
        Files.createDirectories(historyFile.getParent());
        Files.writeString(historyFile, "{\"messages\":[]}");
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(historyFile);
        Files.deleteIfExists(historyFile.getParent());
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    void getFileContent_deniesHistoryFile() throws Exception {
        GetFileContentTool tool = new GetFileContentTool(server);
        String result = tool.handle(args(GetFileContentParamEnum.FILE_PATH.key(), historyFile.toString()), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
        // Two independent facts, not fused into one accidental conjunction: the message
        // explains the whole-directory rule, AND separately quotes the path back — the
        // latter used to be the only reason a bare contains("history") check passed.
        assertTrue(result.contains("protected in its entirety"),
                "must explain the whole-directory rule, not just confirm denial: " + result);
        assertTrue(result.contains(historyFile.toString()), "must quote the denied path back: " + result);
    }

    @Test
    void filterFileContent_deniesHistoryFile() throws Exception {
        FilterFileContentTool tool = new FilterFileContentTool(server);
        JsonObject o = new JsonObject();
        o.addProperty(FilterFileContentParamEnum.FILE_PATH.key(), historyFile.toString());
        o.addProperty(FilterFileContentParamEnum.PATTERN.key(), "messages");
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
    }

    @Test
    void findFile_deniesProtectedDirectory() throws Exception {
        FindFileTool tool = new FindFileTool(server);
        JsonObject o = new JsonObject();
        o.addProperty(FindFileParamEnum.DIRECTORY_PATH.key(), historyFile.getParent().toString());
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
    }

    @Test
    void getFileSizeAndMeta_deniesHistoryFile() throws Exception {
        GetFileSizeAndMetaTool tool = new GetFileSizeAndMetaTool(server);
        String result = tool.handle(args(GetFileSizeAndMetaParamEnum.FILE_PATH.key(), historyFile.toString()), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
    }

    @Test
    void applyEdit_deniesHistoryFileWithoutReachingDiffPanel() {
        ApplyEditTool tool = new ApplyEditTool();
        JsonObject o = new JsonObject();
        o.addProperty(McpToolPropertyEnum.FILE_PATH.key(), historyFile.toString());
        o.addProperty(McpToolPropertyEnum.OLD_STRING.key(), "messages");
        o.addProperty(McpToolPropertyEnum.NEW_STRING.key(), "tampered");
        RecordingListener listener = new RecordingListener();
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId, listener));
        assertTrue(result.contains("protected in its entirety"),
                "must explain the whole-directory rule, not just confirm denial: " + result);
        assertTrue(result.contains(historyFile.toString()), "must quote the denied path back: " + result);
        assertTrue(listener.events.isEmpty(), "must never reach the diff panel for the history file");
    }

    @Test
    void writeFile_deniesHistoryFileWithoutReachingDiffPanel() {
        WriteFileTool tool = new WriteFileTool();
        JsonObject o = new JsonObject();
        o.addProperty(McpToolPropertyEnum.FILE_PATH.key(), historyFile.toString());
        o.addProperty(McpToolPropertyEnum.CONTENT.key(), "tampered");
        RecordingListener listener = new RecordingListener();
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId, listener));
        assertTrue(result.contains("protected in its entirety"),
                "must explain the whole-directory rule, not just confirm denial: " + result);
        assertTrue(result.contains(historyFile.toString()), "must quote the denied path back: " + result);
        assertTrue(listener.events.isEmpty(), "must never reach the diff panel for the history file");
    }

    @Test
    void saveFile_deniesHistoryFile() throws Exception {
        SaveFileTool tool = new SaveFileTool(server);
        JsonObject o = new JsonObject();
        o.addProperty(SaveFileParamEnum.FILE_PATH.key(), historyFile.toString());
        o.addProperty(SaveFileParamEnum.CONTENT.key(), "tampered");
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
    }

    @Test
    void deleteFile_deniesHistoryFile() throws Exception {
        DeleteFileTool tool = new DeleteFileTool(server);
        String result = tool.handle(args(DeleteFileParamEnum.FILE_PATH.key(), historyFile.toString()), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
        assertTrue(Files.exists(historyFile), "history file must survive the denied delete attempt");
    }

    @Test
    void moveFile_deniesHistoryFileAsSource() throws Exception {
        MoveFileTool tool = new MoveFileTool(server);
        JsonObject o = new JsonObject();
        o.addProperty(MoveFileParamEnum.SOURCE_PATH.key(), historyFile.toString());
        o.addProperty(MoveFileParamEnum.TARGET_DIRECTORY.key(), "/tmp");
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
    }

    @Test
    void moveFile_deniesHistoryDirAsTarget(@TempDir Path tmp) throws Exception {
        Path other = Files.createFile(tmp.resolve("notes.txt"));
        MoveFileTool tool = new MoveFileTool(server);
        JsonObject o = new JsonObject();
        o.addProperty(MoveFileParamEnum.SOURCE_PATH.key(), other.toString());
        o.addProperty(MoveFileParamEnum.TARGET_DIRECTORY.key(), historyFile.getParent().toString());
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
    }

    @Test
    void copyFile_deniesHistoryFileAsSource() throws Exception {
        CopyFileTool tool = new CopyFileTool(server);
        JsonObject o = new JsonObject();
        o.addProperty(CopyFileParamEnum.SOURCE_PATH.key(), historyFile.toString());
        o.addProperty(CopyFileParamEnum.TARGET_DIRECTORY.key(), "/tmp");
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
    }

    @Test
    void copyFile_deniesHistoryDirAsTarget(@TempDir Path tmp) throws Exception {
        Path other = Files.createFile(tmp.resolve("notes.txt"));
        CopyFileTool tool = new CopyFileTool(server);
        JsonObject o = new JsonObject();
        o.addProperty(CopyFileParamEnum.SOURCE_PATH.key(), other.toString());
        o.addProperty(CopyFileParamEnum.TARGET_DIRECTORY.key(), historyFile.getParent().toString());
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId));
        assertTrue(result.startsWith("Access denied"), result);
    }

    @Test
    void normalOwnConfigFile_stillWritable_provingDenyIsNarrowNotBlanket() throws Exception {
        Path configDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionId);
        Path memoryFile = configDir.resolve("memory.md");
        try {
            WriteFileTool tool = new WriteFileTool();
            JsonObject o = new JsonObject();
            o.addProperty(McpToolPropertyEnum.FILE_PATH.key(), memoryFile.toString());
            o.addProperty(McpToolPropertyEnum.CONTENT.key(), "a fact");
            String result = tool.handle(new ToolRequestArguments(o), new FakeSession(sessionId));
            assertTrue(result.toLowerCase().contains("saved"),
                    "the OTHER own-config tree (memory/tool_results) must remain writable: " + result);
        }
        finally {
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionId);
        }
    }

    @Test
    void crossSession_read_cannotReachAnotherSessionsHistoryOrContextFile() throws Exception {
        String otherSessionId = registerOtherSessionUnrestricted();
        Path contextFile = historyFile.resolveSibling("context.json");
        Files.writeString(contextFile, "{}");
        try {
            GetFileContentTool tool = new GetFileContentTool(server);
            // The caller authenticates as otherSessionId (restrict-to-project OFF —
            // the configuration in which the old, caller-scoped veto let this through);
            // historyFile/contextFile belong to sessionId. The deny must come from the
            // path itself, not from whether the caller happens to own it.
            String historyResult = tool.handle(args(GetFileContentParamEnum.FILE_PATH.key(), historyFile.toString()), new FakeSession(otherSessionId));
            assertTrue(historyResult.startsWith("Access denied"), historyResult);
            String contextResult = tool.handle(args(GetFileContentParamEnum.FILE_PATH.key(), contextFile.toString()), new FakeSession(otherSessionId));
            assertTrue(contextResult.startsWith("Access denied"), contextResult);
        }
        finally {
            Files.deleteIfExists(contextFile);
        }
    }

    @Test
    void crossSession_write_cannotAlterAnotherSessionsHistoryFile() throws Exception {
        String otherSessionId = registerOtherSessionUnrestricted();
        WriteFileTool tool = new WriteFileTool();
        JsonObject o = new JsonObject();
        o.addProperty(McpToolPropertyEnum.FILE_PATH.key(), historyFile.toString());
        o.addProperty(McpToolPropertyEnum.CONTENT.key(), "tampered by another session");
        RecordingListener listener = new RecordingListener();
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(otherSessionId, listener));
        assertTrue(result.contains("protected in its entirety"),
                "must explain the whole-directory rule, not just confirm denial: " + result);
        assertTrue(result.contains(historyFile.toString()), "must quote the denied path back: " + result);
        assertTrue(listener.events.isEmpty(), "must never reach the diff panel for another session's history file");
        assertEquals("{\"messages\":[]}", Files.readString(historyFile), "original content must be untouched");
    }

    @Test
    void crossSession_delete_cannotDeleteAnotherSessionsHistoryFile() throws Exception {
        String otherSessionId = registerOtherSessionUnrestricted();
        DeleteFileTool tool = new DeleteFileTool(server);
        String result = tool.handle(args(DeleteFileParamEnum.FILE_PATH.key(), historyFile.toString()), new FakeSession(otherSessionId));
        assertTrue(result.startsWith("Access denied"), result);
        assertTrue(Files.exists(historyFile), "history file must survive the denied delete attempt");
    }

    @Test
    void crossSession_move_cannotMoveAnotherSessionsHistoryFileAway() throws Exception {
        String otherSessionId = registerOtherSessionUnrestricted();
        MoveFileTool tool = new MoveFileTool(server);
        JsonObject o = new JsonObject();
        o.addProperty(MoveFileParamEnum.SOURCE_PATH.key(), historyFile.toString());
        o.addProperty(MoveFileParamEnum.TARGET_DIRECTORY.key(), "/tmp");
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(otherSessionId));
        assertTrue(result.startsWith("Access denied"), result);
        assertTrue(Files.exists(historyFile), "history file must not be moved away by another session");
    }

    @Test
    void crossSession_copy_cannotCopyAnotherSessionsHistoryFile() throws Exception {
        String otherSessionId = registerOtherSessionUnrestricted();
        CopyFileTool tool = new CopyFileTool(server);
        JsonObject o = new JsonObject();
        o.addProperty(CopyFileParamEnum.SOURCE_PATH.key(), historyFile.toString());
        o.addProperty(CopyFileParamEnum.TARGET_DIRECTORY.key(), "/tmp");
        String result = tool.handle(new ToolRequestArguments(o), new FakeSession(otherSessionId));
        assertTrue(result.startsWith("Access denied"), result);
    }

    private String registerOtherSessionUnrestricted() {
        String otherSessionId = "history-protect-other-" + UUID.randomUUID();
        server.registerSession(otherSessionId, CLAUDE, List.of(), false);
        return otherSessionId;
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
                pe.response().complete(PermissionDecision.denied("test"));
            }
        }
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;
        private final AiProcessEventListener listener;

        FakeSession(String id) {
            this(id, null);
        }

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
