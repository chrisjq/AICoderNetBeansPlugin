package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AUDIT 3/6 — proves MoveFileTool honours both parameters: sourcePath selects the file to move and
 * targetDirectory the destination (content lands in the target, source is gone). Also proves the ConfirmEvent
 * gate and the missing-argument errors.
 */
class MoveFileToolTest {

    private static final String SESSION_ID = "move-session";

    private static ToolRequestArguments args(String source, String targetDir) {
        JsonObject o = new JsonObject();
        if (source != null) {
            o.addProperty(MoveFileParamEnum.SOURCE_PATH.key(), source);
        }
        if (targetDir != null) {
            o.addProperty(MoveFileParamEnum.TARGET_DIRECTORY.key(), targetDir);
        }
        return new ToolRequestArguments(o);
    }

    private static McpHookServer unrestrictedServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    @Test
    void schemaRequiresSourceAndTarget() {
        JsonObject schema = new MoveFileTool(unrestrictedServer()).schema(java.util.Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(props.has(MoveFileParamEnum.SOURCE_PATH.key()));
        assertTrue(props.has(MoveFileParamEnum.TARGET_DIRECTORY.key()));
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(2, required.size());
        assertTrue(required.toString().contains(MoveFileParamEnum.SOURCE_PATH.key()));
        assertTrue(required.toString().contains(MoveFileParamEnum.TARGET_DIRECTORY.key()));
    }

    @Test
    void schemaExposesTargetProjectPathAndCommitWithWarningAsOptional() {
        // #17b/#18: both new parameters must be advertised, and neither may be unconditionally required — omitting
        // either preserves today's existing behaviour (same-project move; refuse on any non-fatal warning).
        JsonObject schema = new MoveFileTool(unrestrictedServer()).schema(java.util.Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(props.has(MoveFileParamEnum.TARGET_PROJECT_PATH.key()));
        assertEquals("string", props.getAsJsonObject(MoveFileParamEnum.TARGET_PROJECT_PATH.key())
                .get(ToolSchemaKeyEnum.TYPE.key()).getAsString());
        assertTrue(props.has(MoveFileParamEnum.COMMIT_WITH_WARNING.key()));
        assertEquals("boolean", props.getAsJsonObject(MoveFileParamEnum.COMMIT_WITH_WARNING.key())
                .get(ToolSchemaKeyEnum.TYPE.key()).getAsString());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(2, required.size(), "the two new parameters must not join the required set: " + required);
    }

    @Test
    void relativeTargetDirectoryUnderTargetProjectPathIsCombinedBeforeTheMove(@TempDir Path dir) throws Exception {
        // #17b end-to-end: a relative targetDirectory + targetProjectPath must resolve to the SAME directory the
        // move actually uses — proves MoveFileTool wires resolveMoveTargetDirectory in ahead of the access check
        // and the move itself, not just that the pure helper computes the right string in isolation.
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());
        JsonObject o = new JsonObject();
        o.addProperty(MoveFileParamEnum.SOURCE_PATH.key(), source.toString());
        o.addProperty(MoveFileParamEnum.TARGET_DIRECTORY.key(), "dest");
        o.addProperty(MoveFileParamEnum.TARGET_PROJECT_PATH.key(), dir.toString());

        String result = tool.handle(new ToolRequestArguments(o), new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertEquals("File moved", result);
        assertFalse(Files.exists(source), "source must be gone after a move");
        assertTrue(Files.exists(targetDir.resolve("moved.txt")), "file must land in the resolved (relative) target");
    }

    @Test
    void movesFileIntoTargetDirectory(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());

        String result = tool.handle(args(source.toString(), targetDir.toString()),
                new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertEquals("File moved", result);
        assertFalse(Files.exists(source), "source must be gone after a move");
        assertTrue(Files.exists(targetDir.resolve("moved.txt")), "file must appear in the target directory");
        assertEquals("payload", Files.readString(targetDir.resolve("moved.txt")));
    }

    @Test
    void missingSourceReportsNotFoundWithoutConfirming(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("missing.txt");
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.allowed());

        String result = tool.handle(args(missing.toString(), dir.toString()), session);

        assertTrue(result.contains("File not found"), result);
        assertTrue(session.captured.isEmpty(), "no confirm may be asked for a file that does not exist");
    }

    @Test
    void missingTargetDirectoryIsReported(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());

        String result = tool.handle(args(source.toString(), dir.resolve("no-such-dir").toString()),
                new StubSession(SESSION_ID, PermissionDecision.allowed()));

        assertTrue(result.contains("Target directory not found"), result);
        assertTrue(Files.exists(source), "source must survive a failed move");
    }

