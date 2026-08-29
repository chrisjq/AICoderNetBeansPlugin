package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the batch shape added to {@code MoveClassTool}: the schema exposes {@code filePaths}, exactly one of
 * {@code filePath}/{@code filePaths} is enforced both ways, {@code line} is rejected with {@code filePaths}, and every
 * path in a batch is access-checked before {@code RefactoringProvider.moveClasses} is ever reached. The actual move
 * (one {@code MoveRefactoring} for the whole batch) needs a resolvable Java source file and is covered by
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProviderMoveClassesTest}
 * only up to the point that does not require a live project, matching the existing single-file test's own limits.
 */
class MoveClassToolMultiFileTest {

    private static final String SESSION_ID = "move-class-multi-test";
    private static final String VALID_PACKAGE = "com.example.target";

    private static JsonObject schemaOf(MoveClassTool tool) {
        return tool.schema(Set.of()).getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
    }

    @Test
    void schemaExposesFilePathsAsAStringArray() {
        JsonObject schema = schemaOf(new MoveClassTool());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());

        assertTrue(props.has(MoveClassParamEnum.FILE_PATHS.key()), "schema must expose filePaths");
        JsonObject filePaths = props.getAsJsonObject(MoveClassParamEnum.FILE_PATHS.key());
        assertEquals("array", filePaths.get(ToolSchemaKeyEnum.TYPE.key()).getAsString());
        assertEquals("string", filePaths.getAsJsonObject(ToolSchemaKeyEnum.ITEMS.key())
                .get(ToolSchemaKeyEnum.TYPE.key()).getAsString());
    }

    @Test
    void neitherFilePathNorFilePathsIsUnconditionallyRequired() {
        JsonObject schema = schemaOf(new MoveClassTool());
        var required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());

        for (var el : required) {
            assertFalse(el.getAsString().equals(MoveClassParamEnum.FILE_PATH.key())
                    || el.getAsString().equals(MoveClassParamEnum.FILE_PATHS.key()),
                    "exactly-one-of parameters must not be unconditionally required: " + required);
        }
        assertTrue(required.contains(new com.google.gson.JsonPrimitive(MoveClassParamEnum.TARGET_PACKAGE.key())));
    }

    @Test
    void bothFilePathAndFilePathsIsRejected() {
        JsonObject o = new JsonObject();
        o.addProperty(MoveClassParamEnum.FILE_PATH.key(), "/tmp/a.java");
        var arr = new com.google.gson.JsonArray();
        arr.add("/tmp/b.java");
        o.add(MoveClassParamEnum.FILE_PATHS.key(), arr);
        o.addProperty(MoveClassParamEnum.TARGET_PACKAGE.key(), VALID_PACKAGE);

        // The exactly-one-of check runs before the session is ever touched, so a null session is safe here.
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new MoveClassTool().handle(new ToolRequestArguments(o), null));
        assertTrue(ex.getMessage().contains(MoveClassParamEnum.FILE_PATH.key())
                && ex.getMessage().contains(MoveClassParamEnum.FILE_PATHS.key()),
                "the error must name both keys: " + ex.getMessage());
    }

    @Test
    void neitherFilePathNorFilePathsIsRejected() {
        JsonObject o = new JsonObject();
        o.addProperty(MoveClassParamEnum.TARGET_PACKAGE.key(), VALID_PACKAGE);

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new MoveClassTool().handle(new ToolRequestArguments(o), null));
        assertTrue(ex.getMessage().contains(MoveClassParamEnum.FILE_PATH.key())
                && ex.getMessage().contains(MoveClassParamEnum.FILE_PATHS.key()),
                "the error must name both keys: " + ex.getMessage());
    }

    @Test
    void emptyFilePathsGetsTheAccurateError() {
        JsonObject o = new JsonObject();
        o.add(MoveClassParamEnum.FILE_PATHS.key(), new com.google.gson.JsonArray());
        o.addProperty(MoveClassParamEnum.TARGET_PACKAGE.key(), VALID_PACKAGE);

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new MoveClassTool().handle(new ToolRequestArguments(o), null));

        assertTrue(ex.getMessage().contains("must contain at least one non-null string path"),
                "empty filePaths must explain that its array is empty: " + ex.getMessage());
    }

    @Test
    void nonStringFilePathsElementIsRejectedWithItsIndexAndValue() {
        JsonObject o = new JsonObject();
        var arr = new com.google.gson.JsonArray();
        arr.add("/tmp/a.java");
        arr.add(42);
        arr.add("/tmp/b.java");
        o.add(MoveClassParamEnum.FILE_PATHS.key(), arr);
        o.addProperty(MoveClassParamEnum.TARGET_PACKAGE.key(), VALID_PACKAGE);

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new MoveClassTool().handle(new ToolRequestArguments(o), null));

        assertTrue(ex.getMessage().contains("filePaths[1]"),
                "the error must name the bad array index: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("42"),
                "the error must name the received non-string value: " + ex.getMessage());
    }

    @Test
    void lineWithFilePathsIsRejected() {
        JsonObject o = new JsonObject();
        var arr = new com.google.gson.JsonArray();
        arr.add("/tmp/a.java");
        arr.add("/tmp/b.java");
        o.add(MoveClassParamEnum.FILE_PATHS.key(), arr);
        o.addProperty(MoveClassParamEnum.LINE.key(), 12);
        o.addProperty(MoveClassParamEnum.TARGET_PACKAGE.key(), VALID_PACKAGE);

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new MoveClassTool().handle(new ToolRequestArguments(o), new FakeSession(SESSION_ID, null)));
        assertTrue(ex.getMessage().contains(MoveClassParamEnum.LINE.key()),
                "the error must name the offending parameter: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(MoveClassParamEnum.FILE_PATHS.key()));
    }

    // ---- access-checking: needs a real registered session with a real scope, per the project's established pattern ----
    @BeforeEach
    void setUp() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("move-class-multi-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    @Test
    void eachPathInABatchIsAccessCheckedNotJustTheFirst(@TempDir Path tempDir) throws Exception {
        Path allowedDir = Files.createDirectories(tempDir.resolve("in-scope"));
        Path outsideDir = Files.createDirectories(tempDir.resolve("out-of-scope"));
        Path inScopeFile = Files.writeString(allowedDir.resolve("A.java"), "package a; class A {}");
        Path outOfScopeFile = Files.writeString(outsideDir.resolve("B.java"), "package b; class B {}");

        McpServerRegistry.getServer().registerSession(SESSION_ID, AiTypeEnum.CLAUDE,
                List.of(allowedDir.toFile()), true);

        JsonObject o = new JsonObject();
        var arr = new com.google.gson.JsonArray();
        arr.add(inScopeFile.toString());
        arr.add(outOfScopeFile.toString());
        o.add(MoveClassParamEnum.FILE_PATHS.key(), arr);
        o.addProperty(MoveClassParamEnum.TARGET_PACKAGE.key(), VALID_PACKAGE);

        String result = new MoveClassTool().handle(new ToolRequestArguments(o), new FakeSession(SESSION_ID, null));

        assertTrue(result.contains(outOfScopeFile.toString()),
                "must name the out-of-scope path that was actually denied: " + result);
        assertFalse(result.contains("Refactoring blocked") || result.contains("File not found"),
                "must stop at the access check, never reaching RefactoringProvider: " + result);
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
