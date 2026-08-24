package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins the shared regex contract both SearchInFilesTool and FilterFileContentTool depend on, so the two cannot quietly
 * drift into different semantics for what looks like the same query.
 */
class LineMatcherTest {

    /**
     * The specific behaviour {@link Pattern#quote} provides and the one most likely to be lost if someone later
     * "simplifies" the compilation: a literal query must never be interpreted as a regex.
     */
    @Test
    void literalMode_treatsRegexMetacharactersLiterally() {
        Pattern p = LineMatcher.compile("a.b", false, false);

        assertTrue(p.matcher("a.b").find(), "the literal dot must still match itself");
        assertFalse(p.matcher("axb").find(), "'.' must not act as a regex wildcard in literal mode");
    }

    @Test
    void regexMode_treatsMetacharactersAsRegex() {
        Pattern p = LineMatcher.compile("a.b", true, false);

        assertTrue(p.matcher("a.b").find());
        assertTrue(p.matcher("axb").find(), "'.' must act as a wildcard when isRegex is true");
    }

    /**
     * Case-insensitivity must be a Pattern flag, not lowercasing the query or the line — flag-based matching is what
     * Pattern.CASE_INSENSITIVE gives, and it must survive verbatim in the reported match text (asserted via the
     * surrounding tool tests); here it is pinned at the compile level.
     */
    @Test
    void caseInsensitiveByDefault_matchesRegardlessOfCase() {
        Pattern p = LineMatcher.compile("warning", false, false);

        assertTrue(p.matcher("WARNING: low disk").find());
        assertTrue(p.matcher("Warning: low disk").find());
    }

    @Test
    void caseSensitiveWhenRequested_excludesDifferentCase() {
        Pattern p = LineMatcher.compile("Warning", false, true);

        assertTrue(p.matcher("Warning: low disk").find());
        assertFalse(p.matcher("WARNING: low disk").find());
    }

    /**
     * find()-style substring matching, not a full-line match — the query need not describe the whole line to hit.
     */
    @Test
    void matchIsSubstringNotFullLine() {
        Pattern p = LineMatcher.compile("BUILD", false, false);

        assertTrue(p.matcher("[INFO] BUILD SUCCESS").find());
    }

    @Test
    void invalidRegex_throwsPatternSyntaxExceptionRatherThanReturningEmpty() {
        assertThrows(PatternSyntaxException.class, () -> LineMatcher.compile("[", true, false));
    }

    // ---- findWithTimeout: the runaway-pattern budget ----
    private long savedTimeoutMillis;

    @org.junit.jupiter.api.BeforeEach
    void saveBudget() {
        savedTimeoutMillis = LineMatcher.matchTimeoutMillis;
    }

    @org.junit.jupiter.api.AfterEach
    void restoreBudget() {
        LineMatcher.matchTimeoutMillis = savedTimeoutMillis;
    }

    /**
     * The exact scenario that motivated the guard: a classic catastrophic-backtracking regex ({@code (a+)+$}) against a
     * line of {@code a}s spins effectively forever. With a tiny budget it must abort as RegexTimeoutException quickly
     * instead of hanging the handler thread — and "quickly" is asserted on wall-clock time, not just exception type.
     */
    @Test
    void adversarialRegex_abortsOnDeadline_insteadOfHangingForever() {
        LineMatcher.matchTimeoutMillis = 150;
        Pattern evil = LineMatcher.compile("(a+)+$", true, false);
        // The trailing '!' is what arms the bomb: an all-'a' input MATCHES (a+)+$ outright,
        // so without a final character the pattern can never consume, there is no
        // backtracking storm, and there would be nothing to time out.
        String bomb = "a".repeat(4_000) + "!";

        long start = System.nanoTime();
        assertThrows(LineMatcher.RegexTimeoutException.class,
                () -> LineMatcher.findWithTimeout(evil, bomb));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 5_000,
                "timeout must actually bound the attempt, took " + elapsedMillis + " ms");
    }

    /**
     * Honest patterns must be untouched by the guard: same results as a bare matcher.
     */
    @Test
    void findWithTimeout_matchesAndMissesLikePlainFind() {
        Pattern p = LineMatcher.compile("BUILD", false, false);

        assertTrue(LineMatcher.findWithTimeout(p, "[INFO] BUILD SUCCESS"));
        assertFalse(LineMatcher.findWithTimeout(p, "[INFO] nothing here"));

        Pattern rx = LineMatcher.compile("a+b", true, false); // regex, case-INSENSITIVE
        assertTrue(LineMatcher.findWithTimeout(rx, "xxAAAbbb"));
        assertFalse(LineMatcher.findWithTimeout(rx, "only b's: bbb"));
    }

    /**
     * An exhausted deadline surfaces as the timeout on any line, never as a wrong result.
     */
    @Test
    void zeroBudget_throwsRatherThanReturningAMisleadingMiss() {
        LineMatcher.matchTimeoutMillis = 0;
        Pattern p = LineMatcher.compile("ok", false, false);

        assertThrows(LineMatcher.RegexTimeoutException.class,
                () -> LineMatcher.findWithTimeout(p, "ok"));
    }
}