    @Test
    void deniedMoveStopsAndLeavesFileInPlace(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.denied("no"));

        String result = tool.handle(args(source.toString(), targetDir.toString()), session);

        assertTrue(result.contains("User declined the move"), result);
        assertTrue(Files.exists(source), "file must survive a denied move");
        assertFalse(Files.exists(targetDir.resolve("moved.txt")), "nothing may appear in the target after a denial");
        assertEquals(1, session.captured.size(), "denial path must fire the Move ConfirmEvent");
        assertEquals("Move", ((ConfirmEvent) session.captured.get(0)).toolName());
    }

    @Test
    void sourceReplacedDuringPromptIsRefusedUnderTheLock(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.allowed()) {
            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return event -> {
                    captured.add(event);
                    if (event instanceof ConfirmEvent ce) {
                        try {
                            Files.writeString(source, "changed-after-approval-xyz");
                        }
                        catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                        ce.response().complete(PermissionDecision.allowed());
                    }
                };
            }
        };

        String result = tool.handle(args(source.toString(), targetDir.toString()), session);

        assertTrue(result.contains("changed after approval"), result);
        assertTrue(Files.exists(source), "source must survive when it changed after approval");
        assertFalse(Files.exists(targetDir.resolve("moved.txt")), "nothing may land in the target");
    }

    @Test
    void targetAppearedDuringPromptIsRefusedForMove(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        Path targetDir = Files.createDirectory(dir.resolve("dest"));
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());
        StubSession session = new StubSession(SESSION_ID, PermissionDecision.allowed()) {
            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return event -> {
                    captured.add(event);
                    if (event instanceof ConfirmEvent ce) {
                        try {
                            Files.writeString(targetDir.resolve("moved.txt"), "already-here");
                        }
                        catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                        ce.response().complete(PermissionDecision.allowed());
                    }
                };
            }
        };

        String result = tool.handle(args(source.toString(), targetDir.toString()), session);

        assertTrue(result.contains("appeared after approval"), result);
        assertTrue(Files.exists(source), "source must survive when the target appeared during the prompt");
        assertEquals("already-here", Files.readString(targetDir.resolve("moved.txt")),
                "the file that appeared during the prompt must not be overwritten");
    }

    @Test
    void missingSourcePathThrows() {
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());

        assertThrows(McpArgumentException.class,
                () -> tool.handle(args(null, "/tmp"), new StubSession(SESSION_ID, PermissionDecision.allowed())));
    }

    @Test
    void javaMoveWaitsForAnInFlightSharedOperationThenCompletes(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("Moved.java"), "class Moved {}");
        Path target = Files.createDirectory(dir.resolve("java-target"));
        assertMoveWaitsForHeldPaths(source, target, true);
    }

    @Test
    void nonJavaMoveWaitsForSourceAndTargetLocksThenCompletes(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("moved.txt"), "payload");
        Path target = Files.createDirectory(dir.resolve("text-target"));
        assertMoveWaitsForHeldPaths(source, target, false);
    }

    private static void assertMoveWaitsForHeldPaths(Path source, Path target, boolean javaMove) throws Exception {
        MoveFileTool tool = new MoveFileTool(unrestrictedServer());
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> holder = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "move-holder-" + javaMove, List.of(source.toString(), target.resolve(source.getFileName()).toString()), () -> {
                holderEntered.countDown();
                try {
                    if (!holderRelease.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out holding move paths");
                    }
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted holding move paths", ex);
                }
                return "held";
            }));
            assertTrue(holderEntered.await(5, TimeUnit.SECONDS));

            Future<String> move = executor.submit(() -> tool.handle(
                    args(source.toString(), target.toString()),
                    new StubSession(SESSION_ID, PermissionDecision.allowed())));
            Thread.sleep(250);
            assertFalse(move.isDone(), javaMove
                                       ? "Java moves must wait on the exclusive mutation side; result=" + (move.isDone() ? move.get() : "<pending>")
                                       : "non-Java moves must wait on source/target file locks; result=" + (move.isDone() ? move.get() : "<pending>"));

            holderRelease.countDown();
            assertEquals("held", holder.get(5, TimeUnit.SECONDS));
            move.get(5, TimeUnit.SECONDS);
        }
        finally {
            holderRelease.countDown();
            executor.shutdownNow();
        }
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
                if (event instanceof PermissionEvent pe) {
                    pe.response().complete(autoDecision);
                }
                else if (event instanceof ConfirmEvent ce) {
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
