package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

/**
 * The strategy the compactor runs for one compaction (spec §6.3). {@link CompactionStrategyEnum#SUMMARISE} resolves to
 * {@link #SUMMARISE}. {@link CompactionStrategyEnum#AUTO} depends on the confirmed window, which only the turn knows,
 * so it resolves to {@link #DROP} here; the turn decides Auto itself.
 */
public enum ResolvedCompactionEnum {
    SUMMARISE,
    DROP,
    NONE;

    /**
     * Summarise resolves to Summarise, None stays None, and Auto or Drop (or no setting) resolves to Drop.
     */
    public static ResolvedCompactionEnum resolve(CompactionStrategyEnum strategy) {
        if (strategy == CompactionStrategyEnum.NONE) {
            return NONE;
        }
        if (strategy == CompactionStrategyEnum.SUMMARISE) {
            return SUMMARISE;
        }
        return DROP;
    }
}
