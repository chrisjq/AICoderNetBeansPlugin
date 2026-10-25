package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

/**
 * How the model-facing history is shortened when it no longer fits the window (spec §6.3). The setting; the broker
 * works with the resolved choice. Auto resolves to Summarise or Drop by the confirmed window and the summarise
 * threshold, Summarise resolves to Summarise, and None stays None.
 */
public enum CompactionStrategyEnum {
    AUTO("Auto"),
    SUMMARISE("Summarise"),
    DROP("Drop oldest"),
    NONE("None");

    private final String displayName;

    CompactionStrategyEnum(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Summarise resolves to {@link ResolvedCompactionEnum#SUMMARISE}; Auto resolves to Drop here (the turn decides it).
     */
    public ResolvedCompactionEnum resolve() {
        return ResolvedCompactionEnum.resolve(this);
    }
}
