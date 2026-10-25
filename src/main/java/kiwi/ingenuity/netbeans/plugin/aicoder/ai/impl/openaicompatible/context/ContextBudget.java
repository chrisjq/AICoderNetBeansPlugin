package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

/**
 * Window arithmetic: how many tokens to keep back for the reply, how many recent messages to keep, and how large a tool
 * result may be.
 */
public final class ContextBudget {

    private final ContextBudgetSettings settings;

    public ContextBudget(ContextBudgetSettings settings) {
        this.settings = settings;
    }

    /**
     * The tokens kept back for the reply: never more than half the window (spec §8.1), and never negative. A negative
     * window counts as zero.
     */
    public int reserve(int window) {
        int available = Math.max(0, window);
        int reserve = settings.reserveAbsolute() != null
                      ? settings.reserveAbsolute()
                      : (int) Math.min(Math.max(settings.reservePercent() * available, settings.reserveMinimum()),
                                       settings.reserveMaxShare() * available);
        return Math.max(0, Math.min(reserve, available / 2));
    }

    /**
     * The tokens of recent history kept verbatim: at most what is left after the reply reserve and the overhead (spec
     * §8.1), never negative. A negative overhead counts as zero.
     */
    public int keepRecent(int window, int overhead) {
        int available = Math.max(0, window);
        int keep = settings.keepRecentAbsolute() != null
                   ? settings.keepRecentAbsolute()
                   : (int) Math.max(settings.keepRecentPercent() * available, settings.keepRecentMinimum());
        return Math.max(0, Math.min(keep, available - reserve(available) - Math.max(0, overhead)));
    }

    /**
     * The largest tool result in tokens: the tool-result share (spec §8.1: in (0, 100 %]) of what the window leaves
     * after the reserve. A share outside [0, 1] is clamped into it.
     */
    public int toolResultCapTokens(int window) {
        double share = Math.min(1.0, Math.max(0.0, settings.toolResultShare()));
        return (int) (share * (Math.max(0, window) - reserve(window)));
    }

    public int toolResultCapChars(int window, double charsPerToken) {
        return (int) (toolResultCapTokens(window) * charsPerToken);
    }
}
