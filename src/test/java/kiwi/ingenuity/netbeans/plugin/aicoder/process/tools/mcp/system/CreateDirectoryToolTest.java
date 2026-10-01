package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.ToolLockRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves CreateDirectoryTool creates nested directories through NetBeans, tells an already-existing directory
 * apart from a genuine error, refuses a path that runs through an existing file, and is gated by the same
 * write-permission check as the other file-management tools.
 */
class CreateDirectoryToolTest {

    private static final String SESSION_ID = "create-dir-session";

    private static ToolRequestArguments args(String filePath) {
        JsonObject o = new JsonObject();
        if (filePath != null) {
            o.addProperty(CreateDirectoryParamEnum.FILE_PATH.key(), filePath);
        }
        return new ToolRequestArguments(o);
    }

    private static McpHookServer unrestrictedServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    @Test
    void isMutatingAndRequiresFileWriteLock() {
        CreateDirectoryTool tool = new CreateDirectoryTool(unrestrictedServer());

        assertTrue(tool.isMutating(), "CreateDirectory must stay under the global mutation lock");
        RequiresLock annotation = tool.getClass().getAnnotation(RequiresLock.class);
        assertNotNull(annotation, "CreateDirectoryTool must carry @RequiresLock");
        assertEquals(LockTypeEnum.FILE_WRITE_LOCK, annotation.value());
        assertEquals(LockTypeEnum.FILE_WRITE_LOCK, ToolLockRegistry.getLockType(McpToolEnum.CREATE_DIRECTORY, tool));
    }

    @Test
    void createsNestedDirectories(@TempDir Path dir) {
        Path target = dir.resolve("a/b/c");
        CreateDirectoryTool tool = new CreateDirectoryTool(unrestrictedServer());

        String result = tool.handle(args(target.toString()), new StubSession(SESSION_ID));

        assertTrue(Files.isDirectory(target), result);
        assertTrue(Files.isDirectory(dir.resolve("a/b")), "intermediate parents must also be created");
        assertTrue(result.contains("created"), result);
    }

    @Test
    void alreadyExistingDirectoryReportsSuccess(@TempDir Path dir) throws Exception {
        Path target = Files.createDirectory(dir.resolve("already"));
        CreateDirectoryTool tool = new CreateDirectoryTool(unrestrictedServer());

        String result = tool.handle(args(target.toString()), new StubSession(SESSION_ID));

        assertTrue(result.contains("already existed"), result);
    }

    @Test
    void pathThroughAnExistingFileIsRefused(@TempDir Path dir) throws Exception {
        Path blocker = Files.writeString(dir.resolve("blocker"), "not a directory");
        Path target = dir.resolve("blocker/child");
        CreateDirectoryTool tool = new CreateDirectoryTool(unrestrictedServer());

        String result = tool.handle(args(target.toString()), new StubSession(SESSION_ID));

        assertFalse(Files.exists(target));
        assertTrue(result.contains("exists and is a file"), result);
    }

    @Test
    void outsideTheProjectIsDenied(@TempDir Path dir) throws Exception {
        Path allowedProject = Files.createTempDirectory("create-dir-allowed-");
        try {
            McpHookServer server = new McpHookServer(0);
            server.registerSession(SESSION_ID, CLAUDE, List.of(allowedProject.toFile()), true);
            CreateDirectoryTool tool = new CreateDirectoryTool(server);
            Path target = dir.resolve("outside");

            String result = tool.handle(args(target.toString()), new StubSession(SESSION_ID));

            assertFalse(Files.exists(target), "nothing may be created outside the session's project scope");
            assertTrue(result.contains("Access denied") || result.toLowerCase().contains("denied"), result);
        }
        finally {
            deleteRecursively(allowedProject.toFile());
        }
    }

    @Test
    void permissionDeniedWhenNoSessionIsRegistered(@TempDir Path dir) {
        // A fresh server with nothing registered for this session id: isFileWritable fails closed.
        McpHookServer server = new McpHookServer(0);
        CreateDirectoryTool tool = new CreateDirectoryTool(server);
        Path target = dir.resolve("unregistered");

        String result = tool.handle(args(target.toString()), new StubSession(SESSION_ID));

        assertFalse(Files.exists(target));
        assertTrue(result.toLowerCase().contains("denied") || result.toLowerCase().contains("unavailable"), result);
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

    private static final class StubSession extends AbstractAiSession {

        private final String id;

        StubSession(String id) {
            super(new AiSession(id, "Test", null, null, null, null,
                    Instant.EPOCH, Instant.EPOCH));
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return event -> {
            };
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
