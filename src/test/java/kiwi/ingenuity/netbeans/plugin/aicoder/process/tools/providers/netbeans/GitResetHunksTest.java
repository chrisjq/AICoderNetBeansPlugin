package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@link GitProvider#gitResetHunks} end-to-end against throwaway repositories built with the real {@code git}
 * CLI: the partial-unstaging path (build a hunk patch from the HEAD-vs-index diff, then apply it in reverse to the
 * index) must unstage exactly the chosen hunks / index-line range and leave the working tree untouched. Skipped when no
 * git binary can be found on the machine.
 */
class GitResetHunksTest {

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
    void unstagesOnlyTheSelectedHunkOfAFullyStagedFile() throws Exception {
        changeLine(3, "CHANGED3");
        changeLine(27, "CHANGED27");
        git("add", "f.txt");

        String result = GitProvider.gitResetHunks(projectPath(), "f.txt",
                                                  List.of(new GitHunkPatch.Selection(1, null, null)));

        assertTrue(result.startsWith("Unstaged ") && result.endsWith("hunk(s) of f.txt"), result);
        String cached = git("diff", "--cached");
        String unstaged = git("diff");
        assertTrue(cached.contains("CHANGED27"), "index must keep the second change:\n" + cached);
        assertFalse(cached.contains("CHANGED3"), "index must no longer hold the first change:\n" + cached);
        assertTrue(unstaged.contains("CHANGED3"), "working tree vs index must now show the first change:\n" + unstaged);
        assertFalse(unstaged.contains("CHANGED27"), "the kept change must not differ from the index:\n" + unstaged);
    }

    @Test
    @Timeout(30)
    void unstagesOnlyTheSelectedIndexRangeAcrossHunks() throws Exception {
        changeLine(3, "CHANGED3");
        changeLine(27, "CHANGED27");
        git("add", "f.txt");

        String result = GitProvider.gitResetHunks(projectPath(), "f.txt",
                                                  List.of(new GitHunkPatch.Selection(null, 27, 27)));

        assertTrue(result.startsWith("Unstaged ") && result.endsWith("hunk(s) of f.txt"), result);
        String cached = git("diff", "--cached");
        String unstaged = git("diff");
        assertTrue(cached.contains("CHANGED3"), "the first change must stay staged:\n" + cached);
        assertFalse(cached.contains("CHANGED27"), "the selected change must leave the index:\n" + cached);
        assertTrue(unstaged.contains("CHANGED27"), "the selected change must now differ from the index:\n" + unstaged);
        assertFalse(unstaged.contains("CHANGED3"), "the staged change must not differ from the index:\n" + unstaged);
    }

    @Test
    @Timeout(30)
    void aFileWithNoStagedChangesIsRejected() throws Exception {
        changeLine(3, "CHANGED3"); // edited in the working tree only, nothing staged

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                                   () -> GitProvider.gitResetHunks(projectPath(), "f.txt",
                                                                                   List.of(new GitHunkPatch.Selection(1, null, null))));

        assertTrue(ex.getMessage().contains("No staged changes"), ex.getMessage());
        assertTrue(git("diff", "--cached").isEmpty(), "nothing may be changed in the index");
    }
}
