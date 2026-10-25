package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ProjectPathParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitExecutableLocator;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link GitAddTool} — the MCP wrapper over {@code git add} with optional partial staging via a {@code hunks}
 * parameter. The schema/argument-validation tests need no git; the end-to-end tests build a throwaway repository with
 * the real git CLI (same setup as {@code GitAddHunksTest}) and are skipped when no git binary is on the machine.
 */
class GitAddToolTest {

    private static final int LINE_COUNT = 30;

    @TempDir
    Path tempDir;

    private Path repo;
    private Path file;

    @BeforeEach
    void initRepo() throws Exception {
        Assumptions.assumeTrue(GitExecutableLocator.find().isPresent(),
                               "no git executable found on this machine; skipping");
        repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        git("init", "-b", "master");
        git("config", "user.name", "Audit");
        git("config", "user.email", "audit@example.com");
        file = repo.resolve("f.txt");
        Files.write(file, lines("original"));
        git("add", ".");
        git("commit", "-m", "initial");
    }

    @AfterEach
    void cleanUp() throws Exception {
        // Defensive: the harness deletes tempDir, but make sure we never touch anything else.
    }

    private static List<String> lines(String value) {
        List<String> out = new ArrayList<>();
        for (int n = 1; n <= LINE_COUNT; n++) {
            out.add("line " + n + ": " + value);
        }
        return out;
    }

    private void changeLine(int n, String marker) throws Exception {
        List<String> ls = Files.readAllLines(file);
        ls.set(n - 1, "line " + n + ": " + marker);
        Files.write(file, ls);
    }

    private String git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(bos);
        }
        int code = p.waitFor();
        String out = bos.toString(StandardCharsets.UTF_8);
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed (" + code + "): " + out);
        }
        return out;
    }

    private String projectPath() {
        return repo.toString();
    }

    /**
     * Builds a {@link ToolRequestArguments} for a tool call. {@link GitAddTool#handle} never reads the session, so the
     * tests pass {@code null} for it.
     */
    private static ToolRequestArguments args(JsonObject o) {
        return new ToolRequestArguments(o);
    }

    private static JsonObject obj() {
        return new JsonObject();
    }

    private static JsonArray array(Object... values) {
        JsonArray a = new JsonArray();
        for (Object v : values) {
            if (v instanceof String s) {
                a.add(s);
            }
            else {
                a.add((JsonObject) v);
            }
        }
        return a;
    }

    private static JsonObject entry(String file, Integer hunk, Integer fromLine, Integer toLine) {
        JsonObject o = new JsonObject();
        o.addProperty("file", file);
        if (hunk != null) {
            o.addProperty("hunk", hunk);
        }
        if (fromLine != null) {
            o.addProperty("fromLine", fromLine);
        }
        if (toLine != null) {
            o.addProperty("toLine", toLine);
        }
        return o;
    }

    // ---- schema ---------------------------------------------------------
    @Test
    @Timeout(10)
    void schemaRequiresProjectPathAndNotFiles() {
        JsonObject tool = new GitAddTool().schema(Set.of());
        JsonObject input = tool.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonArray required = input.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        JsonObject props = input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());

        // projectPath required, files NOT required.
        assertEquals(1, required.size());
        assertEquals(ProjectPathParamEnum.PROJECT_PATH.key(), required.get(0).getAsString());
        // hunks items: file required; the three partial-staging properties present.
        JsonObject hunksItems = props.getAsJsonObject(GitAddParamEnum.HUNKS.key())
                .getAsJsonObject(ToolSchemaKeyEnum.ITEMS.key());
        JsonArray itemsRequired = hunksItems.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(1, itemsRequired.size());
        assertEquals("file", itemsRequired.get(0).getAsString());
        JsonObject hprops = hunksItems.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(hprops.has("file"));
        assertTrue(hprops.has("hunk"));
        assertTrue(hprops.has("fromLine"));
        assertTrue(hprops.has("toLine"));
    }

    // ---- argument validation (no git needed) ----------------------------
    @Test
    @Timeout(10)
    void neitherFilesNorHunksIsAnError() {
        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), "x");
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                                               () -> new GitAddTool().handle(args(o), null));
        assertTrue(ex.getMessage().contains("files or hunks is required"), String.valueOf(ex.getMessage()));
    }

    @Test
    @Timeout(10)
    void nonObjectHunksEntryIsAnError() {
        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), "x");
        o.add(GitAddParamEnum.HUNKS.key(), array("f.txt")); // a string, not an object

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                                               () -> new GitAddTool().handle(args(o), null));
        assertTrue(ex.getMessage().contains("hunks entry 0"), String.valueOf(ex.getMessage()));
    }

    @Test
    @Timeout(10)
    void fromLineWithoutToLineIsAnError() {
        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), "x");
        o.add(GitAddParamEnum.HUNKS.key(), array(entry("f.txt", null, 1, null))); // fromLine, no toLine

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                                               () -> new GitAddTool().handle(args(o), null));
        assertTrue(ex.getMessage().contains("must be given together"), String.valueOf(ex.getMessage()));
    }

    // ---- end-to-end against a real repository ---------------------------
    @Test
    @Timeout(30)
    void hunksEntryStagesOnlyTheRequestedHunk() throws Exception {
        changeLine(3, "CHANGED3");
        changeLine(15, "CHANGED15"); // far enough away to be a separate hunk (git context = 3)

        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath());
        o.add(GitAddParamEnum.HUNKS.key(), array(entry("f.txt", 1, null, null)));

        assertDoesNotThrow(() -> new GitAddTool().handle(args(o), null));

        String cached = git("diff", "--cached");
        String unstaged = git("diff");
        // Only hunk 1 was staged: the index holds CHANGED3, not CHANGED15; the working tree vs index still shows
        // CHANGED15, and the staged hunk no longer differs from the index.
        assertTrue(cached.contains("CHANGED3"), "index must hold hunk 1:\n" + cached);
        assertFalse(cached.contains("CHANGED15"), "index must not hold hunk 2:\n" + cached);
        assertTrue(unstaged.contains("CHANGED15"), "unstaged must still show hunk 2:\n" + unstaged);
        assertFalse(unstaged.contains("CHANGED3"), "staged hunk must no longer differ from the index:\n" + unstaged);
    }

    @Test
    @Timeout(30)
    void hunksEntryFileOnlyStagesWholeFile() throws Exception {
        changeLine(3, "CHANGED3");
        changeLine(15, "CHANGED15");
        changeLine(27, "CHANGED27");

        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath());
        o.add(GitAddParamEnum.HUNKS.key(), array(entry("f.txt", null, null, null))); // {file} only

        assertDoesNotThrow(() -> new GitAddTool().handle(args(o), null));

        String cached = git("diff", "--cached");
        String unstaged = git("diff");
        // The whole file is staged: every change is in the index and the working tree matches the index.
        assertTrue(cached.contains("CHANGED3") && cached.contains("CHANGED15") && cached.contains("CHANGED27"),
                   "index must hold every change:\n" + cached);
        assertFalse(unstaged.contains("CHANGED"), "nothing should remain unstaged:\n" + unstaged);
    }
}
