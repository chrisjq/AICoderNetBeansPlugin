package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUtilsLockPathTest {

    @Test
    void normaliseLockPathsUsesRealParentAndDeduplicates(@TempDir Path root) throws Exception {
        Path realParent = Files.createDirectories(root.resolve("real/child"));
        Path alias = root.resolve("alias");
        Files.createSymbolicLink(alias, root.resolve("real"));

        String relative = root.relativize(realParent.resolve("../child/file.txt")).toString();
        String throughAlias = alias.resolve("child/file.txt").toString();
        String expected = realParent.resolve("file.txt").toString();

        Set<String> paths = FileUtils.normaliseLockPaths(List.of(
                root.resolve(relative).toString(), throughAlias, expected, expected));

        assertEquals(Set.of(expected), paths);
    }

    @Test
    void normaliseLockPathsResolvesDeepestExistingAncestor(@TempDir Path root) throws Exception {
        Path existing = Files.createDirectories(root.resolve("existing"));
        Path future = existing.resolve("one/two/file.txt");

        assertEquals(Set.of(future.toString()),
                FileUtils.normaliseLockPaths(List.of(future.toString())));
    }

    @Test
    void caseInsensitiveVolumeFoldsDifferentlyCasedSpellingsToOneKey(@TempDir Path root) throws Exception {
        Path existing = Files.createDirectories(root.resolve("existing"));
        String lower = existing.resolve("one/two/file.txt").toString();
        String upper = existing.resolve("ONE/TWO/FILE.TXT").toString();
        FileUtils.caseInsensitiveVolumeOverrideForTests = true;
        try {
            Set<String> keys = FileUtils.normaliseLockPaths(List.of(lower, upper));
            assertEquals(1, keys.size(), "differently-cased spellings of the same future file must fold to one key: " + keys);
        }
        finally {
            FileUtils.caseInsensitiveVolumeOverrideForTests = null;
        }
    }

    @Test
    void caseSensitiveVolumeKeepsDifferentlyCasedSpellingsApart(@TempDir Path root) throws Exception {
        Path existing = Files.createDirectories(root.resolve("existing"));
        String lower = existing.resolve("one/two/file.txt").toString();
        String upper = existing.resolve("ONE/TWO/FILE.TXT").toString();
        FileUtils.caseInsensitiveVolumeOverrideForTests = false;
        try {
            Set<String> keys = FileUtils.normaliseLockPaths(List.of(lower, upper));
            assertEquals(2, keys.size(), "a case-sensitive volume must keep differently-cased spellings apart: " + keys);
        }
        finally {
            FileUtils.caseInsensitiveVolumeOverrideForTests = null;
        }
    }
}
