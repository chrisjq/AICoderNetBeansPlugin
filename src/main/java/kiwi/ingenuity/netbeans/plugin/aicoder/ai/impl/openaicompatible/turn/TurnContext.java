package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.TokenEstimator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.Compactor;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ContextBudget;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ContextBudgetSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ContextHistory;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.Pruner;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ResolvedCompactionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.Summariser;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.TruncationGuard;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerRemedyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.SessionDecisions;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowLadder;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowSources;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.HintState;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.HintState.HintKind;

/**
 * The session's context management around the turn loop (spec §4 items 6-7, §6): the window in force (override, a
 * window learned from an overflow error, else the assumed window), compaction (prune, then Drop) before a request or on
 * demand, the overflow and fixed-overhead checks, and the truncation guard for servers that cut the prompt silently.
 * One instance per session start. Synchronized: the turn thread and a manual Compact never run at the same time, but
 * the manager reads it from either.
 */
public final class TurnContext {

    /**
     * Receives the hint-slot changes (the info bar); may do nothing.
     */
    public interface HintSink {

        void set(HintKind kind, String text, String tooltip);

        void clear(HintKind kind);

        /**
         * The Summary button's state: the current summary (null when there is none) and whether the context was trimmed
         * rather than summarised.
         */
        default void summary(String summaryOrNull, boolean trimmedNotSummarised) {
        }

        HintSink NONE = new HintSink() {
            @Override
            public void set(HintKind kind, String text, String tooltip) {
            }

            @Override
            public void clear(HintKind kind) {
            }
        };
    }

    /**
     * Writes a summary of older messages with the session's summary model ({@link Summariser}, no tools); a failed
     * result makes the compaction fall back to Drop.
     */
    @FunctionalInterface
    public interface SummaryRunner {

        Summariser.Result summarise(List<ChatMessage> messages, String previousSummary, int window, String pinned,
                                    RequestHandle handle);
    }

    /**
     * The session settings that matter here, read at the start of each turn.
     *
     * @param override the context-window override, 0 for auto
     * @param kindName the server kind's enum name (until the poller is wired, the configured server type)
     * @param summariseThreshold Auto summarises (rather than drops) when the confirmed window is at least this
     */
    public record Config(CompactionStrategyEnum strategy, boolean autoCompact, boolean prune, int override,
                         int assumedWindow, int minimumWindowForTools, String kindName, String model, String url,
                         int summariseThreshold) {

        public Config(CompactionStrategyEnum strategy, boolean autoCompact, boolean prune, int override,
                      int assumedWindow, int minimumWindowForTools, String kindName, String model, String url) {
            this(strategy, autoCompact, prune, override, assumedWindow, minimumWindowForTools, kindName, model, url,
                 DEFAULT_SUMMARISE_THRESHOLD);
        }

        public static Config defaults() {
            return new Config(CompactionStrategyEnum.AUTO, true, true, 0, 8192, 6144, "GENERIC", "", "");
        }
    }

    /**
     * The default summarise threshold (spec §8.2).
     */
    public static final int DEFAULT_SUMMARISE_THRESHOLD = 16384;
    /**
     * The lead-in of the one user message that carries the summary to the model.
     */
    public static final String SUMMARY_PREFIX = "Summary of the earlier conversation:\n";
    private static final String STATE_PRUNE_NOTICE_SHOWN = "pruneNoticeShown";
    private static final String STATE_PRUNED_COUNT = "prunedCount";

    static String summarisedNotice(int messages) {
        return "Context compacted (" + messages + " messages summarised)";
    }

    /**
     * What a compaction did: whether the history changed, and the chat notices to post (prune notice, "Context
     * trimmed…").
     */
    public record Compaction(boolean changed, List<String> notices) {

        static Compaction none() {
            return new Compaction(false, List.of());
        }
    }

    /**
     * What a usage report asks for: compact before the next request, and an optional chat message.
     */
    public record UsageVerdict(boolean compactNext, String message) {

        static final UsageVerdict NOTHING = new UsageVerdict(false, null);
    }

    static final String PRUNE_NOTICE = "Older tool output is being shortened in the model's context to save space; "
            + "your chat keeps everything.";
    static final String NONE_OVERFLOW_MESSAGE
            = "Context exceeded the server limit; enable compaction or clear context";
    static final String CUTTING_HINT = "Server cuts the prompt";

