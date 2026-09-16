package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

/**
 * The wait-then-classify logic behind {@link ProjectActionProvider}'s blocking IDE actions, isolated from {@code
 * ActionProvider}/{@code Lookup} so it can be driven directly against a {@link BlockingActionProgress} in tests.
 */
final class IdeActionWaiter {

    private IdeActionWaiter() {
    }

    static ProjectActionResult await(BlockingActionProgress progress, String label, long startGraceMillis,
                                     long runLimitMillis) {
        boolean started;
        try {
            started = progress.awaitStarted(startGraceMillis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started = false;
        }
        if (!started) {
            return ProjectActionResult.notTracked(notTrackedMessage(label));
        }
        boolean finished;
        try {
            finished = progress.awaitFinished(runLimitMillis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            return ProjectActionResult.stillRunning(
                    label + " is still running after " + runLimitMillis / 1000
                    + "s — check the Output window for results");
        }
        if (!progress.isSuccess()) {
            return ProjectActionResult.failed(label + " reported failure");
        }
        return ProjectActionResult.completed(label + " completed — the build result is not confirmed; check the Output "
                + "window, or use the Maven/Gradle/Ant build tools for a real result.");
    }

    /**
     * Today's fire-and-forget text, with a clarification appended — for when the action's own {@code ActionProvider}
     * never called {@code ActionProgress.started()}, so it does not support tracked completion.
     */
    static String notTrackedMessage(String label) {
        return label + " triggered — check the Output window for results (could not confirm completion; this "
                + "project's build action does not report progress)";
    }

    /**
     * Today's fire-and-forget text, with a clarification appended — for when the request arrived on the EDT, so waiting
     * for completion was never attempted at all rather than attempted and unsupported.
     */
    static String notWaitedMessage(String label) {
        return label + " triggered — check the Output window for results (completion was not awaited because the "
                + "request arrived on the UI thread)";
    }
}
