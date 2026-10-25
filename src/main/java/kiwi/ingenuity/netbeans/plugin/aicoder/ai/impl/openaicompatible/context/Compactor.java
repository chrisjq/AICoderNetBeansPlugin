package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;

/**
 * Prunes old tool output, then Drops the oldest whole turns until the estimate fits in the window minus reserve,
 * keeping at least the keep-recent tail. Until Plan 7, Auto and Summarise resolve to Drop.
 */
public final class Compactor {

    private final Pruner pruner;
    private final ContextBudget budget;

    public Compactor() {
        this(new Pruner(), new ContextBudget(ContextBudgetSettings.defaults()));
    }

    public Compactor(Pruner pruner, ContextBudget budget) {
        this.pruner = pruner == null ? new Pruner() : pruner;
        this.budget = budget == null ? new ContextBudget(ContextBudgetSettings.defaults()) : budget;
    }

    /**
     * @param windowConfirmed automatic compaction runs only when this is true; manual Compact ({@code force}) always
     * uses {@code window} (the assumed window when unconfirmed)
     * @param prune whether to shorten old tool results before dropping turns
     */
    public Result compact(ContextHistory history, TokenEstimator estimator, int window,
                          boolean windowConfirmed, boolean force, boolean prune,
                          CompactionStrategyEnum strategy) {
        ResolvedCompactionEnum resolved = ResolvedCompactionEnum.resolve(strategy);
        if (history == null || estimator == null) {
            return Result.idle(resolved);
        }
        if (resolved == ResolvedCompactionEnum.NONE) {
            return Result.idle(resolved);
        }
        if (!force && !windowConfirmed) {
            return Result.idle(resolved);
        }
        int safeWindow = Math.max(0, window);
        int target = safeWindow - budget.reserve(safeWindow);
        if (history.estimateTokens(estimator) <= target) {
            return Result.idle(resolved);
        }
        int keepTurns = keepRecentTurns(history, estimator, safeWindow);
        int prunedCount = 0;
        if (prune) {
            prunedCount = pruner.prune(history, keepTurns).prunedCount();
            keepTurns = keepRecentTurns(history, estimator, safeWindow);
        }
        int dropped = 0;
        if (history.estimateTokens(estimator) > target) {
            dropped = dropOldest(history, estimator, keepTurns, target);
            if (dropped > 0) {
                insertDropNotice(history, dropped);
            }
        }
        String chat = dropped > 0 ? chatNotice(dropped, windowConfirmed, safeWindow) : null;
        return new Result(resolved, prunedCount > 0 || dropped > 0, prunedCount, dropped, chat);
    }

    private int keepRecentTurns(ContextHistory history, TokenEstimator estimator, int window) {
        int overhead = overheadTokens(history, estimator);
        return turnsCovering(history, estimator, budget.keepRecent(window, overhead));
    }

    static int turnsCovering(ContextHistory history, TokenEstimator estimator, int keepRecentTokens) {
        List<List<ChatMessage>> turns = history.turns();
        if (turns.isEmpty()) {
            return 0;
        }
        int kept = 0;
        int tokens = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            kept++;
            for (ChatMessage message : turns.get(i)) {
                tokens += estimator.estimate(message);
            }
            if (tokens >= keepRecentTokens) {
                break;
            }
        }
        return kept;
    }

    private static int overheadTokens(ContextHistory history, TokenEstimator estimator) {
        int total = 0;
        if (history.pinned() != null) {
            total += estimator.estimate(new ChatMessage(ChatRole.SYSTEM, history.pinned(), List.of(), null));
        }
        if (history.compactionSummary() != null) {
            total += estimator.estimate(new ChatMessage(ChatRole.SYSTEM, history.compactionSummary(), List.of(), null));
        }
        return total;
    }

    private static int dropOldest(ContextHistory history, TokenEstimator estimator, int keepTurns, int target) {
        int dropped = 0;
        while (history.estimateTokens(estimator) > target) {
            if (history.turns().size() <= keepTurns) {
                break;
            }
            history.dropOldestTurns(1);
            dropped++;
        }
        return dropped;
    }

    static void insertDropNotice(ContextHistory history, int dropped) {
        String notice = modelNotice(dropped);
        List<ChatMessage> msgs = history.messages();
        if (!msgs.isEmpty() && msgs.get(0).role() == ChatRole.USER) {
            String existing = msgs.get(0).content();
            msgs.get(0).setContent(existing == null || existing.isBlank() ? notice : notice + "\n\n" + existing);
        }
        else {
            history.prepend(new ChatMessage(ChatRole.USER, notice, List.of(), null));
        }
    }

    static String modelNotice(int dropped) {
        return "[" + dropped + " earlier exchanges were trimmed]";
    }

    static String chatNotice(int dropped, boolean windowConfirmed, int window) {
        String base = "Context trimmed (" + dropped + " earlier exchanges dropped)";
        return windowConfirmed ? base : base + " — window assumed: " + window;
    }

    public record Result(ResolvedCompactionEnum resolved, boolean changed, int prunedCount, int droppedExchanges,
                         String chatNotice) {

        public static Result idle(ResolvedCompactionEnum resolved) {
            return new Result(resolved, false, 0, 0, null);
        }
    }
}
