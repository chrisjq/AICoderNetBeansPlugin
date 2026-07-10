package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

/**
 * Pure decision rules for the Accept/Reject permission path. Kept free of Swing
 * so unit tests can lock the fail-closed behaviour that protects the diff gate.
 */
public final class PermissionDiffPolicy {

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

    private PermissionDiffPolicy() {
    }

    /**
     * @param toolName Edit or Write (other tools should not reach the
     * permission path)
     * @param filePath target path (must be non-blank)
     * @param original current file text, or empty string if the file does not
     * exist yet
     * @param oldString Edit only
     * @param newString Edit only
     * @param writeContent Write only
     */
    public static Decision decide(String toolName, String filePath, String original,
            String oldString, String newString, String writeContent) {
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
                return Decision.deny("Edit old_string was not found in the file — refusing silent apply");
            }
            int idx = orig.indexOf(oldString);
            proposed = orig.substring(0, idx) + newString + orig.substring(idx + oldString.length());
        }
        else {
            return Decision.deny("Unsupported tool for permission diff: " + toolName);
        }
        if (orig.equals(proposed)) {
            return Decision.allowSilent(proposed);
        }
        return Decision.showDiff(proposed);
    }
}
