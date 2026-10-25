package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import java.util.Objects;

/**
 * Notices a server that silently cuts the prompt: a usage report that sits at the window, or that shrank while the
 * estimate grew, is not trusted for calibration. Compacts at most once per turn; on recurrence it pauses automatic
 * compaction and tells the user.
 */
public final class TruncationGuard {

    public enum Action {
        CALIBRATE,
        IGNORE_CALIBRATION,
        COMPACT_ONCE,
        PAUSE_WITH_MESSAGE
    }

    public record Decision(Action action, String message) {

        static Decision of(Action action) {
            return new Decision(action, null);
        }
    }

    public record Report(int window, boolean windowIsOverride, int reportedPrompt, Integer previousReported,
                         int estimate, int previousEstimate, String remedy) {

    }

    private final double tolerance;
    private String identity;
    private boolean paused;
    private boolean suspicionSeen;
    private boolean compactedThisTurn;

    public TruncationGuard(double tolerance) {
        this.tolerance = tolerance;
    }

    public void beginTurn() {
        compactedThisTurn = false;
    }

    public boolean isPaused() {
        return paused;
    }

    public void resetOn(String override, String model, String url) {
        String next = Objects.toString(override, "") + "|" + Objects.toString(model, "") + "|" + Objects.toString(url, "");
        if (next.equals(identity)) {
            return;
        }
        identity = next;
        paused = false;
        suspicionSeen = false;
        compactedThisTurn = false;
    }

    public Decision onReport(Report report) {
        if (!isSuspicious(report)) {
            return Decision.of(Action.CALIBRATE);
        }
        if (paused || compactedThisTurn) {
            return Decision.of(Action.IGNORE_CALIBRATION);
        }
        compactedThisTurn = true;
        if (!suspicionSeen) {
            suspicionSeen = true;
            return Decision.of(Action.COMPACT_ONCE);
        }
        paused = true;
        return new Decision(Action.PAUSE_WITH_MESSAGE, message(report));
    }

    private boolean isSuspicious(Report report) {
        boolean nearWindow = Math.abs(report.reportedPrompt() - report.window()) <= report.window() * tolerance;
        boolean shrankWhileGrowing = report.previousReported() != null
                && report.reportedPrompt() < report.previousReported()
                && report.estimate() > report.previousEstimate();
        return nearWindow || shrankWhileGrowing;
    }

    private static String message(Report report) {
        int reported = report.reportedPrompt();
        int window = report.window();
        String cause = report.windowIsOverride()
                       ? "your context-window override (" + window + ") is probably larger than the server's setting"
                       : "less than the detected window (" + window + ")";
        return "The server seems to cut the prompt at about " + reported + " tokens — " + cause
                + ". Lower the override to " + reported + " or less, or raise the server's context length ("
                + report.remedy() + "). Automatic compaction is paused until you change the override or the model.";
    }
}
