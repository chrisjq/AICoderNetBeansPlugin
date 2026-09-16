package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

/**
 * Why a build ended as {@link BuildStatusEnum#CANCELLED}, shown alongside the status.
 */
public enum BuildCancelReasonEnum {
    STOPPED_BY_OWNER("stopped by its AI"),
    SESSION_CLOSED("its AI session closed"),
    START_WAIT_EXPIRED("it did not start within the wait limit");

    private final String description;

    BuildCancelReasonEnum(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
