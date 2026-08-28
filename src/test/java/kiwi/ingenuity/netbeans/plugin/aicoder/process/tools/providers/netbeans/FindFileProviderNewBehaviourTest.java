package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for four new FindFileProvider behaviours: {@code ignoreHidden} (default true — dot-names and DOS-hidden files
 * excluded, hidden directories pruned, but a hidden root still searched when named), {@code maxDepth} (caller depth 0 =
 * starting directory only, 1 = one level below, 2 = two; negative = as deep as the MAX_DEPTH_CEILING allows), the
 * {@code toRealPath()} guard that collapses the same file reached through overlapping or aliased roots, and
 * {@code type} (default file; {@code dir} finds empty and populated directories, the starting directory is never
 * returned, and the result wording follows the type). The depth translation is pinned hard because it is the most
 * likely place for an off-by-one.
 */
class FindFileProviderNewBehaviourTest {

    private static String find(Path root, int maxDepth, boolean ignoreHidden) {
        return FindFileProvider.findFiles(List.of(root), "txt", false, false, 0, ignoreHidden, maxDepth, path -> true);
    }

    private static String findAll(Path root, boolean ignoreHidden) {
        return FindFileProvider.findFiles(List.of(root), null, false, false, 0, ignoreHidden, -1, path -> true);
    }

    private static String findTyped(Path root, FindFileTypeEnum type, String pattern, boolean ignoreHidden, int maxDepth) {
        return FindFileProvider.findFiles(List.of(root), pattern, false, false, 0, ignoreHidden, maxDepth, type, path -> true);
    }

    private static List<String> txtRows(String result) {
        return result.lines().filter(line -> line.endsWith(".txt")).toList();
    }

    private static void assertNoLineEquals(String result, String path) {
        boolean listed = result.lines().anyMatch(line -> line.equals(path));
        assertFalse(listed, "the starting directory must not be listed as a row exactly: " + result);
    }
    @TempDir
    Path directory;

    private Path buildTree(Path root) throws IOException {
        Files.createDirectories(root.resolve("sub"));
        Files.createDirectories(root.resolve("deep").resolve("deeper"));
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(Files.createFile(root.resolve("root.txt")), "r");
        Files.writeString(Files.createFile(root.resolve(".dotfile")), "d");
        Files.writeString(Files.createFile(root.resolve("sub").resolve("one.txt")), "1");
        Files.writeString(Files.createFile(root.resolve("deep").resolve("two.txt")), "2");
        Files.writeString(Files.createFile(root.resolve("deep").resolve("deeper").resolve("three.txt")), "3");
        Files.writeString(Files.createFile(root.resolve(".hidden").resolve("secret.txt")), "s");
        return root;
    }

    @Test
    void walkDepthTranslatesCallerDepthToOnePastWalkDepth() {
        // "Unlimited" is now bounded by MAX_DEPTH_CEILING: an unbounded walk was both unbounded WORK and an overflow
        // waiting to happen, since MAX_VALUE + 1 wraps negative and Files.walk rejects that.
        assertEquals(FindFileProvider.MAX_DEPTH_CEILING + 1, FindFileProvider.walkDepth(-1));
        assertEquals(1, FindFileProvider.walkDepth(0));
        assertEquals(2, FindFileProvider.walkDepth(1));
        assertEquals(3, FindFileProvider.walkDepth(2));
    }

    @Test
    void maxDepthZeroReturnsOnlyDirectChildren() throws Exception {
        buildTree(directory);
        String result = find(directory, 0, true);
        assertTrue(result.startsWith("Found 1 file(s):"), result);
        assertTrue(result.contains("root.txt"), result);
        assertFalse(result.contains("one.txt"), result);
        assertFalse(result.contains("two.txt"), result);
        assertFalse(result.contains("three.txt"), result);
    }

    @Test
    void maxDepthOneFindsExactlyOneLevelDown() throws Exception {
        buildTree(directory);
        String result = find(directory, 1, true);
        assertTrue(result.startsWith("Found 3 file(s):"), result);
        assertTrue(result.contains("root.txt"), result);
        assertTrue(result.contains("one.txt"), result);
        assertTrue(result.contains("two.txt"), result);
        assertFalse(result.contains("three.txt"), result);
    }

    @Test
    void negativeMaxDepthSearchesToTheCeilingAndReachesTheDeepestFile() throws Exception {
        buildTree(directory);
        String result = find(directory, -1, true);
        assertTrue(result.startsWith("Found 4 file(s):"), result);
        assertTrue(result.contains("root.txt"), result);
        assertTrue(result.contains("one.txt"), result);
        assertTrue(result.contains("two.txt"), result);
        assertTrue(result.contains("three.txt"), result);
        assertFalse(result.contains("secret.txt"), result);
    }

