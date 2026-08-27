package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file.CloseFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.navigate.NavigateToLineParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.navigate.NavigateToLineTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.FixImportsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.OrganiseImportsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.OrganiseMembersTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.ReformatFileTool;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * AUDIT 7 follow-up: the six parameter-bearing ui tools (CloseFile, FixImports, OrganiseImports, OrganiseMembers,
 * ReformatFile, NavigateToLine) are driven end-to-end through handle() against a REAL McpHookServer, proving the
 * filePath parameter crosses the access gate and reaches the provider (in scope, file exists) and is refused by the
 * gate (out of scope). The session is registered restrict=true with only the inDir root, so isFileAccessible answers
 * for real. The provider's own window-system work (DataObject, LineCookie, config Actions, WindowManager) is
 * deliberately not stubbed — tests only pin the two ends of the gate that are observable headless.
 */
class UiToolsHandleGateEndToEndTest {

    @TempDir
    Path inDir;

    @TempDir
    Path outsideDir;

    private String restrictedSessionId;

    @BeforeEach
    void setUp() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("ui-handle-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        restrictedSessionId = "ui-handle-" + UUID.randomUUID();
        McpServerRegistry.getServer().registerSession(restrictedSessionId, CLAUDE, List.of(inDir.toFile()), true);
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    private static ToolRequestArguments args(String filePath) {
        JsonObject o = new JsonObject();
        o.addProperty(McpToolPropertyEnum.FILE_PATH.key(), filePath);
        return new ToolRequestArguments(o);
    }

    private static ToolRequestArguments navigateArgs(String filePath, int line) {
        JsonObject o = new JsonObject();
        o.addProperty(NavigateToLineParamEnum.FILE_PATH.key(), filePath);
        o.addProperty(NavigateToLineParamEnum.LINE.key(), line);
        return new ToolRequestArguments(o);
    }

    private static FakeSession session(String id) {
        return new FakeSession(id);
    }

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    @Timeout(value = 45)
    void closeFileInScopeReachesProvider() throws Exception {
        Path file = inDir.resolve("CloseMe.java");
        Files.writeString(file, "class CloseMe {}");
        String result = new CloseFileTool().handle(args(file.toString()), session(restrictedSessionId));
        assertFalse(result.startsWith("Access denied"), "in-scope file must not be refused: " + result);
        assertFalse(result.contains("File not found"), "existing in-scope file must resolve: " + result);
    }

    @Test
    void closeFileOutOfScopeIsRefused() {
        String file = outsideDir.resolve("Secret.java").toString();
        String result = new CloseFileTool().handle(args(file), session(restrictedSessionId));
        assertTrue(result.startsWith("Access denied"), "out-of-scope file must be refused: " + result);
        assertTrue(result.contains("outside the allowed project scope"), "denial must name the scope rule: " + result);
    }

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    @Timeout(value = 45)
    void fixImportsInScopeReachesProvider() throws Exception {
        Path file = inDir.resolve("Imports.java");
        Files.writeString(file, "class Imports {}");
        String result = new FixImportsTool().handle(args(file.toString()), session(restrictedSessionId));
        assertFalse(result.startsWith("Access denied"), "in-scope file must not be refused: " + result);
        assertFalse(result.contains("File not found"), "existing in-scope file must resolve: " + result);
    }

    @Test
    void fixImportsOutOfScopeIsRefused() {
        String file = outsideDir.resolve("Imports.java").toString();
        String result = new FixImportsTool().handle(args(file), session(restrictedSessionId));
        assertTrue(result.startsWith("Access denied"), "out-of-scope file must be refused: " + result);
    }

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    @Timeout(value = 45)
    void organiseImportsInScopeReachesProvider() throws Exception {
        Path file = inDir.resolve("OrderImports.java");
        Files.writeString(file, "class OrderImports {}");
        String result = new OrganiseImportsTool().handle(args(file.toString()), session(restrictedSessionId));
        assertFalse(result.startsWith("Access denied"), "in-scope file must not be refused: " + result);
        assertFalse(result.contains("File not found"), "existing in-scope file must resolve: " + result);
    }

    @Test
    void organiseImportsOutOfScopeIsRefused() {
        String file = outsideDir.resolve("OrderImports.java").toString();
        String result = new OrganiseImportsTool().handle(args(file), session(restrictedSessionId));
        assertTrue(result.startsWith("Access denied"), "out-of-scope file must be refused: " + result);
    }

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    @Timeout(value = 45)
    void organiseMembersInScopeReachesProvider() throws Exception {
        Path file = inDir.resolve("OrderMembers.java");
        Files.writeString(file, "class OrderMembers {}");
        String result = new OrganiseMembersTool().handle(args(file.toString()), session(restrictedSessionId));
        assertFalse(result.startsWith("Access denied"), "in-scope file must not be refused: " + result);
        assertFalse(result.contains("File not found"), "existing in-scope file must resolve: " + result);
    }

    @Test
    void organiseMembersOutOfScopeIsRefused() {
        String file = outsideDir.resolve("OrderMembers.java").toString();
        String result = new OrganiseMembersTool().handle(args(file), session(restrictedSessionId));
        assertTrue(result.startsWith("Access denied"), "out-of-scope file must be refused: " + result);
    }

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    @Timeout(value = 45)
    void reformatFileInScopeReachesProvider() throws Exception {
        Path file = inDir.resolve("Reformat.java");
        Files.writeString(file, "class Reformat {}");
        String result = new ReformatFileTool().handle(args(file.toString()), session(restrictedSessionId));
        assertFalse(result.startsWith("Access denied"), "in-scope file must not be refused: " + result);
        assertFalse(result.contains("File not found"), "existing in-scope file must resolve: " + result);
    }

    @Test
    void reformatFileOutOfScopeIsRefused() {
        String file = outsideDir.resolve("Reformat.java").toString();
        String result = new ReformatFileTool().handle(args(file), session(restrictedSessionId));
        assertTrue(result.startsWith("Access denied"), "out-of-scope file must be refused: " + result);
    }

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    @Timeout(value = 45)
    void navigateToLineInScopeAcceptsFilePathAndLine() throws Exception {
        Path file = inDir.resolve("Navigate.java");
        Files.writeString(file, "class Navigate {}");
        String result = new NavigateToLineTool().handle(navigateArgs(file.toString(), 3), session(restrictedSessionId));
        assertFalse(result.startsWith("Access denied"), "in-scope file must not be refused: " + result);
    }

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    @Timeout(value = 45)
    void navigateToLineAcceptsLineZeroLikeAnyOtherInteger() throws Exception {
        Path file = inDir.resolve("NavigateZero.java");
        Files.writeString(file, "class NavigateZero {}");
        String result = new NavigateToLineTool().handle(navigateArgs(file.toString(), 0), session(restrictedSessionId));
        assertFalse(result.startsWith("Access denied"), "in-scope file must not be refused: " + result);
        assertFalse(result.contains("Invalid integer"), "line=0 must parse: " + result);
    }

    @Test
    void navigateToLineOutOfScopeIsRefused() throws Exception {
        String file = outsideDir.resolve("Navigate.java").toString();
        String result = new NavigateToLineTool().handle(navigateArgs(file, 1), session(restrictedSessionId));
        assertTrue(result.startsWith("Access denied"), "out-of-scope file must be refused: " + result);
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

    private static final class FakeSession extends AbstractAiSession {

        private final String id;

        FakeSession(String id) {
            super(AiSession.create(null, AiTypeEnum.CLAUDE));
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
