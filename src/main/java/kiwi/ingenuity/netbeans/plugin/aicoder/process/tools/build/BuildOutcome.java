package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

/**
 * What a build produced: its final status and the text the calling AI receives.
 */
public record BuildOutcome(BuildStatusEnum status, String result) {

    public BuildOutcome {
        if (status == null || !status.isFinished()) {
            throw new IllegalArgumentException("A build outcome needs a finished status, not " + status);
        }
    }

    public static BuildOutcome completed(boolean success, String result) {
        return new BuildOutcome(success ? BuildStatusEnum.SUCCESS : BuildStatusEnum.FAILED, result);
    }

    public static BuildOutcome timedOut(String result) {
        return new BuildOutcome(BuildStatusEnum.TIMED_OUT, result);
    }

    public static BuildOutcome failed(String result) {
        return new BuildOutcome(BuildStatusEnum.FAILED, result);
    }
}