    @Test
    void ignoreHiddenExcludesDotNamesAndHiddenDirectoryContents() throws Exception {
        buildTree(directory);
        String result = findAll(directory, true);
        assertTrue(result.startsWith("Found 4 file(s):"), result);
        assertTrue(result.contains("root.txt"), result);
        assertFalse(result.contains(".dotfile"), result);
        assertFalse(result.contains("secret.txt"), result);
    }

    @Test
    void ignoreHiddenFalseIncludesDotNamesAndHiddenDirectoryContents() throws Exception {
        buildTree(directory);
        String result = findAll(directory, false);
        assertTrue(result.startsWith("Found 6 file(s):"), result);
        assertTrue(result.contains(".dotfile"), result);
        assertTrue(result.contains("secret.txt"), result);
    }

    @Test
    void aHiddenRootIsStillSearchedWhenNamedExplicitly() throws Exception {
        Path hiddenRoot = Files.createDirectories(directory.resolve(".configRoot"));
        Files.createDirectories(hiddenRoot.resolve("inner"));
        Files.writeString(Files.createFile(hiddenRoot.resolve("setting.txt")), "c");
        Files.writeString(Files.createFile(hiddenRoot.resolve("inner").resolve("nested.txt")), "n");
        String result = find(hiddenRoot, -1, true);
        assertTrue(result.startsWith("Found 2 file(s):"), result);
        assertTrue(result.contains("setting.txt"), result);
        assertTrue(result.contains("nested.txt"), result);
    }

    @Test
    void theSameFileReachedThroughParentAndChildRootsCountsOnce() throws Exception {
        buildTree(directory);
        String result = FindFileProvider.findFiles(List.of(directory, directory.resolve("deep")),
                "txt", false, false, 0, true, -1, path -> true);
        assertTrue(result.startsWith("Found 4 file(s):"), result);
        List<String> rows = txtRows(result);
        assertEquals(4, rows.size(), result);
        assertEquals(new HashSet<>(rows).size(), rows.size(), "each file must appear exactly once: " + result);
    }

    @Test
    void aSymlinkedAliasRootDoesNotDuplicateResults() throws Exception {
        buildTree(directory);
        Path alias = directory.resolve("alias-to-deep");
        try {
            Files.createSymbolicLink(alias, directory.resolve("deep"));
        }
        catch (IOException | UnsupportedOperationException e) {
            return;
        }
        String result = FindFileProvider.findFiles(List.of(directory, alias),
                "txt", false, false, 0, true, -1, path -> true);
        assertTrue(result.startsWith("Found 4 file(s):"), result);
        List<String> rows = txtRows(result);
        assertEquals(4, rows.size(), result);
        assertEquals(new HashSet<>(rows).size(), rows.size(), "each file must appear exactly once: " + result);
    }

    @Test
    void isHiddenPathIsFalseForAnOrdinaryFile() throws Exception {
        Path file = Files.createFile(directory.resolve("plain.txt"));
        assertFalse(FindFileProvider.isHiddenPath(file));
        Path dot = Files.createDirectories(directory.resolve(".hidden"));
        assertTrue(FindFileProvider.isHiddenPath(dot));
    }

    // Windows-only: a file whose name does NOT start with a dot but carries the DOS hidden attribute must still be
    // treated as hidden. The "dos:hidden" attribute is unsupported on a non-DOS filesystem (throws
    // UnsupportedOperationException), so this cannot run on Linux and is gated to Windows.
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsHiddenAttributeIsHonouredForANonDotName() throws Exception {
        Path path = Files.createFile(directory.resolve("visible.txt"));
        Files.setAttribute(path, "dos:hidden", true);
        assertTrue(FindFileProvider.isHiddenPath(path));

        String excluded = find(directory, -1, true);
        assertTrue(excluded.contains("Found 0 file(s):"), excluded);
        assertFalse(excluded.contains("visible.txt"), excluded);

        String included = find(directory, -1, false);
        assertTrue(included.contains("Found 1 file(s):"), included);
        assertTrue(included.contains("visible.txt"), included);
    }

    // Windows-only counterpart documenting the intended cross-platform rule at the place someone would change it: a
    // dot-named file is hidden on every platform including Windows. The leading-dot branch is platform-independent, so
    // it is already exercised by isHiddenPathIsFalseForAnOrdinaryFile on Linux; this gated copy pins the rule on the
    // attribute-honouring platform too.
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dotNamedFileIsHiddenOnWindows() throws Exception {
        Path dot = Files.createFile(directory.resolve(".hidden.txt"));
        assertTrue(FindFileProvider.isHiddenPath(dot));
    }

