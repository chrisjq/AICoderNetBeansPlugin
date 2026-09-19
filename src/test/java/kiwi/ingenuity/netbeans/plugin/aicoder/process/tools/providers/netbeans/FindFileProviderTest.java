package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindFileProviderTest {

    @TempDir
    Path directory;

    @Test
    void recursivelyFindsLiteralNamesCaseInsensitively() throws Exception {
        Files.createFile(directory.resolve("README.md"));
        Path nested = Files.createDirectories(directory.resolve("docs"));
        Files.createFile(nested.resolve("reference.MD"));

        String result = FindFileProvider.findFiles(List.of(directory), "reference.md", false, false, 0);

        assertTrue(result.startsWith("Found 1 file(s):"), result);
        assertTrue(result.contains("docs"), result);
        assertTrue(result.contains("reference.MD"), result);
    }

    @Test
    void omittedPatternListsEveryFile() throws Exception {
        Files.createFile(directory.resolve("one.txt"));
        Files.createFile(directory.resolve("two.txt"));

        String result = FindFileProvider.findFiles(List.of(directory), null, false, false, 0);

        assertTrue(result.startsWith("Found 2 file(s):"), result);
        assertTrue(result.contains(directory.resolve("one.txt").toAbsolutePath().normalize().toString()), result);
        assertTrue(result.contains(directory.resolve("two.txt").toAbsolutePath().normalize().toString()), result);
    }

    @Test
    void returnsTheSearchRootPlusTheCandidateRelativePath() throws Exception {
        Path nested = Files.createDirectories(directory.resolve("nested"));
        Files.createFile(nested.resolve("known.txt"));

        String result = FindFileProvider.findFiles(List.of(directory), "known", false, true, 0);

        Path expected = directory.toAbsolutePath().normalize().resolve("nested").resolve("known.txt");
        assertTrue(result.contains(expected.toString()), result);
    }

    @Test
    void excludesCandidatesDeniedByTheAccessPredicate() throws Exception {
        Files.createFile(directory.resolve("allowed.txt"));
        Files.createFile(directory.resolve("denied.txt"));

        String result = FindFileProvider.findFiles(List.of(directory), ".txt", false, true, 0,
                path -> !path.getFileName().toString().equals("denied.txt"));

        assertTrue(result.startsWith("Found 1 file(s):"), result);
        assertTrue(result.contains("allowed.txt"), result);
        assertTrue(!result.contains("denied.txt"), result);
    }

    @Test
    void supportsRegexAndCaseSensitiveMatching() throws Exception {
        // The decoy differs from the match by more than case on purpose. Two names differing ONLY in case cannot
        // coexist on a case-insensitive filesystem — the default on macOS (APFS) and Windows — so creating them both
        // threw FileAlreadyExistsException there while passing on Linux. "findOtherTool.java" still proves the flag:
        // the regex misses it under caseSensitive=true and would catch it under false.
        Files.createFile(directory.resolve("FindFileTool.java"));
        Files.createFile(directory.resolve("findOtherTool.java"));

        String result = FindFileProvider.findFiles(List.of(directory), "Find.*\\.java", true, true, 0);

        assertTrue(result.startsWith("Found 1 file(s):"), result);
        assertTrue(result.contains("FindFileTool.java"), result);
        assertTrue(!result.contains("findOtherTool.java"), result);
    }

    @Test
    void reportsTrueTotalWhenOutputIsCapped() throws Exception {
        for (int i = 0; i < 3; i++) {
            Files.createFile(directory.resolve("match-" + i + ".txt"));
        }

        String result = FindFileProvider.findFiles(List.of(directory), "match", false, false, 2);

        assertTrue(result.startsWith("Found 3 file(s) (showing first 2):"), result);
        assertEquals(2, result.lines().filter(line -> line.endsWith(".txt")).count(), result);
    }

    @Test
    void distinguishesInvalidRegexAndNonDirectory() {
        assertTrue(FindFileProvider.findFiles(List.of(directory), "[", true, false, 0)
                .startsWith("Invalid regex:"));
        assertEquals("Not a directory: " + directory.resolve("missing"),
                FindFileProvider.findFiles(List.of(directory.resolve("missing")), "x", false, false, 0));
    }
}
