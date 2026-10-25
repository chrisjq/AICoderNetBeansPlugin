package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link GitHunkPatch} — pure-text parse and build of git unified diffs for partial staging. No git, no
 * filesystem: everything is string in, string out.
 */
class GitHunkPatchTest {

    // ---- parse ------------------------------------------------------------
    @Test
    @Timeout(10)
    void parseTwoFiles() {
        String diff = "diff --git a/A.java b/A.java\n"
                + "index 1111111..2222222 100644\n"
                + "--- a/A.java\n"
                + "+++ b/A.java\n"
                + "@@ -5 +5 @@ class A\n"
                + " c1\n"
                + "-old\n"
                + "+new\n"
                + "diff --git a/B.java b/B.java\n"
                + "index 3333333..4444444 100644\n"
                + "--- a/B.java\n"
                + "+++ b/B.java\n"
                + "@@ -1,2 +1,2 @@\n"
                + " c1\n"
                + "-old\n"
                + "+new\n"
                + "@@ -9,1 +9,2 @@\n"
                + " c9\n"
                + "-x\n"
                + "+y\n"
                + "+z\n";

        List<GitHunkPatch.FileDiff> files = GitHunkPatch.parse(diff);

        assertEquals(2, files.size());

        GitHunkPatch.FileDiff a = files.get(0);
        assertEquals("A.java", a.path());
        assertEquals(1, a.hunks().size());
        GitHunkPatch.FileDiff.Hunk a0 = a.hunks().get(0);
        assertEquals(5, a0.oldStart());
        assertEquals(1, a0.oldCount()); // missing count -> 1
        assertEquals(5, a0.newStart());
        assertEquals(1, a0.newCount()); // missing count -> 1
        assertEquals("class A", a0.headerSuffix());
        assertEquals(4, a.headerLines().size());
        assertEquals("diff --git a/A.java b/A.java", a.headerLines().get(0));
        assertEquals(3, a0.bodyLines().size());

        GitHunkPatch.FileDiff b = files.get(1);
        assertEquals("B.java", b.path());
        assertEquals(2, b.hunks().size());
        assertEquals(2, b.hunks().get(0).oldCount());
        assertEquals(1, b.hunks().get(1).oldCount());
        assertEquals(2, b.hunks().get(1).newCount());
    }

    // ---- whole-hunk selection ---------------------------------------------
    @Test
    @Timeout(10)
    void selectHunkTwoOfThreeKeepsItVerbatim() {
        String diff = "diff --git a/x b/x\n"
                + "index aaa..bbb\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,2 +1,2 @@\n"
                + " cA\n"
                + "-o1\n"
                + "+n1\n"
                + "@@ -10,3 +10,3 @@ funcTwo\n"
                + " ctx1\n"
                + "-o2\n"
                + "+n2\n"
                + " ctx2\n"
                + "@@ -20,2 +20,3 @@\n"
                + " c3\n"
                + "-d1\n"
                + "+a1\n"
                + "+a2\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);
        assertEquals(3, f.hunks().size());

        String patch = GitHunkPatch.buildPatch(f, List.of(new GitHunkPatch.Selection(2, null, null)));

        // header lines + only hunk 2; whole hunk so its counts and newStart are unchanged.
        String expected = "diff --git a/x b/x\n"
                + "index aaa..bbb\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -10,3 +10,3 @@ funcTwo\n"
                + " ctx1\n"
                + "-o2\n"
                + "+n2\n"
                + " ctx2\n";
        assertEquals(expected, patch);
    }

