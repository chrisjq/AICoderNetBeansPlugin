package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.ToolLockRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves DeleteDirectoryTool deletes only a file-free tree, refuses (naming the files) otherwise, refuses
 * anything outside the session's scope, and is gated by the same permission/confirmation path as DeleteFile.
 * The open-project-root refusal and the deepest-first delete's race safety live in
 * {@code RefactoringProviderDeleteDirectoryTest} instead — they need package-private test seams on
 * {@code RefactoringProvider} that this Tool-layer test, in a different package, cannot see.
 */
class DeleteDirectoryToolTest {

    private static final String SESSION_ID = "delete-dir-session";

    private static ToolRequestArguments args(String filePath) {
        JsonObject o = new JsonObject();
        if (filePath != null) {
            o.addProperty(DeleteDirectoryParamEnum.FILE_PATH.key(), filePath);
        }
        return new ToolRequestArguments(o);
    }

    private static McpHookServer unrestrictedServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    @Test
    void isMutatingWithOwnPerDirectoryLocking() {
        DeleteDirectoryTool tool = new DeleteDirectoryTool(unrestrictedServer());

        assertTrue(tool.isMutating());
        assertTrue(tool.usesOwnFileLocking(), "directory mutation must avoid the global mutation lock");
        assertEquals(null, ToolLockRegistry.getLockType(McpToolEnum.DELETE_DIRECTORY, tool));
    }

    @Test
    void deletesAnEmptyNestedTree(@TempDir Path dir) throws Exception {
        Path victim = dir.resolve("victim");
        Files.createDirectories(victim.resolve("a/b/c"));
        DeleteDirectoryTool tool = new DeleteDirectoryTool(unrestrictedServer());

        String result = tool.handle(args(victim.toString()), new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertEquals("Directory deleted", result);
        assertFalse(Files.exists(victim));
    }

    @Test
    void refusesWhenADeepFileExists_andListsIt(@TempDir Path dir) throws Exception {
        Path victim = dir.resolve("victim");
        Files.createDirectories(victim.resolve("a/b"));
        Files.writeString(victim.resolve("a/b/secret.txt"), "keep me");
        DeleteDirectoryTool tool = new DeleteDirectoryTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.allowed());

        String result = tool.handle(args(victim.toString()), session);

        assertTrue(result.contains("secret.txt"), result);
        assertTrue(result.contains("1 file"), result);
        assertTrue(Files.exists(victim), "nothing may be removed when the tree holds a file");
        assertTrue(Files.exists(victim.resolve("a/b/secret.txt")));
    }

    @Test
    void outsideTheProjectIsDenied(@TempDir Path dir) throws Exception {
        Path allowedProject = Files.createTempDirectory("delete-dir-allowed-");
        try {
            McpHookServer server = new McpHookServer(0);
            server.registerSession(SESSION_ID, CLAUDE, List.of(allowedProject.toFile()), true);
            DeleteDirectoryTool tool = new DeleteDirectoryTool(server);
            Path victim = Files.createDirectory(dir.resolve("outside"));

            String result = tool.handle(args(victim.toString()), new StubSession(SESSION_ID, PermissionDecision.allowed()));

            assertTrue(Files.exists(victim), "nothing outside the session's project scope may be deleted");
            assertTrue(result.toLowerCase().contains("denied"), result);
        }
        finally {
            deleteRecursively(allowedProject.toFile());
        }
    }

    @Test
    void deniedConfirmationLeavesTheTreeInPlace(@TempDir Path dir) throws Exception {
        Path victim = Files.createDirectory(dir.resolve("victim"));
        DeleteDirectoryTool tool = new DeleteDirectoryTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.denied("no"));

        String result = tool.handle(args(victim.toString()), session);

        assertTrue(result.contains("User declined the delete"), result);
        assertTrue(Files.exists(victim));
        assertEquals(1, session.captured.size(), "denial path must fire the Delete ConfirmEvent");
        assertEquals("Delete", ((ConfirmEvent) session.captured.get(0)).toolName());
    }

    /**
     * N2: no lock is held during the confirm prompt, so a file could appear in the tree between the emptiness
     * check that built the prompt text and the delete itself. {@code RefactoringProvider.deleteDirectory} is
     * only ever invoked as the under-lock action, so its whole file-free-tree walk — not just a cheap type
     * check — runs AFTER the lock is held; this proves a file created during the window the prompt is open is
     * still caught.
     */
    @Test
    void fileCreatedDuringConfirmationMakesTheUnderLockTreeWalkRefuse(@TempDir Path dir) throws Exception {
        Path victim = dir.resolve("victim");
        Files.createDirectories(victim.resolve("a/b"));
        DeleteDirectoryTool tool = new DeleteDirectoryTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.allowed()) {
            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return event -> {
                    captured.add(event);
                    if (event instanceof ConfirmEvent ce) {
                        try {
                            Files.writeString(victim.resolve("a/b/appeared-during-confirm.txt"), "content");
                        }
                        catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                        ce.response().complete(PermissionDecision.allowed());
                    }
                };
            }
        };

        String result = tool.handle(args(victim.toString()), session);

        assertTrue(result.contains("appeared-during-confirm.txt"), result);
        assertTrue(Files.exists(victim), "nothing may be removed when a file appeared during the confirm prompt");
        assertTrue(Files.exists(victim.resolve("a/b/appeared-during-confirm.txt")));
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        f.delete();
    }

    private static class StubSession extends AbstractAiSession {

        final List<AiProcessEvent> captured = new ArrayList<>();
        private final String id;
        private final PermissionDecision autoDecision;

        StubSession(String id, PermissionDecision autoDecision) {
            super(new AiSession(id, "Test", null, null, null, null,
                    Instant.EPOCH, Instant.EPOCH));
            this.id = id;
            this.autoDecision = autoDecision;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return event -> {
                captured.add(event);
                if (event instanceof ConfirmEvent ce) {
                    ce.response().complete(autoDecision);
                }
            };
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
