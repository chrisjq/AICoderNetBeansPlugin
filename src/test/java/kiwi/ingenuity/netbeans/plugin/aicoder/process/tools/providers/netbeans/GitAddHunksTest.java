package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@link GitProvider#gitAddHunks} end-to-end against throwaway repositories built with the real {@code git} CLI:
 * the partial-staging path (build a hunk patch, apply it with {@code git apply --cached}) must stage exactly the chosen
 * hunks / line range in the INDEX and leave the working tree untouched. Skipped when no git binary can be found on the
 * machine.
 */
class GitAddHunksTest {

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

    /**
     * Builds the committed file body: "line N: <value>" for each of the 30 lines.
     */
    private static List<String> lines(String value) {
        List<String> out = new ArrayList<>();
        for (int n = 1; n <= LINE_COUNT; n++) {
            out.add("line " + n + ": " + value);
        }
        return out;
    }

    /**
     * Rewrites one physical line of the working-tree file to carry a marker.
     */
    private void changeLine(int n, String marker) throws Exception {
        List<String> lines = Files.readAllLines(file);
        lines.set(n - 1, "line " + n + ": " + marker);
        Files.write(file, lines);
    }

    /**
     * Runs git in {@link #repo}; returns stdout+stderr, throwing on a non-zero exit.
     */
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

    @Test
    @Timeout(30)
    void stagesOnlyTheRequestedHunk() throws Exception {
        changeLine(3, "CHANGED3");
        changeLine(15, "CHANGED15");
        changeLine(27, "CHANGED27");

        String result = GitProvider.gitAddHunks(projectPath(), "f.txt",
                                                List.of(new GitHunkPatch.Selection(2, null, null)));

        assertEquals("Staged 1 hunk(s) of f.txt", result);

        String cached = git("diff", "--cached");
        String unstaged = git("diff");
        String working = Files.readString(file);

        assertTrue(cached.contains("CHANGED15"), "index must hold the staged hunk:\n" + cached);
        assertFalse(cached.contains("CHANGED3"), "index must not hold hunk 1:\n" + cached);
        assertFalse(cached.contains("CHANGED27"), "index must not hold hunk 3:\n" + cached);

        assertTrue(unstaged.contains("CHANGED3"), "working tree vs index must still show hunk 1:\n" + unstaged);
        assertTrue(unstaged.contains("CHANGED27"), "working tree vs index must still show hunk 3:\n" + unstaged);
        assertFalse(unstaged.contains("CHANGED15"), "staged hunk must no longer differ from the index:\n" + unstaged);

        // Only the index was written; the working file is unchanged.
        assertTrue(working.contains("CHANGED3") && working.contains("CHANGED15") && working.contains("CHANGED27"),
                   "working file must keep all three edits:\n" + working);
    }

    @Test
    @Timeout(30)
    void stagesOnlyTheRequestedLineRange() throws Exception {
        // Three consecutive edits collapse into a single hunk; select only new-file line 11.
        changeLine(10, "CHANGED10");
        changeLine(11, "CHANGED11");
        changeLine(12, "CHANGED12");

        String result = GitProvider.gitAddHunks(projectPath(), "f.txt",
                                                List.of(new GitHunkPatch.Selection(1, 11, 11)));

        assertEquals("Staged 1 hunk(s) of f.txt", result);

        String cached = git("diff", "--cached");
        String unstaged = git("diff");

        assertTrue(cached.contains("CHANGED11"), "index must hold only the selected line:\n" + cached);
        assertFalse(cached.contains("CHANGED10"), "line 10 must stay unstaged:\n" + cached);
        assertFalse(cached.contains("CHANGED12"), "line 12 must stay unstaged:\n" + cached);

        assertTrue(unstaged.contains("CHANGED10"), "working vs index must still show line 10:\n" + unstaged);
        assertTrue(unstaged.contains("CHANGED12"), "working vs index must still show line 12:\n" + unstaged);
        // Line 11 is now staged, so the working-vs-index diff shows it as a CONTEXT line between the still-
        // unstaged lines 10 and 12 — assert it is not present as an added or removed line (not merely absent).
        assertFalse(unstaged.contains("+line 11: CHANGED11") || unstaged.contains("-line 11: original"),
                    "staged line 11 must no longer be a changed line (only context):\n" + unstaged);
    }

    @Test
    @Timeout(30)
    void outOfRangeHunkReportsAnErrorAndStagesNothing() throws Exception {
        changeLine(3, "CHANGED3"); // one hunk

        String result = GitProvider.gitAddHunks(projectPath(), "f.txt",
                                                List.of(new GitHunkPatch.Selection(5, null, null)));

        assertTrue(result.contains("has 1 hunks"), result);
        assertTrue(result.contains("cannot select hunk 5"), result);
        assertFalse(git("diff", "--cached").contains("CHANGED3"), "nothing may be staged");
    }

    @Test
    @Timeout(30)
    void noUnstagedChangesGivesAClearMessage() throws Exception {
        // The file is committed and unmodified.
        String result = GitProvider.gitAddHunks(projectPath(), "f.txt",
                                                List.of(new GitHunkPatch.Selection(1, null, null)));

        assertTrue(result.startsWith("No unstaged changes in f.txt"), result);
        assertTrue(git("diff", "--cached").isEmpty(), "nothing staged for a clean tree");
    }
}
