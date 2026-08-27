package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

/**
 * Pure decision rules for the Accept/Reject permission path. Kept free of Swing so unit tests can lock the fail-closed
 * behaviour that protects the diff gate.
 */
public final class PermissionDiffPolicy {

    /**
     * @param toolName Edit or Write (other tools should not reach the permission path)
     * @param filePath target path (must be non-blank)
     * @param original current file text, or empty string if the file does not exist yet
     * @param oldString Edit only
     * @param newString Edit only
     * @param writeContent Write only
     */
    public static Decision decide(String toolName, String filePath, String original,
            String oldString, String newString, String writeContent) {
        return decide(toolName, filePath, original, oldString, newString, writeContent, false);
    }

    /**
     * @param replaceAll Edit only — replace every occurrence rather than the first. Must match what the apply side
     * ({@code RefactoringProvider.applyEdit}) is going to do with the same inputs: the panel's whole purpose is that
     * the user approves the exact change that lands, so a preview showing one replacement while the apply performs
     * twelve turns the gate into a lie.
     */
    public static Decision decide(String toolName, String filePath, String original,
            String oldString, String newString, String writeContent, boolean replaceAll) {
        if (filePath == null || filePath.isBlank()) {
            return Decision.deny("Missing file path");
        }
        String orig = original != null ? original : "";
        String proposed;
        if ("Write".equals(toolName)) {
            proposed = writeContent != null ? writeContent : "";
        }
        else if ("Edit".equals(toolName)) {
            if (oldString == null || newString == null) {
                return Decision.deny("Edit is missing old_string or new_string");
            }
            if (!orig.contains(oldString)) {
                String whitespaceHint = diagnoseWhitespaceMismatch(orig, oldString);
                if (whitespaceHint != null) {
                    return Decision.deny(whitespaceHint);
                }
                return Decision.deny("Edit old_string was not found in the file — refusing silent apply");
            }
            proposed = replaceAll
                    ? replaceEvery(orig, oldString, newString)
                    : replaceFirst(orig, oldString, newString);
        }
        else {
            return Decision.deny("Unsupported tool for permission diff: " + toolName);
        }
        if (orig.equals(proposed)) {
            return Decision.allowSilent(proposed);
        }
        return Decision.showDiff(proposed);
    }

    /**
     * Literal single replacement. Deliberately not {@code String.replaceFirst}, which takes a REGEX — the caller's
     * old_string is arbitrary source text and would be interpreted, so a stray {@code (} or {@code .} would either
     * throw or silently match the wrong span.
     */
    public static String replaceFirst(String orig, String oldString, String newString) {
        int idx = orig.indexOf(oldString);
        if (idx < 0) {
            return orig;
        }
        return orig.substring(0, idx) + newString + orig.substring(idx + oldString.length());
    }

    /**
     * Literal replacement of every occurrence, scanning left to right and resuming AFTER each replacement.
     *
     * <p>
     * Not {@code String.replaceAll} (regex, same trap as above) and not {@code String.replace} — that would be correct
     * here, but this must stay character-identical to what the apply side does, and keeping both on one explicit
     * algorithm is what guarantees that. Advancing past the inserted text also means a replacement can never be
     * rescanned, so {@code "aa" -> "aaa"} terminates instead of growing forever.
     */
    public static String replaceEvery(String orig, String oldString, String newString) {
        if (oldString.isEmpty()) {
            return orig;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        int idx;
        while ((idx = orig.indexOf(oldString, from)) >= 0) {
            out.append(orig, from, idx).append(newString);
            from = idx + oldString.length();
        }
        out.append(orig, from, orig.length());
        return out.toString();
    }

    private static String diagnoseWhitespaceMismatch(String orig, String oldString) {
        String[] origLines = orig.split("\n", -1);
        String[] oldLines = oldString.split("\n", -1);
        if (oldLines.length > origLines.length) {
            return null;
        }
        // Strip once up front rather than per comparison: overlapping candidate
        // windows would otherwise re-strip the same original lines repeatedly.
        String[] origStripped = new String[origLines.length];
        for (int i = 0; i < origLines.length; i++) {
            origStripped[i] = origLines[i].strip();
        }
        String[] oldStripped = new String[oldLines.length];
        for (int i = 0; i < oldLines.length; i++) {
            oldStripped[i] = oldLines[i].strip();
        }
        for (int start = 0; start <= origLines.length - oldLines.length; start++) {
            boolean match = true;
            for (int j = 0; j < oldLines.length; j++) {
                if (!origStripped[start + j].equals(oldStripped[j])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                Integer delta = consistentLeadingSpaceDelta(origLines, start, oldLines);
                if (delta != null && Math.abs(delta) > 0) {
                    // The delta is real, but a line may also differ in ways strip()
                    // hides (a trailing \r, trailing spaces). Name indentation as
                    // the leading cause without implying it is the only one, or an
                    // agent fixes the indent, resubmits, and fails again with no
                    // idea why.
                    return "old_string was not found. A match WAS found ignoring leading whitespace — "
                            + "your indentation differs by " + Math.abs(delta) + " space"
                            + (Math.abs(delta) == 1 ? "" : "s") + " per line"
                            + " (a copied line-number gutter is the usual cause). "
                            + "Re-read the file and match the source byte-for-byte — "
                            + "indentation may not be the only difference.";
                }
                return "old_string was not found. A match WAS found ignoring whitespace — "
                        + "the difference may be line endings (CRLF vs LF), trailing whitespace, or tabs vs spaces. "
                        + "Re-read the file and match the source byte-for-byte.";
            }
        }
        return null;
    }

    private static Integer consistentLeadingSpaceDelta(String[] origLines, int origOffset, String[] oldLines) {
        Integer delta = null;
        for (int j = 0; j < oldLines.length; j++) {
            String origLine = origLines[origOffset + j];
            String oldLine = oldLines[j];
            if (origLine.isBlank() || oldLine.isBlank()) {
                continue;
            }
            if (hasLeadingTab(origLine) || hasLeadingTab(oldLine)) {
                return null;
            }
            int d = countLeadingSpaces(oldLine) - countLeadingSpaces(origLine);
            if (delta == null) {
                delta = d;
            }
            else if (!delta.equals(d)) {
                return null;
            }
        }
        return delta;
    }

    private static boolean hasLeadingTab(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                return true;
            }
            if (c != ' ') {
                break;
            }
        }
        return false;
    }

    private static int countLeadingSpaces(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ' ') {
                count++;
            }
            else {
                break;
            }
        }
        return count;
    }

    private PermissionDiffPolicy() {
    }

    public enum Outcome {
        /**
         * No-op change (or identical content) — safe to allow without a panel.
         */
        ALLOW_SILENT,
        /**
         * Show the Accept/Reject diff panel.
         */
        SHOW_DIFF,
        /**
         * Cannot build a trustworthy preview — deny the write.
         */
        DENY
    }

    public record Decision(Outcome outcome, String proposedContent, String reason) {

        public static Decision allowSilent(String proposed) {
            return new Decision(Outcome.ALLOW_SILENT, proposed, null);
        }

        public static Decision showDiff(String proposed) {
            return new Decision(Outcome.SHOW_DIFF, proposed, null);
        }

        public static Decision deny(String reason) {
            return new Decision(Outcome.DENY, null, reason);
        }
    }
}