    private final TokenEstimator estimator;
    /**
     * The session's budget, pruner and compactor; replaced when the session's settings change.
     */
    private ContextBudget budget = new ContextBudget(ContextBudgetSettings.defaults());
    private Pruner pruner = new Pruner();
    private Compactor compactor = new Compactor(pruner, budget);
    private final TruncationGuard guard = new TruncationGuard(OpenAiCompatiblePluginSettings.getCalibrationTolerance());
    private final SessionDecisions decisions;
    private final HintSink hints;
    private Supplier<WindowSources> windowSources;
    private final WindowLadder ladder = new WindowLadder(OpenAiCompatiblePluginSettings.getWindowReadingsBeforeShrink());
    private String ladderModel;
    private String ladderUrl;

    private Config config = Config.defaults();
    private SummaryRunner summaryRunner;
    private String summary;
    /**
     * The history message that carries {@link #summary}; recognised by identity so a later summary replaces it.
     */
    private ChatMessage summaryMessage;
    private boolean pruneNoticeShown;
    /**
     * How many tool results have been pruned so far in this session.
     */
    private int prunedTotal;
    private Integer previousReported;
    private int previousEstimate;

    public TurnContext(TokenEstimator estimator, SessionDecisions decisions, HintSink hints) {
        this.estimator = estimator == null ? new TokenEstimator() : estimator;
        this.decisions = decisions == null ? new SessionDecisions("") : decisions;
        this.hints = hints == null ? HintSink.NONE : hints;
    }

    /**
     * Takes this turn's settings; the truncation guard is lifted when the override, model or URL changed.
     */
    public synchronized void setWindowSources(Supplier<WindowSources> sources) {
        windowSources = sources;
    }

    /**
     * Takes the session's reserve, keep-recent, result-share and prune settings (spec §8.2); unset values fall back to
     * the defaults. Call at the start of a turn and when the session's settings change.
     */
    public synchronized void applySettings(OpenAiCompatibleSessionSettings settings) {
        budget = new ContextBudget(ContextBudgetSettings.from(settings));
        pruner = Pruner.from(settings);
        compactor = new Compactor(pruner, budget);
    }

    public synchronized void beginTurn(Config next) {
        beginTurn(next, null);
    }

    /**
     * As {@link #beginTurn(Config)}, with the summary model for this turn; null means Summarise is unavailable (Drop).
     */
    public synchronized void beginTurn(Config next, SummaryRunner runner) {
        summaryRunner = runner;
        config = next == null ? Config.defaults() : next;
        guard.beginTurn();
        boolean wasPaused = guard.isPaused();
        guard.resetOn(String.valueOf(config.override()), config.model(), config.url());
        recordWindowReading();
        if (wasPaused && !guard.isPaused()) {
            hints.clear(HintKind.SERVER_CUTTING_PROMPT);
        }
    }

    public synchronized Config config() {
        return config;
    }

    /**
     * The window in force: override, else a window learned from an overflow error, else the assumed window
     * (unconfirmed). Detection readings join here when the poller is wired.
     */
    public synchronized WindowReading window() {
        return window(config);
    }

    /**
     * Resolves the window for supplied settings without changing this context's active turn.
     */
    public synchronized WindowReading window(Config settings) {
        Config effective = settings == null ? config : settings;
        WindowReading reading = rawWindow(effective);
        Integer held = ladder.current();
        if (held != null && reading.confirmed() && ladderTracks(reading) && reading.tokens() < held
                && Objects.equals(ladderModel, effective.model())) {
            return new WindowReading(held, reading.source(), true);
        }
        return reading;
    }

    private WindowReading rawWindow(Config effective) {
        Integer override = effective.override() > 0 ? effective.override() : null;
        Supplier<WindowSources> supplier = windowSources;
        WindowSources sources = supplier == null ? null : supplier.get();
        return WindowLadder.resolve(override, sources, decisions.learnedWindow(), effective.assumedWindow(),
                                    OpenAiCompatiblePluginSettings.getTrainingLengthFactor());
    }

    /**
     * Whether a reading goes through the two-readings shrink rule: a detected one. An override is the user's word and a
     * window learned from an overflow error is a hard fact, so both take effect at once.
     */
    private static boolean ladderTracks(WindowReading reading) {
        return reading.source() != WindowReading.Source.OVERRIDE && reading.source() != WindowReading.Source.SERVER_ERROR;
    }

