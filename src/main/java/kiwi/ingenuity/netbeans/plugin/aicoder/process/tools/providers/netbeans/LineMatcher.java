package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The single source of truth for "literal text or regex, against a line" — shared by
 * {@link SearchProvider#searchInFiles} and {@link EditorContextProvider#filterFileContent} so the two tools cannot
 * quietly drift into different regex semantics. A hit in one and a miss in the other, for what looks like the same
 * query, would be a hard trap for a caller moving between them.
 * <p>
 * Literal mode wraps the query in {@link Pattern#quote}, so a literal search can never accidentally behave as a regex.
 * Case-insensitivity is applied as {@link Pattern#CASE_INSENSITIVE}, never by lowercasing the query or the line —
 * flag-based matching handles Unicode case folding correctly and never mutates what the caller sees. Matching against
 * each line is {@code find()}-style (a substring match), not a full-line match — callers must not silently change that
 * without updating both tools identically.
 * <p>
 * <b>Runaway-pattern budget.</b> Queries arrive from an LLM, and a pathological regex (classic catastrophic
 * backtracking, e.g. {@code (a+)+$}) can otherwise hang the MCP handler thread forever. {@link #findWithTimeout} bounds
 * each match attempt with a wall-clock deadline checked from inside the scanned text, throwing
 * {@link RegexTimeoutException} when exceeded; callers surface that distinctly from "no matches". The deadline caps
 * TIME, not input size — even a short line can be lethal to a naive matcher.
 */
public final class LineMatcher {

    /**
     * Wall-clock budget for one {@link #findWithTimeout} call. Package-private and mutable purely so tests can shrink
     * it deterministically; production always uses this default.
     */
    static volatile long matchTimeoutMillis = 1_000L;

    /**
     * Compiles {@code query} per {@code isRegex}/{@code caseSensitive}.
     *
     * @throws PatternSyntaxException if {@code isRegex} is true and {@code query} is not a valid regex; callers should
     * catch this and report it distinctly from "no matches" (e.g. {@code "Invalid regex: "
     * + e.getMessage()}) — an empty result and a bad pattern must not look the same to the caller.
     */
    public static Pattern compile(String query, boolean isRegex, boolean caseSensitive) {
        String expr = isRegex ? query : Pattern.quote(query);
        return caseSensitive ? Pattern.compile(expr) : Pattern.compile(expr, Pattern.CASE_INSENSITIVE);
    }

    /**
     * Runs one {@code find()} over {@code line} under the {@link #matchTimeoutMillis} wall-clock deadline.
     *
     * @throws RegexTimeoutException when matching exceeds the budget — treat like an invalid pattern: report it, never
     * silently count the line as "no match"
     */
    public static boolean findWithTimeout(Pattern pattern, CharSequence line) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(matchTimeoutMillis);
        return pattern.matcher(new DeadlineCharSequence(line, deadlineNanos)).find();
    }

    private LineMatcher() {
    }

    /**
     * Thrown when a match attempt exceeds {@link #matchTimeoutMillis}.
     */
    public static final class RegexTimeoutException extends RuntimeException {

        private final long timeoutMillis;

        RegexTimeoutException(long timeoutMillis) {
            super("Regex match exceeded the " + timeoutMillis + " ms budget");
            this.timeoutMillis = timeoutMillis;
        }

        public long timeoutMillis() {
            return timeoutMillis;
        }
    }

    /**
     * Delegating CharSequence whose periodic checkpoint inside {@code charAt} aborts the engine mid-scan. The JDK regex
     * engine touches characters through this interface, so the check fires exactly while the pathological pattern is
     * spinning. Checkpoint granularity (every 1024th character) keeps the overhead negligible for honest patterns.
     */
    private static final class DeadlineCharSequence implements CharSequence {

        private static final int CHECK_INTERVAL = 1024;

        private final CharSequence delegate;
        private final long deadlineNanos;

        DeadlineCharSequence(CharSequence delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (index % CHECK_INTERVAL == 0 && System.nanoTime() > deadlineNanos) {
                throw new RegexTimeoutException(matchTimeoutMillis);
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

}
