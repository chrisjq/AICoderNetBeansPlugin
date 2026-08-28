package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the tool-layer argument DEFAULTS of {@link FindFileTool} at the boundary real callers arrive at, which the
 * provider-side tests never reach: they invoke {@link FindFileProvider#findFiles} with explicit booleans and depths, so
 * the {@code !has(...) || bool(...)} default-true composition and the {@code intOr(..., -1)} unlimited default in
 * {@code FindFileTool.handle} are exercised by nothing. A mutation such as
 * {@code !args.has(IGNORE_HIDDEN) || args.bool(...)} &rarr; {@code args.bool(...)} would silently invert the documented
 * "hidden excluded by default" behaviour with the whole suite still green — exactly the accepted-but-silently-inverted
 * shape the code comment there warns about. These tests omit and then override each default against the SAME fixture,
 * so each pair is its own negative control.
 * <p>
 * A real {@link McpHookServer} is required because {@code FindFileTool.handle} consults {@code isFileAccessible} for an
 * explicit {@code directoryPath} before reaching the provider.
 */
class FindFileToolDefaultHandlingTest {

    @TempDir
    Path fixture;

    private McpHookServer server;
    private String sessionId;
    private FakeSession session;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(fixture.resolve("sub/deeper"));
        Files.writeString(fixture.resolve("top.txt"), "t");
        Files.writeString(fixture.resolve("sub/visible.txt"), "v");
        Files.writeString(fixture.resolve("sub/.dotfile"), "d");
        Files.writeString(fixture.resolve("sub/deeper/deepfile.txt"), "deep");

        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("findfile-default-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        server = McpServerRegistry.getServer();
        sessionId = "findfile-default-" + UUID.randomUUID();
        server.registerSession(sessionId, CLAUDE, List.of(fixture.toFile()), true);
        session = new FakeSession(sessionId);
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    void omitIgnoreHidden_defaultsToTrueAndExcludesDotfiles() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(FindFileParamEnum.DIRECTORY_PATH.key(), fixture.toString());
        String out = new FindFileTool(server).handle(new ToolRequestArguments(o), session);

        assertTrue(out.contains("visible.txt"), "a visible file must be listed: " + out);
        assertFalse(out.contains(".dotfile"),
                "omitting ignoreHidden must default to true and exclude the dotfile: " + out);
    }

    @Test
    void ignoreHiddenFalse_includesDotfiles() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(FindFileParamEnum.DIRECTORY_PATH.key(), fixture.toString());
        o.addProperty(FindFileParamEnum.IGNORE_HIDDEN.key(), false);
        String out = new FindFileTool(server).handle(new ToolRequestArguments(o), session);

        assertTrue(out.contains("visible.txt"), "a visible file must be listed: " + out);
        assertTrue(out.contains(".dotfile"),
                "override-compatible ignoreHidden=false must include the dotfile: " + out);
    }

    @Test
    void omitMaxDepth_searchesToTheCeilingAndFindsDeepFiles() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(FindFileParamEnum.DIRECTORY_PATH.key(), fixture.toString());
        String out = new FindFileTool(server).handle(new ToolRequestArguments(o), session);

        assertTrue(out.contains("deepfile.txt"),
                "omitting maxDepth must search to the ceiling (default -1, capped at MAX_DEPTH_CEILING) and find the "
                + "deep file: " + out);
    }

    @Test
    void maxDepthZero_visitsOnlyTheStartingDirectory() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(FindFileParamEnum.DIRECTORY_PATH.key(), fixture.toString());
        o.addProperty(FindFileParamEnum.MAX_DEPTH.key(), 0);
        String out = new FindFileTool(server).handle(new ToolRequestArguments(o), session);

        assertTrue(out.contains("top.txt"), "maxDepth=0 must still find files in the starting directory: " + out);
        assertFalse(out.contains("visible.txt"), "maxDepth=0 must not descend one level to sub/: " + out);
        assertFalse(out.contains("deepfile.txt"), "maxDepth=0 must not reach a deeper file: " + out);
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
}