    @Test
    void dirFindsAnEmptyDirectory() throws Exception {
        Files.createDirectories(directory.resolve("empty"));
        Files.createDirectories(directory.resolve("populated"));
        Files.writeString(Files.createFile(directory.resolve("populated").resolve("in.txt")), "x");
        String result = findTyped(directory, FindFileTypeEnum.DIR, null, true, -1);
        assertTrue(result.startsWith("Found 2 directory(ies):"), result);
        assertTrue(result.contains("empty"), result);
        assertTrue(result.contains("populated"), result);
    }

    @Test
    void fileExcludesDirectoriesAndDirExcludesFiles() throws Exception {
        Files.createDirectories(directory.resolve("onlydir"));
        Files.writeString(Files.createFile(directory.resolve("onlyfile.txt")), "x");
        String dirResult = findTyped(directory, FindFileTypeEnum.DIR, null, true, -1);
        assertTrue(dirResult.startsWith("Found 1 directory(ies):"), dirResult);
        assertTrue(dirResult.contains("onlydir"), dirResult);
        assertFalse(dirResult.contains("onlyfile.txt"), dirResult);
        String fileResult = findTyped(directory, FindFileTypeEnum.FILE, null, true, -1);
        assertTrue(fileResult.startsWith("Found 1 file(s):"), fileResult);
        assertTrue(fileResult.contains("onlyfile.txt"), fileResult);
        assertFalse(fileResult.contains("onlydir"), fileResult);
    }

    @Test
    void startingDirectoryIsNeverReturnedForEitherType() throws Exception {
        Files.createDirectories(directory.resolve("sub"));
        String dirResult = findTyped(directory, FindFileTypeEnum.DIR, null, true, 0);
        assertTrue(dirResult.startsWith("Found 1 directory(ies):"), dirResult);
        assertTrue(dirResult.contains("sub"), dirResult);
        assertNoLineEquals(dirResult, directory.toString());
        String fileResult = findTyped(directory, FindFileTypeEnum.FILE, null, true, 0);
        assertNoLineEquals(fileResult, directory.toString());
    }

    @Test
    void fromResolvesDefaultsCaseAndSpaceAndRejectsUnknown() {
        assertEquals(FindFileTypeEnum.FILE, FindFileTypeEnum.from(null));
        assertEquals(FindFileTypeEnum.FILE, FindFileTypeEnum.from("   "));
        assertEquals(FindFileTypeEnum.FILE, FindFileTypeEnum.from("file"));
        assertEquals(FindFileTypeEnum.DIR, FindFileTypeEnum.from("dir"));
        assertEquals(FindFileTypeEnum.DIR, FindFileTypeEnum.from("DIR"));
        assertEquals(FindFileTypeEnum.DIR, FindFileTypeEnum.from(" Dir "));
        assertNull(FindFileTypeEnum.from("directory"));
        assertNull(FindFileTypeEnum.from("folder"));
    }

    @Test
    void resultWordingFollowsTheType() throws Exception {
        // No pattern means "list everything", so there is nothing to quote back — the old wording reported the
        // ABSENCE of a filter as though the filter itself were the literal string "null".
        assertEquals("No directories found", findTyped(directory, FindFileTypeEnum.DIR, null, true, -1));
        Files.createDirectories(directory.resolve("d1"));
        String some = findTyped(directory, FindFileTypeEnum.DIR, null, true, -1);
        assertTrue(some.startsWith("Found 1 directory(ies):"), some);
        assertFalse(some.contains("file(s)"), some);
    }

    @Test
    void dirComposesWithIgnoreHidden() throws Exception {
        Files.createDirectories(directory.resolve(".hidden"));
        Files.createDirectories(directory.resolve("visible"));
        String result = findTyped(directory, FindFileTypeEnum.DIR, null, true, -1);
        assertTrue(result.startsWith("Found 1 directory(ies):"), result);
        assertTrue(result.contains("visible"), result);
        assertFalse(result.contains(".hidden"), result);
        String permissive = findTyped(directory, FindFileTypeEnum.DIR, null, false, -1);
        assertTrue(permissive.startsWith("Found 2 directory(ies):"), permissive);
        assertTrue(permissive.contains(".hidden"), permissive);
    }

    @Test
    void dirWithMaxDepthZeroReturnsOnlyImmediateChildDirectories() throws Exception {
        Files.createDirectories(directory.resolve("a").resolve("b"));
        Files.createDirectories(directory.resolve("c"));
        String result = findTyped(directory, FindFileTypeEnum.DIR, null, true, 0);
        assertTrue(result.startsWith("Found 2 directory(ies):"), result);
        assertTrue(result.contains("a"), result);
        assertTrue(result.contains("c"), result);
        assertFalse(result.contains("b"), result);
    }
}
