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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ProjectPathParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitExecutableLocator;
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
 * Tests for {@link GitResetTool} — the MCP wrapper over {@code git reset} with optional partial unstaging via a
 * {@code hunks} parameter. The schema and argument-validation tests need no git; the end-to-end tests build a throwaway
 * repository with the real git CLI (same setup as {@link GitAddToolTest}) and are skipped when no git binary is on the
 * machine.
 */
class GitResetToolTest {

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
     * Builds a {@link ToolRequestArguments} for a tool call. {@link GitResetTool#handle} never reads the session, so
     * the tests pass {@code null} for it.
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
    void schemaDescribesTheHunksArrayAndItsEntries() {
        JsonObject tool = new GitResetTool().schema(Set.of());
        JsonObject input = tool.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());

        JsonObject hunks = props.getAsJsonObject(GitResetParamEnum.HUNKS.key());
        assertEquals("array", hunks.get(ToolSchemaKeyEnum.TYPE.key()).getAsString());

        JsonObject items = hunks.getAsJsonObject(ToolSchemaKeyEnum.ITEMS.key());
        JsonArray itemsRequired = items.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(1, itemsRequired.size());
        assertEquals(McpToolPropertyEnum.FILE.key(), itemsRequired.get(0).getAsString());

        JsonObject entryProps = items.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(entryProps.has(McpToolPropertyEnum.FILE.key()));
        assertTrue(entryProps.has(McpToolPropertyEnum.HUNK.key()));
        assertTrue(entryProps.has(McpToolPropertyEnum.FROM_LINE.key()));
        assertTrue(entryProps.has(McpToolPropertyEnum.TO_LINE.key()));
    }

    // ---- argument validation (no git needed) ----------------------------
    @Test
    @Timeout(10)
    void hunksWithRevisionHEADMinusOneIsRejected() {
        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), "x");
        o.add(GitResetParamEnum.HUNKS.key(), array(entry("f.txt", 1, null, null)));
        o.addProperty(GitResetParamEnum.REVISION.key(), "HEAD~1");

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                                               () -> new GitResetTool().handle(args(o), null));
        assertTrue(ex.getMessage().contains("hunks only applies to unstaging files"), String.valueOf(ex.getMessage()));
    }

    @Test
    @Timeout(10)
    void hunksWithTypeHardIsRejected() {
        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), "x");
        o.add(GitResetParamEnum.HUNKS.key(), array(entry("f.txt", 1, null, null)));
        o.addProperty(GitResetParamEnum.TYPE.key(), "HARD");

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                                               () -> new GitResetTool().handle(args(o), null));
        assertTrue(ex.getMessage().contains("hunks only applies to unstaging files"), String.valueOf(ex.getMessage()));
    }

    @Test
    @Timeout(10)
    void nonObjectHunksEntryIsAnError() {
        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), "x");
        o.add(GitResetParamEnum.HUNKS.key(), array("f.txt")); // a string, not an object

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                                               () -> new GitResetTool().handle(args(o), null));
        assertTrue(ex.getMessage().contains("hunks entry 0"), String.valueOf(ex.getMessage()));
    }

    // ---- end-to-end against a real repository ---------------------------
    @Test
    @Timeout(30)
    void hunkEntryUnstagesOnlyThatHunk() throws Exception {
        changeLine(3, "CHANGED3");
        changeLine(27, "CHANGED27");
        git("add", "f.txt");

        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath());
        o.add(GitResetParamEnum.HUNKS.key(), array(entry("f.txt", 1, null, null)));

        assertDoesNotThrow(() -> new GitResetTool().handle(args(o), null));

        String cached = git("diff", "--cached");
        String unstaged = git("diff");
        assertTrue(cached.contains("CHANGED27"), "index must keep hunk 2:\n" + cached);
        assertFalse(cached.contains("CHANGED3"), "index must no longer hold hunk 1:\n" + cached);
        assertTrue(unstaged.contains("CHANGED3"), "hunk 1 must now differ from the index:\n" + unstaged);
        assertFalse(unstaged.contains("CHANGED27"), "hunk 2 must not differ from the index:\n" + unstaged);
    }

    @Test
    @Timeout(30)
    void fileOnlyEntryUnstagesTheWholeFile() throws Exception {
        changeLine(3, "CHANGED3");
        changeLine(15, "CHANGED15");
        changeLine(27, "CHANGED27");
        git("add", "f.txt");

        JsonObject o = obj();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath());
        o.add(GitResetParamEnum.HUNKS.key(), array(entry("f.txt", null, null, null))); // {file} only

        assertDoesNotThrow(() -> new GitResetTool().handle(args(o), null));

        assertTrue(git("diff", "--cached").isEmpty(), "the whole file must be unstaged");
        String unstaged = git("diff");
        assertTrue(unstaged.contains("CHANGED3") && unstaged.contains("CHANGED15") && unstaged.contains("CHANGED27"),
                   "every change must now differ from the index:\n" + unstaged);
    }
}
