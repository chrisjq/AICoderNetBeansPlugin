package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GetGitStatusTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitBlameParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitBlameTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetPluginVersionTool;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins that a git tool's caller-supplied path is checked against the calling session's file scope, so
 * restrict-to-project actually restricts git the way it restricts every other file tool. Before this, every git tool
 * except GitCommit took an arbitrary absolute {@code projectPath} straight to {@code GitProvider#resolveRoot} with no
 * scope check at all — a session locked to one project could GitReset --hard, GitCheckout or GetGitDiff any repository
 * on the machine.
 * <p>
 * The check deliberately validates the SUPPLIED path and not the resolved repository root: a {@code .git} directory
 * frequently sits above the NetBeans project directory, and scoping the resolved root would break that ordinary layout.
 * Every assertion here is therefore about the argument the caller passed, never about where the upward walk for
 * {@code .git} eventually lands.
 * <p>
 * Every tool used here is read-only and every path points at an empty temp directory that is not a repository, so a
 * regression that lets a call through mutates nothing — it only fails an assertion.
 */
class GitProjectPathScopeTest {

    private static JsonObject projectPathArgs(Path path) {
        JsonObject o = new JsonObject();
        o.addProperty(GitCommonParamEnum.PROJECT_PATH.key(), path.toString());
        return o;
    }

    private McpHookServer server;
    private String restrictedSessionId;

    @TempDir
    Path projectDir;

    @TempDir
    Path outsideDir;

    @BeforeEach
    void setUp() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("git-scope-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        server = McpServerRegistry.getServer();
        restrictedSessionId = "git-scope-" + UUID.randomUUID();
        server.registerSession(restrictedSessionId, CLAUDE, List.of(projectDir.toFile()), true);
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    void deniesGitToolWhenProjectPathIsOutsideTheSessionScope() throws Exception {
        String result = McpToolInvoker.invoke(McpToolEnum.GET_GIT_STATUS, new GetGitStatusTool(),
                projectPathArgs(outsideDir), new FakeSession(restrictedSessionId));
        assertTrue(result.startsWith("Access denied"),
                "a restricted session must not reach a repository outside its scope: " + result);
        assertTrue(result.contains(outsideDir.toString()),
                "the denial must quote the refused path back: " + result);
    }

    @Test
    void allowsGitToolWhenProjectPathIsInsideTheSessionScope() throws Exception {
        // Proves the gate is a scope check and not a blanket refusal of git under
        // restrict-to-project. The directory is not a repository, so the tool answers
        // "Repository not found" — the point is only that it got that far.
        String result = McpToolInvoker.invoke(McpToolEnum.GET_GIT_STATUS, new GetGitStatusTool(),
                projectPathArgs(projectDir), new FakeSession(restrictedSessionId));
        assertFalse(result.startsWith("Access denied"),
                "a path inside the session's own project must not be refused: " + result);
    }

    @Test
    void allowsAnyProjectPathWhenSessionIsUnrestricted() throws Exception {
        String unrestrictedSessionId = "git-scope-open-" + UUID.randomUUID();
        server.registerSession(unrestrictedSessionId, CLAUDE, List.of(), false);
        String result = McpToolInvoker.invoke(McpToolEnum.GET_GIT_STATUS, new GetGitStatusTool(),
                projectPathArgs(outsideDir), new FakeSession(unrestrictedSessionId));
        assertFalse(result.startsWith("Access denied"),
                "restrict-to-project OFF must keep working exactly as before: " + result);
    }

    @Test
    void deniesAbsoluteFileArgumentWhenProjectPathIsOmitted() throws Exception {
        // GitBlame is the one git tool that lets projectPath be omitted when file is
        // absolute. Gating projectPath alone would leave per-line authorship of any file
        // on disk completely ungated.
        JsonObject o = new JsonObject();
        o.addProperty(GitBlameParamEnum.FILE.key(), outsideDir.resolve("secret.txt").toString());
        String result = McpToolInvoker.invoke(McpToolEnum.GIT_BLAME, new GitBlameTool(),
                o, new FakeSession(restrictedSessionId));
        assertTrue(result.startsWith("Access denied"),
                "an absolute file argument must be scoped when projectPath is omitted: " + result);
    }

    @Test
    void everyGitSectionToolIsCoveredByTheGuard() {
        // The guard keys off the GIT section rather than a hand-maintained tool list, so
        // this walks the real handler map: a git tool added later is covered without
        // anyone remembering to add it, and this test fails if that ever stops being true.
        Map<McpToolEnum, McpToolInterface> handlers = ToolHandlerFactory.getToolHandlers(server);
        AbstractAiSession session = new FakeSession(restrictedSessionId);
        int gitTools = 0;
        for (Map.Entry<McpToolEnum, McpToolInterface> entry : handlers.entrySet()) {
            if (entry.getValue().section() != McpSectionEnum.GIT) {
                continue;
            }
            gitTools++;
            assertNotNull(McpToolInvoker.gitScopeDenialOrNull(entry.getValue(),
                    projectPathArgs(outsideDir), session),
                    entry.getKey().toolName() + " must refuse an out-of-scope projectPath");
        }
        assertTrue(gitTools >= 20,
                "expected the full git tool surface to be walked, saw only " + gitTools);
    }

    @Test
    void leavesNonGitToolsAlone() {
        // A projectPath belonging to a non-git tool (the build tools take one too) must
        // not be swept up by a guard that exists for the git surface, which has its own
        // scope handling.
        assertNull(McpToolInvoker.gitScopeDenialOrNull(new GetPluginVersionTool(),
                projectPathArgs(outsideDir), new FakeSession(restrictedSessionId)),
                "the git guard must not fire for a non-git tool");
    }

    @Test
    void failsClosedWhenTheCallHasNoSession() {
        assertNotNull(McpToolInvoker.gitScopeDenialOrNull(new GetGitStatusTool(),
                projectPathArgs(outsideDir), null),
                "a git call with no session identity must be refused, not waved through");
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
