package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;

public record ContextBudgetSettings(double reservePercent, int reserveMinimum, double reserveMaxShare,
                                    Integer reserveAbsolute, double keepRecentPercent, int keepRecentMinimum,
                                    Integer keepRecentAbsolute, double toolResultShare) {

    public static ContextBudgetSettings defaults() {
        return new ContextBudgetSettings(0.15, 1024, 0.5, null, 0.25, 2048, null, 0.25);
    }

    /**
     * The budget a session asks for: each per-session value (whole percents are converted to fractions), falling back
     * to {@link #defaults()} where unset. An absolute size of 0 means the percentage applies.
     */
    public static ContextBudgetSettings from(OpenAiCompatibleSessionSettings settings) {
        OpenAiCompatibleSessionSettings session = settings == null ? new OpenAiCompatibleSessionSettings() : settings;
        int reserveAbsolute = session.effectiveReserveAbsolute();
        int keepRecentAbsolute = session.effectiveKeepRecentAbsolute();
        return new ContextBudgetSettings(
                session.effectiveReservePercent() / 100.0,
                session.effectiveReserveMinimum(),
                session.effectiveReserveMaxSharePercent() / 100.0,
                reserveAbsolute > 0 ? reserveAbsolute : null,
                session.effectiveKeepRecentPercent() / 100.0,
                session.effectiveKeepRecentMinimum(),
                keepRecentAbsolute > 0 ? keepRecentAbsolute : null,
                session.effectiveToolResultSharePercent() / 100.0);
    }
}
