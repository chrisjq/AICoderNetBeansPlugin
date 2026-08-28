package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Robustness of the FindFile walk: the depth ceiling, the arithmetic that used to overflow, and the no-results message.
 * <p>
 * Every case here was raised by an independent reviewer against the shipped 1.3.50 build, and two of them were
 * reproduced live through the real MCP tool before this test existed.
 */
class FindFileRobustnessTest {

    @TempDir
    Path tempDir;

    /**
     * The overflow. {@code walkDepth} used to compute {@code maxDepth + 1} unguarded, so the largest legal int wrapped
     * to {@code Integer.MIN_VALUE}; {@code Files.walk} rejects a negative depth with {@code IllegalArgumentException},
     * and the walk's try caught only {@code IOException}, so it escaped the tool. Live reproduction against 1.3.50:
     * {@code FindFile(maxDepth: 2147483647)} returned {@code Internal error: 'maxDepth' is negative}.
     */
    @Test
    void largestIntDepthDoesNotOverflowIntoANegativeWalkDepth() {
        int walk = FindFileProvider.walkDepth(Integer.MAX_VALUE);

        assertTrue(walk > 0, "the translated depth must stay positive; it overflowed to " + walk);
        assertEquals(FindFileProvider.MAX_DEPTH_CEILING + 1, walk,
                "an absurd depth clamps to the ceiling rather than wrapping");
    }

    /**
     * The ceiling applies to "unlimited" too. Unbounded descent was measured at 853ms against 23ms for depth 0 on this
     * project, and nothing stopped a walk from descending forever on a pathological tree.
     */
    @Test
    void unlimitedDepthIsBoundedByTheCeiling() {
        assertEquals(FindFileProvider.MAX_DEPTH_CEILING + 1, FindFileProvider.walkDepth(-1),
                "negative means unlimited, but unlimited is still bounded");
    }

    @Test
    void depthAboveTheCeilingIsClamped() {
        assertEquals(FindFileProvider.MAX_DEPTH_CEILING + 1,
                FindFileProvider.walkDepth(FindFileProvider.MAX_DEPTH_CEILING + 500));
    }

    /**
     * The negative control: ordinary depths must still translate exactly, or the clamp would have quietly broken the
     * documented 0-is-this-directory semantics that the rest of the suite relies on.
     */
    @Test
    void ordinaryDepthsAreUnaffectedByTheCeiling() {
        assertEquals(1, FindFileProvider.walkDepth(0));
        assertEquals(2, FindFileProvider.walkDepth(1));
        assertEquals(6, FindFileProvider.walkDepth(5));
    }

    /**
     * A walk at the largest legal depth must complete rather than throwing out of the provider.
     */
    @Test
    void aWalkAtTheLargestIntDepthCompletes() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "x");

        String result = assertDoesNotThrow(() -> FindFileProvider.findFiles(
                List.of(tempDir), "a.txt", false, false, 0, true, Integer.MAX_VALUE, path -> true));

        assertTrue(result.contains("a.txt"), result);
    }

    /**
     * The schema says omitting {@code pattern} lists everything, so reporting "matching: null" is nonsense. Reproduced
     * live: a directory with no matching entries answered {@code No directories found matching: null}.
     */
    @Test
    void noResultsWithoutAPatternDoesNotSayNull() throws Exception {
        Path empty = Files.createDirectory(tempDir.resolve("empty"));

        String result = FindFileProvider.findFiles(
                List.of(empty), null, false, false, 0, true, -1, path -> true);

        assertFalse(result.contains("null"),
                "a pattern-less search must not report the literal null: " + result);
        assertTrue(result.startsWith("No files found"), result);
    }

    /**
     * An unreadable entry must not abort the search, but it must not vanish silently either.
     * <p>
     * One unreadable DIRECTORY takes its whole subtree with it, so a caller reading a bare "Found N" would treat an
     * incomplete answer as a complete one — the silent-truncation shape this codebase keeps meeting. Two reviewers
     * raised it independently. The walk continues, and the header says how much it could not read.
     * <p>
     * Skipped when the directory stays readable anyway: running as root, or on a filesystem that ignores permission
     * bits, there is nothing to deny and the test would assert on the environment rather than the code.
     */
    @Test
    void anUnreadableDirectoryIsDisclosedRatherThanSilentlyDropped() throws Exception {
        Files.writeString(tempDir.resolve("visible.txt"), "x");
        Path locked = Files.createDirectory(tempDir.resolve("locked"));
        Files.writeString(locked.resolve("hidden-by-permissions.txt"), "x");
        try {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        }
        catch (UnsupportedOperationException e) {
            Assumptions.abort("filesystem does not support POSIX permissions");
        }
        Assumptions.assumeFalse(Files.isReadable(locked), "directory is still readable, nothing would be denied");

        try {
            String result = FindFileProvider.findFiles(
                    List.of(tempDir), null, false, false, 0, true, -1, path -> true);

            assertTrue(result.contains("visible.txt"),
                    "the readable part of the tree must still be returned: " + result);
            assertTrue(result.contains("unreadable"),
                    "an incomplete answer must say so rather than looking complete: " + result);
            assertTrue(result.contains("may be incomplete"), result);
        }
        finally {
            // Restore permissions or @TempDir cannot clean up after itself.
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * The negative control. Without it the assertion above would also pass if EVERY search claimed to be incomplete,
     * which would train callers to ignore the notice entirely.
     */
    @Test
    void afullyReadableTreeCarriesNoIncompletenessNotice() throws Exception {
        Files.writeString(tempDir.resolve("plain.txt"), "x");

        String result = FindFileProvider.findFiles(
                List.of(tempDir), null, false, false, 0, true, -1, path -> true);

        assertFalse(result.contains("may be incomplete"),
                "a complete answer must not be hedged: " + result);
    }

    /**
     * With a pattern, the message must still quote it — otherwise the fix above would have removed useful information
     * rather than the defect.
     */
    @Test
    void noResultsWithAPatternStillQuotesThePattern() throws Exception {
        Path empty = Files.createDirectory(tempDir.resolve("empty2"));

        String result = FindFileProvider.findFiles(
                List.of(empty), "needle", false, false, 0, true, -1, path -> true);

        assertTrue(result.contains("needle"),
                "the caller needs to see what was searched for: " + result);
    }
}