    /**
     * Counts this turn's detected window as one reading for the shrink rule (spec §3.6): a smaller value takes effect
     * only after the configured number of consecutive smaller readings for the same model.
     */
    private void recordWindowReading() {
        if (!Objects.equals(ladderUrl, config.url())) {
            resetWindowReadings();
            ladderUrl = config.url();
        }
        WindowReading raw = rawWindow(config);
        if (raw.source() == WindowReading.Source.OVERRIDE) {
            resetWindowReadings();
        }
        else if (raw.confirmed() && ladderTracks(raw)) {
            ladder.record(config.model(), raw.tokens());
            ladderModel = config.model();
        }
    }

    /**
     * Forgets the remembered window readings (spec §3.7: a model or URL change).
     */
    public synchronized void resetWindowReadings() {
        ladder.reset();
        ladderModel = null;
    }

    public synchronized int reserve(int window) {
        return budget.reserve(window);
    }

    public synchronized int estimate(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimator.estimate(message);
        }
        return total;
    }

    /**
     * The fixed overhead of every request: the system prompt and the tool definitions.
     */
    public synchronized int overheadTokens(String systemPrompt, String toolsJson) {
        String text = (systemPrompt == null ? "" : systemPrompt) + (toolsJson == null ? "" : toolsJson);
        return text.isEmpty() ? 0 : estimator.estimate(new ChatMessage(ChatRole.SYSTEM, text, List.of(), null));
    }

    /**
     * Checks the fixed overhead against the window before a turn sends anything (spec §4 item 7). Only a confirmed
     * window is held to this: an assumed one would refuse every turn of a large hosted model nothing was detected for.
     *
     * @param overhead the system prompt and the tool definitions, in tokens
     * @param toolTokens the tool definitions alone, in tokens (the tools-share hint and the "too big" text)
     *
     * @return the CONTEXT_OVERFLOW message, or null when the overhead fits
     */
    public synchronized String overheadProblem(int overhead, int toolTokens, boolean toolsOffered) {
        WindowReading window = window();
        if (!window.confirmed()) {
            return null;
        }
        int w = window.tokens();
        if (overhead <= w - budget.reserve(w)) {
            hints.clear(HintKind.TOOLS_DO_NOT_FIT);
            if (HintState.toolsShareApplies(toolTokens, w)) {
                hints.set(HintKind.TOOLS_SHARE, HintState.toolsShareText(toolTokens, w),
                          HintState.toolsShareTooltip(toolTokens, w));
            }
            else {
                hints.clear(HintKind.TOOLS_SHARE);
            }
            return null;
        }
        hints.clear(HintKind.TOOLS_SHARE);
        String message;
        String hint;
        if (toolsOffered && w < config.minimumWindowForTools()) {
            message = "Context window " + w + " is too small for tools — raise it to at least "
                    + config.assumedWindow() + " (recommended; " + config.minimumWindowForTools()
                    + " is the minimum). " + capitalised(remedy()) + ".";
            hint = HintState.windowTooSmallForToolsText(w);
        }
        else {
            message = "Tool definitions alone need ~" + overhead + " tokens of your " + w + "-token window. Apply the "
                    + "'Minimal' MCP template (session settings → MCP Tools → Apply MCP template…), switch off tool "
                    + "groups, or raise the context window.";
            hint = HintState.toolsTooBigText(toolTokens, w);
        }
        hints.set(HintKind.TOOLS_DO_NOT_FIT, hint, message);
        return message;
    }

    /**
     * Prunes and Drops so the history fits the window (spec §6.2-§6.3). Automatic compaction ({@code force} false) runs
     * only when auto-compact is on, the window is confirmed and the truncation guard has not paused it; manual Compact
     * ({@code force}) always runs, on the assumed window when unconfirmed.
     *
     * @param history the model-facing history; replaced in place when anything changed
     */
    public synchronized Compaction compact(List<ChatMessage> history, String pinned, boolean force) {
        return compact(history, pinned, force, null);
    }

    /**
     * As {@link #compact(List, String, boolean)}; {@code handle} cancels a summary request in flight.
     */
    public synchronized Compaction compact(List<ChatMessage> history, String pinned, boolean force,
                                           RequestHandle handle) {
        if (compactionOff()) {
            return Compaction.none();
        }
        if (!force && (!config.autoCompact() || guard.isPaused())) {
            return Compaction.none();
        }
        WindowReading window = window();
        if (!force && !window.confirmed()) {
            return Compaction.none();
        }
        ContextHistory context = contextOf(history, pinned);
        if (resolve(window) == ResolvedCompactionEnum.SUMMARISE) {
            return summarise(history, context, pinned, window, handle);
        }
        return drop(history, context, window, force, config.prune(), new ArrayList<>());
    }

    /**
     * The strategy for this compaction (spec §6.3): Auto summarises only against a confirmed window at or above the
     * summarise threshold; Summarise needs a summary model for this turn; everything else drops.
     */
    synchronized ResolvedCompactionEnum resolve(WindowReading window) {
        CompactionStrategyEnum strategy = config.strategy();
        if (strategy == CompactionStrategyEnum.NONE) {
            return ResolvedCompactionEnum.NONE;
        }
        if (summaryRunner == null) {
            return ResolvedCompactionEnum.DROP;
        }
        if (strategy == CompactionStrategyEnum.SUMMARISE) {
            return ResolvedCompactionEnum.SUMMARISE;
        }
        if (strategy == CompactionStrategyEnum.AUTO && window.confirmed()
                && window.tokens() >= config.summariseThreshold()) {
            return ResolvedCompactionEnum.SUMMARISE;
        }
        return ResolvedCompactionEnum.DROP;
    }

    private ContextHistory contextOf(List<ChatMessage> history, String pinned) {
        ContextHistory context = new ContextHistory(pinned == null || pinned.isBlank() ? null : pinned);
        history.forEach(context::append);
        context.setCompactionSummary(null);
        return context;
    }

    private Compaction drop(List<ChatMessage> history, ContextHistory context, WindowReading window, boolean force,
                            boolean prune, List<String> notices) {
        Compactor.Result result = compactor.compact(context, estimator, window.tokens(), window.confirmed(), force,
                                                    prune, CompactionStrategyEnum.DROP);
        prunedTotal += result.prunedCount();
        if (!result.changed() && notices.isEmpty()) {
            return Compaction.none();
        }
        history.clear();
        history.addAll(context.messages());
        if (result.prunedCount() > 0) {
            notePruned(notices);
        }
        if (result.chatNotice() != null) {
            notices.add(result.chatNotice());
        }
        if (result.droppedExchanges() > 0) {
            if (summaryMessage != null && !history.contains(summaryMessage)) {
                summaryMessage = null;
                summary = null;
            }
            hints.summary(summary, summary == null);
        }
        return new Compaction(true, List.copyOf(notices));
    }

    private void notePruned(List<String> notices) {
        if (!pruneNoticeShown) {
            pruneNoticeShown = true;
            notices.add(PRUNE_NOTICE);
        }
    }

    /**
     * Prune first; if still over, replace every turn older than the keep-recent tail with ONE user message carrying the
     * summary (update mode when there is a previous one). A failed summary falls back to Drop.
     */
    private Compaction summarise(List<ChatMessage> history, ContextHistory context, String pinned,
                                 WindowReading window, RequestHandle handle) {
        int w = window.tokens();
        int target = w - budget.reserve(w);
        if (context.estimateTokens(estimator) <= target) {
            return Compaction.none();
        }
        List<String> notices = new ArrayList<>();
        int keepTurns = keepRecentTurns(context, pinned, w);
        int pruned = config.prune() ? pruner.prune(context, keepTurns).prunedCount() : 0;
        prunedTotal += pruned;
        if (pruned > 0) {
            notePruned(notices);
            if (context.estimateTokens(estimator) <= target) {
                history.clear();
                history.addAll(context.messages());
                return new Compaction(true, List.copyOf(notices));
            }
        }
        List<List<ChatMessage>> turns = context.turns();
        int older = turns.size() - keepTurns;
        List<ChatMessage> span = new ArrayList<>();
        for (int i = 0; i < older; i++) {
            span.addAll(turns.get(i));
        }
        span.removeIf(message -> message == summaryMessage);
        Summariser.Result result = span.isEmpty() ? null
                                   : summaryRunner.summarise(List.copyOf(span), summary, w, pinned, handle);
        if (result == null || !result.success() || result.summary() == null || result.summary().isBlank()) {
            String reason = result == null || result.failureReason() == null || result.failureReason().isBlank()
                            ? "summary request failed" : result.failureReason();
            notices.add("Couldn't summarise (" + reason + "); dropped " + older
                    + " older exchanges instead.");
            return drop(history, context, window, true, false, notices);
        }
        List<ChatMessage> kept = new ArrayList<>();
        for (int i = Math.max(0, older); i < turns.size(); i++) {
            kept.addAll(turns.get(i));
        }
        summary = result.summary();
        summaryMessage = new ChatMessage(ChatRole.USER, SUMMARY_PREFIX + summary, List.of(), null);
        history.clear();
        history.add(summaryMessage);
        history.addAll(kept);
        notices.add(summarisedNotice(span.size()));
        hints.summary(summary, false);
        return new Compaction(true, List.copyOf(notices));
    }

    /**
     * How many of the newest turns the keep-recent budget covers (at least one, the current turn).
     */
    private int keepRecentTurns(ContextHistory context, String pinned, int window) {
        int overhead = pinned == null || pinned.isBlank() ? 0
                       : estimator.estimate(new ChatMessage(ChatRole.SYSTEM, pinned, List.of(), null));
        int keepTokens = budget.keepRecent(window, overhead);
        List<List<ChatMessage>> turns = context.turns();
        int kept = 0;
        int tokens = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            kept++;
            for (ChatMessage message : turns.get(i)) {
                tokens += estimator.estimate(message);
            }
            if (tokens >= keepTokens) {
                break;
            }
        }
        return kept;
    }

    /**
     * The current summary, or null when nothing has been summarised (or the summary was dropped since).
     */
    public synchronized String summary() {
        return summary;
    }

    /**
     * Clears the compaction summary so the model is reset to start-of-session state.
     */
    public synchronized void clearSummary() {
        summary = null;
        summaryMessage = null;
        hints.summary(null, false);
    }

    /**
     * Clears prune bookkeeping (counts and notice flags).
     */
    public synchronized void clearPruneState() {
        prunedTotal = 0;
        pruneNoticeShown = false;
    }

    /**
     * Tool results pruned so far in this session.
     */
    public synchronized int prunedCount() {
        return prunedTotal;
    }

    /**
     * The compaction the given settings resolve to against the window as it is now (what Auto would do right now), for
     * display; null means the settings of the current turn.
     */
    public synchronized ResolvedCompactionEnum resolvedStrategy(Config settings) {
        Config effective = settings == null ? config : settings;
        CompactionStrategyEnum strategy = effective.strategy();
        if (strategy == CompactionStrategyEnum.NONE) {
            return ResolvedCompactionEnum.NONE;
        }
        if (strategy == CompactionStrategyEnum.SUMMARISE) {
            return ResolvedCompactionEnum.SUMMARISE;
        }
        WindowReading window = window(effective);
        if (strategy == CompactionStrategyEnum.AUTO && window.confirmed()
                && window.tokens() >= effective.summariseThreshold()) {
            return ResolvedCompactionEnum.SUMMARISE;
        }
        return ResolvedCompactionEnum.DROP;
    }

    /**
     * Everything that must survive closing the session (spec §6.6): the model-facing history with its prune
     * placeholders, the summary and the estimator's calibration ratio, plus the prune-notice flag and the pruned count.
     */
    public synchronized JsonObject exportState(List<ChatMessage> history) {
        ContextHistory context = new ContextHistory();
        history.forEach(context::append);
        context.setCompactionSummary(summary);
        JsonObject json = context.toJson(estimator);
        json.addProperty(STATE_PRUNE_NOTICE_SHOWN, pruneNoticeShown);
        json.addProperty(STATE_PRUNED_COUNT, prunedTotal);
        return json;
    }

    /**
     * Restores a state saved by {@link #exportState}: the summary, the calibration ratio, the prune bookkeeping, and
     * the Summary button's state. Corrupt or partial input restores what it can.
     *
     * @return the restored model-facing history
     */
    public synchronized List<ChatMessage> importState(JsonObject json) {
        ContextHistory restored = ContextHistory.fromJson(json, estimator);
        List<ChatMessage> messages = new ArrayList<>(restored.messages());
        summary = restored.compactionSummary();
        summaryMessage = null;
        if (summary != null) {
            for (ChatMessage message : messages) {
                if (message.role() == ChatRole.USER && message.content() != null
                        && message.content().startsWith(SUMMARY_PREFIX)) {
                    summaryMessage = message;
                    break;
                }
            }
        }
        pruneNoticeShown = json != null && json.has(STATE_PRUNE_NOTICE_SHOWN)
                && json.get(STATE_PRUNE_NOTICE_SHOWN).isJsonPrimitive()
                && json.get(STATE_PRUNE_NOTICE_SHOWN).getAsBoolean();
        prunedTotal = json != null && json.has(STATE_PRUNED_COUNT) && json.get(STATE_PRUNED_COUNT).isJsonPrimitive()
                      ? Math.max(0, json.get(STATE_PRUNED_COUNT).getAsInt()) : 0;
        hints.summary(summary, false);
        return messages;
    }

    /**
     * A fresh model-facing history (after the switch to schema mode): empty, or the one summary message when there is a
     * summary, so the earlier conversation is not lost to the model entirely.
     */
    public synchronized List<ChatMessage> freshHistory() {
        List<ChatMessage> fresh = new ArrayList<>();
        if (summary != null) {
            summaryMessage = new ChatMessage(ChatRole.USER, SUMMARY_PREFIX + summary, List.of(), null);
            fresh.add(summaryMessage);
        }
        return fresh;
    }

    /**
     * Records the window a server stated in an overflow error as a session decision (the learned window).
     */
    public synchronized void learnWindow(Integer statedLimit) {
        if (statedLimit != null && statedLimit > 0) {
            decisions.learnedWindow(statedLimit);
        }
    }

    public synchronized boolean compactionOff() {
        return ResolvedCompactionEnum.resolve(config.strategy()) == ResolvedCompactionEnum.NONE;
    }

    /**
     * A reported {@code prompt_tokens}: calibrates the estimator, or — on the servers that truncate silently (OLLAMA,
     * LMSTUDIO) — lets the truncation guard decide (spec §6.1). On other servers a report above the window means the
     * history no longer fits: compact before the next request.
     *
     * @param estimate what was estimated for the request the report belongs to
     */
    public synchronized UsageVerdict onUsage(Integer reported, int estimate) {
        if (reported == null || reported <= 0) {
            return UsageVerdict.NOTHING;
        }
        WindowReading window = window();
        UsageVerdict verdict = UsageVerdict.NOTHING;
        int compactAt = Math.max(0, window.tokens() - budget.reserve(window.tokens()));
        boolean thresholdReached = reported >= compactAt;
        if (truncatesSilently()) {
            TruncationGuard.Decision decision = guard.onReport(new TruncationGuard.Report(
                    window.tokens(), window.source() == WindowReading.Source.OVERRIDE, reported, previousReported,
                    estimate, previousEstimate, remedy()));
            switch (decision.action()) {
                case CALIBRATE -> {
                    estimator.calibrate(estimate, reported);
                    if (thresholdReached) {
                        verdict = new UsageVerdict(true, null);
                    }
                }
                case COMPACT_ONCE ->
                    verdict = new UsageVerdict(true, null);
                case PAUSE_WITH_MESSAGE -> {
                    hints.set(HintKind.SERVER_CUTTING_PROMPT, CUTTING_HINT, decision.message());
                    verdict = new UsageVerdict(false, decision.message());
                }
                default -> {
                    if (thresholdReached) {
                        verdict = new UsageVerdict(true, null);
                    }
                }
            }
        }
        else {
            estimator.calibrate(estimate, reported);
            if (reported > window.tokens()) {
                verdict = new UsageVerdict(true, null);
            }
            else if (thresholdReached && !verdict.compactNext()) {
                verdict = new UsageVerdict(true, null);
            }
        }
        previousReported = reported;
        previousEstimate = estimate;
        return verdict;
    }

    public synchronized boolean truncationPaused() {
        return guard.isPaused();
    }

    private boolean truncatesSilently() {
        String kind = normalisedKind();
        return "OLLAMA".equals(kind) || "LMSTUDIO".equals(kind);
    }

    private String normalisedKind() {
        String kind = config.kindName();
        return kind == null ? "" : kind.trim().replace("_", "").toUpperCase(Locale.ROOT);
    }

    String remedy() {
        return ServerRemedyEnum.forKind(config.kindName()).remedy();
    }

    private static String capitalised(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