    @Test
    @Timeout(10)
    void selectingHunksOneAndThreeShiftsThirdNewStart() {
        String diff = "diff --git a/x b/x\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,2 +1,3 @@\n" // hunk 1: net +1
                + " c\n"
                + "-o\n"
                + "+n1\n"
                + "+n2\n"
                + "@@ -5,1 +5,1 @@\n" // hunk 2: not selected
                + " mid\n"
                + "@@ -20,2 +20,2 @@\n" // hunk 3
                + " a\n"
                + "-b\n"
                + "+c\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        String patch = GitHunkPatch.buildPatch(f, List.of(
                                               new GitHunkPatch.Selection(1, null, null),
                                               new GitHunkPatch.Selection(3, null, null)));

        // hunk 1 emitted whole, newStart unchanged; ...
        assertTrue(patch.contains("@@ -1,2 +1,3 @@"), patch);
        // ... hunk 3's newStart is 20 shifted by hunk 1's net change (+1) only (hunk 2 excluded).
        assertTrue(patch.contains("@@ -20,2 +21,2 @@"), patch);
        // the unselected hunk 2 is gone.
        assertFalse(patch.contains("mid"), patch);
    }

    // ---- line ranges ------------------------------------------------------
    @Test
    @Timeout(10)
    void lineRangeDropsPlusAndTurnsMinusToContext() {
        String diff = "diff --git a/x b/x\n"
                + "index aaa..bbb\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,4 +1,4 @@\n"
                + " c1\n"
                + "-old1\n"
                + "+new1\n"
                + "-old2\n"
                + "+new2\n"
                + " c2\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        // New-file line 2 is +new1. Range [2,2] keeps +new1 and the '-' line before it, but drops
        // +new2 (line 3) and turns the intervening -old2 into a context line. The header is
        // recomputed from the resulting body (4 old / 4 new here).
        String patch = GitHunkPatch.buildPatch(f, List.of(new GitHunkPatch.Selection(1, 2, 2)));

        String expected = "diff --git a/x b/x\n"
                + "index aaa..bbb\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,4 +1,4 @@\n"
                + " c1\n"
                + "-old1\n"
                + "+new1\n"
                + " old2\n"
                + " c2\n";
        assertEquals(expected, patch);
    }

    /**
     * The bug the previous line-range test surfaced: git emits a replacement as a block of all '-' lines then all '+'
     * lines. The j-th '-' line must pair with the j-th '+' line (the same new-file number), and the emitted block must
     * keep the '-' lines before the '+' lines.
     */
    @Test
    @Timeout(10)
    void lineRangePairsEachDeletedLineWithItsReplacement() {
        String diff = "diff --git a/x b/x\n"
                + "index aaa..bbb\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -10,3 +10,3 @@\n"
                + "-a10\n"
                + "-a11\n"
                + "-a12\n"
                + "+b10\n"
                + "+b11\n"
                + "+b12\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        // Range 11..11 keeps -a11/+b11 (both at new number 11), turns -a10/-a12 into context, drops +b10/+b12.
        String patch = GitHunkPatch.buildPatch(f, List.of(new GitHunkPatch.Selection(1, 11, 11)));

        String expected = "diff --git a/x b/x\n"
                + "index aaa..bbb\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -10,3 +10,3 @@\n"
                + " a10\n"
                + "-a11\n"
                + "+b11\n"
                + " a12\n";
        assertEquals(expected, patch);
    }

    // ---- no-newline marker ------------------------------------------------
    @Test
    @Timeout(10)
    void noNewlineMarkerStaysWithItsLine() {
        String diff = "diff --git a/x b/x\n"
                + "index aaa..bbb\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,2 +1,2 @@\n"
                + " c1\n"
                + "-old1\n"
                + "+new1\n"
                + "\\ No newline at end of file\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        // Whole hunk: the marker follows a kept line, so it stays with +new1.
        String patch = GitHunkPatch.buildPatch(f, List.of(new GitHunkPatch.Selection(1, null, null)));

        assertTrue(patch.endsWith("+new1\n\\ No newline at end of file\n"), patch);
    }

    // ---- errors -----------------------------------------------------------
    @Test
    @Timeout(10)
    void outOfRangeHunkThrows() {
        String diff = "diff --git a/x b/x\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,1 +1,1 @@\n"
                + " c\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        Exception e = assertThrows(IllegalArgumentException.class,
                                   () -> GitHunkPatch.buildPatch(f, List.of(new GitHunkPatch.Selection(3, null, null))));
        assertTrue(e.getMessage().contains("has 1 hunks"), e.getMessage());
        assertTrue(e.getMessage().contains("x"), e.getMessage());
    }

    @Test
    @Timeout(10)
    void invalidSelectionsThrow() {
        String diff = "diff --git a/x b/x\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,2 +1,2 @@\n"
                + " c\n"
                + "-old\n"
                + "+new\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        assertThrows(IllegalArgumentException.class,
                     () -> GitHunkPatch.buildPatch(f, List.of()));

        Exception e = assertThrows(IllegalArgumentException.class,
                                   () -> GitHunkPatch.buildPatch(f, List.of(new GitHunkPatch.Selection(1, 5, 2))));
        assertTrue(e.getMessage().contains("fromLine 5 > toLine 2"), e.getMessage());
    }

    // ---- optional (file-wide) hunk ----------------------------------------
    @Test
    @Timeout(10)
    void rangeWithoutHunkAppliesToEveryHunk() {
        String diff = "diff --git a/x b/x\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,2 +1,2 @@\n"
                + " c1\n"
                + "-o1\n"
                + "+n1\n"
                + "@@ -10,2 +10,2 @@\n"
                + " c10\n"
                + "-o10\n"
                + "+n10\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        // hunk 1's change (-o1/+n1) is at new line 2 (line 1 is the context line c1); hunk 2's change is at new
        // line 11 (line 10 is its context c10). Range 2..2 keeps only hunk 1's change.
        String patch = GitHunkPatch.buildPatch(f, List.of(new GitHunkPatch.Selection(null, 2, 2)));

        // hunk 1 keeps +n1 (with its paired -o1); hunk 2 has no change in range, so it is omitted.
        String expected = "diff --git a/x b/x\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,2 +1,2 @@\n"
                + " c1\n"
                + "-o1\n"
                + "+n1\n";
        assertEquals(expected, patch);
    }

    @Test
    @Timeout(10)
    void rangeWithoutHunkThatTouchesNoChangeThrows() {
        String diff = "diff --git a/x b/x\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,2 +1,2 @@\n"
                + " c1\n"
                + "-o1\n"
                + "+n1\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        Exception e = assertThrows(IllegalArgumentException.class,
                                   () -> GitHunkPatch.buildPatch(f, List.of(new GitHunkPatch.Selection(null, 99, 100))));
        assertTrue(e.getMessage().contains("no changes in the selected range"), e.getMessage());
    }

    @Test
    @Timeout(10)
    void twoRangesOnTheSameHunkAreCombined() {
        String diff = "diff --git a/x b/x\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,3 +1,3 @@\n"
                + "-a1\n"
                + "-a2\n"
                + "-a3\n"
                + "+b1\n"
                + "+b2\n"
                + "+b3\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        // The union of [1,2] and [2,3] is [1,3], so every change line of the single hunk is kept.
        String patch = GitHunkPatch.buildPatch(f, List.of(
                                               new GitHunkPatch.Selection(1, 1, 2),
                                               new GitHunkPatch.Selection(1, 2, 3)));

        String expected = "diff --git a/x b/x\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,3 +1,3 @@\n"
                + "-a1\n"
                + "+b1\n"
                + "-a2\n"
                + "+b2\n"
                + "-a3\n"
                + "+b3\n";
        assertEquals(expected, patch);
    }

    @Test
    @Timeout(10)
    void wholeFileSelectionThrows() {
        String diff = "diff --git a/x b/x\n"
                + "--- a/x\n"
                + "+++ b/x\n"
                + "@@ -1,1 +1,1 @@\n"
                + "-o\n"
                + "+n\n";

        GitHunkPatch.FileDiff f = GitHunkPatch.parse(diff).get(0);

        Exception e = assertThrows(IllegalArgumentException.class,
                                   () -> GitHunkPatch.buildPatch(f, List.of(new GitHunkPatch.Selection(null, null, null))));
        assertTrue(e.getMessage().contains("whole-file selection"), e.getMessage());
    }
}
