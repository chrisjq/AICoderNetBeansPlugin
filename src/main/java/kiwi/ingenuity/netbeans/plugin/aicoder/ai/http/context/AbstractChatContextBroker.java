package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;

/**
 * Owns the model-facing message history for one session.
 *
 * Thread-safe: a single ReentrantLock guards all state, and the lock is never held across I/O. Callers never
 * need external locking.
 */
public abstract class AbstractChatContextBroker {

    private static final int UNKNOWN_CONTEXT_TRIM_THRESHOLD = 12000;
    private static final String RESTORED_CONTEXT_WARNING = "This conversation was resumed from a previous session. Tool results may be out of date; re-check factual information with a fresh tool call.";

    // Bump whenever a ContextJsonKeyEnum value is renamed or removed, and give
    // the old spelling a migration path. The gate below only fires on THIS
    // number; an unbumped rename sails through it and restoreFromJson then
    // silently skips every entry whose keys it can no longer find.
    // ContextSnapshotFormatTest pins the version-1 spellings.
    private static final int FORMAT_VERSION = 1;

    /**
     * A file truncated by a crash mid-write can contain an assistant tool_calls message with no matching TOOL
     * results, or the reverse. Either produces a payload the endpoint rejects with HTTP 400, so the whole
     * group goes.
     */
    private static List<ContextEntry> dropIncompleteGroups(List<ContextEntry> loaded) {
        Map<Long, List<ContextEntry>> byGroup = new LinkedHashMap<>();
        for (ContextEntry e : loaded) {
            byGroup.computeIfAbsent(e.groupId(), g -> new ArrayList<>()).add(e);
        }
        List<ContextEntry> out = new ArrayList<>();
        for (List<ContextEntry> group : byGroup.values()) {
            int expectedResults = 0;
            Set<String> callIds = new LinkedHashSet<>();
            Set<String> resultIds = new LinkedHashSet<>();
            for (ContextEntry e : group) {
                if (e.message().role() == ChatRole.ASSISTANT
                        && !e.message().toolCalls().isEmpty()) {
                    for (ChatToolCall c : e.message().toolCalls()) {
                        callIds.add(c.id());
                        expectedResults++;
                    }
                }
                if (e.message().role() == ChatRole.TOOL) {
                    resultIds.add(e.message().toolCallId());
                }
            }
            if (expectedResults > 0 && !resultIds.containsAll(callIds)) {
                continue;
            }
            if (!callIds.containsAll(resultIds)) {
                continue;
            }
            out.addAll(group);
        }
        return out;
    }

    protected final ReentrantLock lock = new ReentrantLock();
    protected ContextBrokerSettings settings;
    protected final TokenEstimator estimator = new TokenEstimator();
    protected final ContextDebugLog debugLog;

    private final String sessionId;
    private final Map<PinSlotEnum, String> pins = new EnumMap<>(PinSlotEnum.class);
    private final List<ContextEntry> entries = new ArrayList<>();

    long sequenceCounter = 0L;
    long groupCounter = 0L;
    private long generation = 0L;
    private boolean inTurn = false;
    private long currentGroupId = -1L;
    // Native tool calling (Ollama): when true, appendAssistant folds two assistant messages as usual but drops
    // the prose content if the fold would carry BOTH content AND tool_calls — the banned shape for Mistral
    // templates. Set once per turn alongside the schema/native decision.
    private volatile boolean nativeToolCalling = false;

    private int totalGroupsTrimmed = 0;
    private volatile int lastReportedPromptTokens = 0;
    private ContextEntry trimMarker = null;
    private long sequenceForMarker = 0L;
    private volatile ContextSummariser summariser;
    private volatile boolean summarising = false;
    private volatile boolean pinnedOverBudget = false;
    private ContextEntry summaryEntry = null;
    // Set only by restoreFromJson. Everything restored from disk describes the project as it was in an
    // earlier run: a devstral session reopened after a rebuild answered "1.4.55" from a restored tool
    // result when the installed version was 1.4.57, without re-reading anything. Held in a FIELD rather
    // than appended to entries, like the trim and summary markers, so it can never be serialised by
    // toJson and restored again — otherwise every open/close cycle would add one more copy.
    private ContextEntry restoredContextWarning = null;

    protected AbstractChatContextBroker(String sessionId, ContextBrokerSettings settings) {
        this.sessionId = sessionId;
        this.settings = settings == null ? ContextBrokerSettings.defaults() : settings;
        this.debugLog = new ContextDebugLog(sessionId);
    }

    /**
     * Per-provider hook: the model's usable context window, when discoverable.
     */
    protected abstract int contextLimit();

    /**
     * Effective high/low-water token threshold. Providers may replace the configured fallback with a
     * discovered runtime window; zero means use the configured value.
     */
    protected int trimThreshold() {
        return settings.tokenThreshold() > 0
                ? settings.tokenThreshold() : UNKNOWN_CONTEXT_TRIM_THRESHOLD;
    }

    /**
     * The known model window for gauges and diagnostics, or zero while unknown.
     */
    public final int contextLimitForDisplay() {
        return Math.max(0, contextLimit());
    }

    /**
     * Per-provider hook. Overridable so a backend can supply a better estimate.
     */
    protected int estimateTokens(ChatMessage message) {
        return estimator.estimate(message);
    }

    public final void upsertPin(PinSlotEnum slot, String text) {
        lock.lock();
        try {
            String existing = pins.get(slot);
            String incoming = text == null ? "" : text;
            if (incoming.equals(existing == null ? "" : existing)) {
                return;
            }
            pins.put(slot, incoming);
            generation++;
            debugLog.event(existing == null ? "PIN" : "REPIN",
                    slot + " " + ContextDebugLog.truncate(incoming));
        } finally {
            lock.unlock();
        }
    }

    public final void append(ChatMessage message) {
        lock.lock();
        try {
            long group = inTurn ? currentGroupId : ++groupCounter;
            ContextEntry entry = new ContextEntry(++sequenceCounter, group,
                    System.currentTimeMillis(), message, ContextRetentionEnum.EVICTABLE,
                    estimateTokens(message), null);
            entries.add(entry);
            generation++;
            debugLog.event("APPEND", "seq=" + entry.sequence() + " group=" + group
                    + " role=" + message.role() + " tokens=" + entry.estimatedTokens()
                    + " content=" + ContextDebugLog.truncate(message.content()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends an ASSISTANT message, folding it into the previously appended ASSISTANT when that entry belongs
     * to the same, still-open turn instead of adding a second one. Ollama's Mistral-family chat templates
     * enforce strict USER/ASSISTANT alternation: a narration round (text, no tool call) immediately followed
     * by a tool-calling round produces two consecutive ASSISTANT messages that break the template contract
     * and make the model stop calling tools mid-session. Merging keeps one ASSISTANT per turn segment.
     * <p>
     * The merge is deliberately narrow. The group check ({@code inTurn} and same {@code currentGroupId})
     * stops it ever crossing a committed turn boundary; the role check stops it ever absorbing a USER message
     * or a TOOL result. Only an adjacent ASSISTANT whose tool results have not yet been emitted can take the
     * new one. The content is joined with a blank line when both sides are non-blank (a blank side keeps the
     * other; two blank sides produce a blank message) and the tool-call lists are unioned by id so neither
     * side's calls are lost.
     */
    public final void appendAssistant(ChatMessage message) {
        if (message.role() != ChatRole.ASSISTANT) {
            append(message);
            return;
        }
        lock.lock();
        try {
            List<ContextEntry> list = mutableEntries();
            if (!list.isEmpty()) {
                ContextEntry previous = list.get(list.size() - 1);
                // Native tool calling: an assistant message must be text OR tool calls, never both. Mistral
                // declares this upstream — TemplateConfig.forbids_assistant_content_with_tools
                // (https://mistralai.github.io/mistral-common/code_reference/mistral_common/integrations/chat_templates/template_generator/)
                // — and devstral's template enforces it with `if .Content ... else if .ToolCalls`: content
                // wins and the call is silently dropped from the prompt, which is exactly the stop-calling
                // bug we saw. Corroborating reports: ollama#9628, continuedev#9249. The fold STILL happens
                // (FIX 1), but when it would produce content AND tool_calls the CONTENT gives way — it was
                // already streamed to the user — never the call, and never a split into a separate preceding
                // assistant message (that breaks the template's strict role alternation and fails as badly).
                if (inTurn && previous.groupId() == currentGroupId
                        && previous.message().role() == ChatRole.ASSISTANT) {
                    ChatMessage merged = mergeAssistantMessages(previous.message(), message);
                    if (nativeToolCalling && mergeCarriesContentAndToolCalls(previous.message(), message)) {
                        merged = new ChatMessage(ChatRole.ASSISTANT, null, merged.toolCalls(), null);
                    }
                    previous.setMessage(merged);
                    previous.setEstimatedTokens(estimateTokens(merged));
                    bumpGeneration();
                    debugLog.event("MERGE_ASSISTANT", "seq=" + previous.sequence()
                            + " content=" + ContextDebugLog.truncate(merged.content())
                            + " toolCalls=" + merged.toolCalls().size());
                    return;
                }
            }
            append(message);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Selects whether this broker's history is served in native tool-calling mode (Ollama: tool calls as
     * structured {@code tool_calls}, no schema). Call once per turn, at the same point the manager decides
     * the protocol. This only flips the native merge rule above; it never touches the entries list.
     */
    public final void setNativeToolCalling(boolean nativeToolCalling) {
        this.nativeToolCalling = nativeToolCalling;
    }

    /**
     * True when an {@link #appendAssistant} merge would leave a single assistant message carrying BOTH
     * non-blank prose content AND tool_calls. Decided on exactly what
     * {@link #mergeAssistantMessages(ChatMessage, ChatMessage)} would produce, so the drop is precise. In
     * native mode such a message is the banned shape (Mistral
     * TemplateConfig.forbids_assistant_content_with_tools), and {@code appendAssistant} keeps the merge and
     * blanks the content instead.
     */
    private static boolean mergeCarriesContentAndToolCalls(ChatMessage prev, ChatMessage next) {
        if (prev.toolCalls().isEmpty() && next.toolCalls().isEmpty()) {
            return false;
        }
        String content = joinAssistantContents(prev.content(), next.content());
        return content != null && !content.isBlank();
    }

    private static ChatMessage mergeAssistantMessages(ChatMessage prev, ChatMessage next) {
        String content = joinAssistantContents(prev.content(), next.content());
        List<ChatToolCall> calls = new ArrayList<>(prev.toolCalls());
        for (ChatToolCall call : next.toolCalls()) {
            if (calls.stream().noneMatch(c -> c.id().equals(call.id()))) {
                calls.add(call);
            }
        }
        return new ChatMessage(ChatRole.ASSISTANT, content, calls, null);
    }

    private static String joinAssistantContents(String prev, String next) {
        boolean prevBlank = prev == null || prev.isBlank();
        boolean nextBlank = next == null || next.isBlank();
        if (prevBlank && nextBlank) {
            return null;
        }
        if (prevBlank) {
            return next;
        }
        if (nextBlank) {
            return prev;
        }
        return prev + "\n\n" + next;
    }

    public final List<ChatMessage> snapshot() {
        lock.lock();
        try {
            List<ChatMessage> out = new ArrayList<>();
            String pinned = renderPins();
            if (!pinned.isEmpty()) {
                out.add(new ChatMessage(ChatRole.SYSTEM, pinned, List.of(), null));
            }
            // Ahead of the summary and trim markers: those describe what was REMOVED, this describes the
            // provenance of everything that follows, so it reads as a preamble to the whole history.
            if (restoredContextWarning != null) {
                out.add(restoredContextWarning.message().copy());
            }
            if (summaryEntry != null) {
                out.add(summaryEntry.message().copy());
            }
            if (trimMarker != null) {
                out.add(trimMarker.message().copy());
            }
            for (ContextEntry e : entries) {
                out.add(e.message().copy());
            }
            return List.copyOf(out);
        } finally {
            lock.unlock();
        }
    }

    private String renderPins() {
        StringBuilder sb = new StringBuilder();
        for (PinSlotEnum slot : PinSlotEnum.values()) {
            String text = pins.get(slot);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(text);
        }
        return sb.toString();
    }

    public final int entryCount() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    public final int estimatedTokenTotal() {
        lock.lock();
        try {
            int total = 0;
            for (ContextEntry e : entries) {
                total += e.estimatedTokens();
            }
            if (restoredContextWarning != null) {
                total += restoredContextWarning.estimatedTokens();
            }
            if (summaryEntry != null) {
                total += summaryEntry.estimatedTokens();
            }
            if (trimMarker != null) {
                total += trimMarker.estimatedTokens();
            }
            String pinned = renderPins();
            if (!pinned.isEmpty()) {
                total += estimateTokens(new ChatMessage(ChatRole.SYSTEM, pinned, List.of(), null));
            }
            return total;
        } finally {
            lock.unlock();
        }
    }

    public final long generation() {
        lock.lock();
        try {
            return generation;
        } finally {
            lock.unlock();
        }
    }

    // ---- turn lifecycle ----
    public final void beginTurn() {
        lock.lock();
        try {
            if (inTurn) {
                throw new IllegalStateException("a turn is already open");
            }
            inTurn = true;
            currentGroupId = ++groupCounter;
        } finally {
            lock.unlock();
        }
    }

    public final void commitTurn() {
        lock.lock();
        try {
            inTurn = false;
            currentGroupId = -1L;
            // One turn is all it gets. Every request WITHIN the first turn carries it, so a model that
            // needs several rounds to answer still sees it; keeping it beyond that would tax every later
            // request on a 32k window forever, and the entries it describes are being trimmed away anyway.
            restoredContextWarning = null;
        } finally {
            lock.unlock();
        }
    }

    public final void rollbackTurn() {
        lock.lock();
        try {
            if (!inTurn) {
                return;
            }
            long group = currentGroupId;
            int before = entries.size();
            entries.removeIf(e -> e.groupId() == group);
            inTurn = false;
            currentGroupId = -1L;
            generation++;
            debugLog.event("ROLLBACK", "group=" + group + " discarded="
                    + (before - entries.size()));
        } finally {
            lock.unlock();
        }
    }

    public final void clearHistory() {
        lock.lock();
        try {
            int dropped = mutableEntries().size();
            mutableEntries().clear();
            trimMarker = null;
            summaryEntry = null;
            // The restored entries it warned about have just gone, so the warning goes with them.
            restoredContextWarning = null;
            totalGroupsTrimmed = 0;
            bumpGeneration();
            debugLog.event("EVICT", "clearHistory dropped=" + dropped);
        } finally {
            lock.unlock();
        }
    }

    public final void setSummariser(ContextSummariser summariser) {
        this.summariser = summariser;
    }

    public final boolean isSummarising() {
        return summarising;
    }

    /**
     * Swaps the resolved settings the broker acts on. Called once per turn so a preference change — the token
     * threshold, most often — takes effect on the very next request instead of requiring a session restart.
     *
     * A no-op replacement (nothing actually differs) is not logged, or the debug log would fill with an
     * identical entry every single turn. When something did change, pinnedOverBudget is cleared too: it is
     * otherwise one-way sticky (only ever set true, by trimIfNeeded()/compactNow()), so without this a
     * threshold raised back to something workable would keep reporting the stale over-budget state until the
     * next trim happened to re-derive it.
     */
    public final void updateSettings(ContextBrokerSettings replacement) {
        if (replacement == null) {
            return;
        }
        lock.lock();
        try {
            ContextBrokerSettings previous = settings;
            boolean changed = previous.tokenThreshold() != replacement.tokenThreshold()
                    || previous.trimTargetPercent() != replacement.trimTargetPercent()
                    || previous.maxMessages() != replacement.maxMessages()
                    || previous.persistOnClose() != replacement.persistOnClose()
                    || previous.strategy() != replacement.strategy()
                    || previous.trigger() != replacement.trigger();
            settings = replacement;
            if (changed) {
                pinnedOverBudget = false;
                debugLog.event("SETTINGS", "threshold=" + replacement.tokenThreshold()
                        + " strategy=" + replacement.strategy()
                        + " trigger=" + replacement.trigger());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes the oldest committed group in full. The in-flight group is never a candidate: trimming runs
     * immediately before a request is built, so evicting the turn's own messages would send a request with no
     * user turn in it.
     *
     * @return true if a group was evicted
     */
    public final boolean evictOldestCommittedGroup() {
        lock.lock();
        try {
            Long oldest = null;
            for (ContextEntry e : mutableEntries()) {
                if (inTurn() && e.groupId() == currentGroupId()) {
                    continue;
                }
                if (oldest == null || e.groupId() < oldest) {
                    oldest = e.groupId();
                }
            }
            if (oldest == null) {
                return false;
            }
            final long target = oldest;
            int before = mutableEntries().size();
            int tokens = 0;
            for (ContextEntry e : mutableEntries()) {
                if (e.groupId() == target) {
                    tokens += e.estimatedTokens();
                }
            }
            mutableEntries().removeIf(e -> e.groupId() == target);
            bumpGeneration();
            debugLog.event("EVICT", "group=" + target
                    + " messages=" + (before - mutableEntries().size())
                    + " tokensReclaimed=" + tokens);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Feed the estimator the usage the backend reported for the request just sent. Null reported usage is
     * ignored, leaving the raw estimate standing.
     */
    public final void recordUsage(int estimated, Integer reported) {
        lock.lock();
        try {
            double before = estimator.calibrationRatio();
            estimator.calibrate(estimated, reported);
            if (reported != null && reported > 0) {
                lastReportedPromptTokens = reported;
            }
            if (reported != null) {
                debugLog.event("CALIBRATE", "estimated=" + estimated + " reported=" + reported
                        + " ratio " + before + " -> " + estimator.calibrationRatio());
            }
        } finally {
            lock.unlock();
        }
    }

    public final void resetCalibration() {
        lock.lock();
        try {
            estimator.reset();
            debugLog.event("CALIBRATE", "reset on model change");
        } finally {
            lock.unlock();
        }
    }

    public final double calibrationRatio() {
        lock.lock();
        try {
            return estimator.calibrationRatio();
        } finally {
            lock.unlock();
        }
    }

    public final boolean hasSeenReportedUsage() {
        lock.lock();
        try {
            return estimator.hasSeenReportedUsage();
        } finally {
            lock.unlock();
        }
    }

    public final void trimIfNeeded() {
        lock.lock();
        try {
            if (settings.strategy() == ContextTrimStrategyEnum.NONE) {
                return;
            }
            if (!overHighWaterMark()) {
                return;
            }
            int pinnedCost = pinnedTokensUnlocked();
            long lowWater = (long) trimThreshold() * settings.trimTargetPercent() / 100L;
            if (lowWater > 0 && pinnedCost >= lowWater) {
                pinnedOverBudget = true;
                debugLog.event("TRIM_SUMMARY", "skipped: pinned content " + pinnedCost
                        + " >= low-water " + lowWater + "; threshold too low to trim usefully");
                return;
            }
            int evicted = 0;
            if (settings.strategy() == ContextTrimStrategyEnum.SUMMARISE) {
                List<ChatMessage> evictedMessages = new ArrayList<>();
                while (overLowWaterMark() && evictOldestGroupUnlocked(evictedMessages)) {
                    evicted++;
                }
                if (evicted == 0) {
                    return;
                }
                totalGroupsTrimmed += evicted;
                debugLog.event("TRIM_SUMMARY", "groupsEvicted=" + evicted
                        + " totalTrimmed=" + totalGroupsTrimmed
                        + " tokensNow=" + estimatedTokenTotal()
                        + " strategy=" + settings.strategy());
                summariseEvicted(evictedMessages);
            } else {
                while (overLowWaterMark() && evictOldestCommittedGroup()) {
                    evicted++;
                }
                if (evicted == 0) {
                    return;
                }
                totalGroupsTrimmed += evicted;
                debugLog.event("TRIM_SUMMARY", "groupsEvicted=" + evicted
                        + " totalTrimmed=" + totalGroupsTrimmed
                        + " tokensNow=" + estimatedTokenTotal()
                        + " strategy=" + settings.strategy());
                if (settings.strategy() == ContextTrimStrategyEnum.DROP_MARKED) {
                    upsertTrimMarker();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Force a trim down to the low-water mark regardless of the high-water threshold. Backs the info bar's
     * Compact button.
     *
     * When a summariser is present it is used for any trim strategy — the Compact button is an explicit user
     * request to condense, not gated on the automatic-trim strategy.
     */
    public final int compactNow() {
        lock.lock();
        try {
            if (settings.strategy() == ContextTrimStrategyEnum.NONE) {
                return 0;
            }
            int pinnedCost = pinnedTokensUnlocked();
            long lowWater = (long) settings.tokenThreshold() * settings.trimTargetPercent() / 100L;
            if (lowWater > 0 && pinnedCost >= lowWater) {
                pinnedOverBudget = true;
                debugLog.event("TRIM_SUMMARY", "skipped: pinned content " + pinnedCost
                        + " >= low-water " + lowWater + "; threshold too low to trim usefully");
                return 0;
            }
            int evicted = 0;
            ContextSummariser s = summariser;
            if (s != null) {
                List<ChatMessage> evictedMessages = new ArrayList<>();
                while (overLowWaterMark() && evictOldestGroupUnlocked(evictedMessages)) {
                    evicted++;
                }
                if (evicted > 0) {
                    totalGroupsTrimmed += evicted;
                    debugLog.event("TRIM_SUMMARY", "compactNow groupsEvicted=" + evicted);
                    summariseEvicted(evictedMessages);
                }
            } else {
                while (overLowWaterMark() && evictOldestCommittedGroup()) {
                    evicted++;
                }
                if (evicted > 0) {
                    totalGroupsTrimmed += evicted;
                    if (settings.strategy() == ContextTrimStrategyEnum.DROP_MARKED) {
                        upsertTrimMarker();
                    }
                    debugLog.event("TRIM_SUMMARY", "compactNow groupsEvicted=" + evicted);
                }
            }
            return evicted;
        } finally {
            lock.unlock();
        }
    }

    private boolean overHighWaterMark() {
        if (settings.maxMessages() > 0 && mutableEntries().size() > settings.maxMessages()) {
            return true;
        }
        return currentUsage() > trimThreshold();
    }

    private boolean overLowWaterMark() {
        if (settings.maxMessages() > 0 && mutableEntries().size() > settings.maxMessages()) {
            return true;
        }
        long target = (long) settings.tokenThreshold() * settings.trimTargetPercent() / 100L;
        return currentUsage() > target;
    }

    /**
     * REPORTED_TOKENS is only meaningful once the endpoint has actually returned a usage object. Until then —
     * and permanently, if it never does — it behaves as ESTIMATED_TOKENS. Silently never trimming would be
     * far worse than approximating.
     */
    private int currentUsage() {
        if (settings.trigger() == ContextTriggerEnum.MESSAGE_COUNT) {
            return 0;
        }
        if (settings.trigger() == ContextTriggerEnum.REPORTED_TOKENS
                && estimator.hasSeenReportedUsage() && lastReportedPromptTokens > 0) {
            return lastReportedPromptTokens;
        }
        return estimatedTokenTotal();
    }

    /**
     * Evicts the oldest committed group without acquiring the lock (caller must hold it). Messages from the
     * evicted group are appended to {@code collect} if non-null.
     */
    private boolean evictOldestGroupUnlocked(List<ChatMessage> collect) {
        Long oldest = null;
        for (ContextEntry e : mutableEntries()) {
            if (inTurn && e.groupId() == currentGroupId) {
                continue;
            }
            if (oldest == null || e.groupId() < oldest) {
                oldest = e.groupId();
            }
        }
        if (oldest == null) {
            return false;
        }
        final long target = oldest;
        int before = mutableEntries().size();
        int tokens = 0;
        for (ContextEntry e : mutableEntries()) {
            if (e.groupId() == target) {
                if (collect != null) {
                    collect.add(e.message());
                }
                tokens += e.estimatedTokens();
            }
        }
        mutableEntries().removeIf(e -> e.groupId() == target);
        bumpGeneration();
        debugLog.event("EVICT", "group=" + target
                + " messages=" + (before - mutableEntries().size())
                + " tokensReclaimed=" + tokens);
        return true;
    }

    /**
     * Read-release-compute-reacquire. The summariser makes a network call that can take seconds, so the lock
     * must not be held across it. A generation counter guards the write-back: if anything mutated the history
     * while we were summarising, the summary describes a state that no longer exists and is discarded in
     * favour of the drop marker.
     */
    private void summariseEvicted(List<ChatMessage> span) {
        ContextSummariser s = summariser;
        if (s == null || span.isEmpty()) {
            upsertTrimMarker();
            return;
        }
        long generationAtStart = generation;
        summarising = true;
        String summary = null;
        assert lock.getHoldCount() == 1 : "trimIfNeeded should hold the lock exactly once";
        lock.unlock();
        try {
            summary = s.summarise(span);
        } catch (IOException | RuntimeException ex) {
            debugLog.event("SUMMARISE", "failed: " + ex.getMessage());
        } finally {
            lock.lock();
            summarising = false;
        }
        if (summary == null || summary.isBlank()) {
            debugLog.event("SUMMARISE", "blank or failed, falling back to DROP_MARKED");
            upsertTrimMarker();
            return;
        }
        if (generation != generationAtStart) {
            debugLog.event("SUMMARISE", "discarded: generation moved "
                    + generationAtStart + " -> " + generation);
            upsertTrimMarker();
            return;
        }
        upsertSummary(summary);
        debugLog.event("SUMMARISE", "applied " + summary.length() + " chars");
    }

    private void upsertSummary(String text) {
        String content = "[Summary of earlier conversation: " + text + "]";
        if (summaryEntry == null) {
            summaryEntry = new ContextEntry(++sequenceForMarker, -2L,
                    System.currentTimeMillis(),
                    new ChatMessage(ChatRole.SYSTEM, content, List.of(), null),
                    ContextRetentionEnum.PINNED,
                    estimateTokens(new ChatMessage(ChatRole.SYSTEM, content, List.of(), null)), null);
        } else {
            summaryEntry.message().setContent(content);
            summaryEntry.setEstimatedTokens(estimateTokens(new ChatMessage(ChatRole.SYSTEM, content, List.of(), null)));
        }
    }

    private void upsertTrimMarker() {
        String text = "[" + totalGroupsTrimmed
                + " earlier exchange(s) were trimmed to fit the context window.]";
        if (trimMarker == null) {
            trimMarker = new ContextEntry(++sequenceForMarker, -1L, System.currentTimeMillis(),
                    new ChatMessage(ChatRole.SYSTEM, text, List.of(), null),
                    ContextRetentionEnum.PINNED,
                    estimateTokens(new ChatMessage(ChatRole.SYSTEM, text, List.of(), null)), null);
        } else {
            trimMarker.message().setContent(text);
            trimMarker.setEstimatedTokens(estimateTokens(new ChatMessage(ChatRole.SYSTEM, text, List.of(), null)));
        }
    }

    public final boolean isPinnedOverBudget() {
        return pinnedOverBudget;
    }

    private int pinnedTokensUnlocked() {
        String pinned = renderPins();
        if (pinned.isEmpty()) {
            return 0;
        }
        return estimateTokens(new ChatMessage(ChatRole.SYSTEM, pinned, List.of(), null));
    }

    public final JsonObject toJson() {
        lock.lock();
        try {
            JsonObject root = new JsonObject();
            root.addProperty(ContextJsonKeyEnum.VERSION.key(), FORMAT_VERSION);
            root.addProperty(ContextJsonKeyEnum.SESSION_ID.key(), sessionId);
            root.addProperty(ContextJsonKeyEnum.SAVED_AT.key(), System.currentTimeMillis());
            root.addProperty(ContextJsonKeyEnum.CALIBRATION_RATIO.key(), estimator.calibrationRatio());
            JsonArray arr = new JsonArray();
            for (ContextEntry e : mutableEntries()) {
                if (!RESTORED_CONTEXT_WARNING.equals(e.message().content())) {
                    arr.add(e.toJson());
                }
            }
            root.add(ContextJsonKeyEnum.ENTRIES.key(), arr);
            debugLog.event("PERSIST", "entries=" + mutableEntries().size());
            return root;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Restoration is deliberately partial. Evictable entries come back verbatim so group-atomic eviction
     * still holds; pinned slots are NOT restored, because instructions, identity and project state may all
     * have changed between runs and a stale system prompt fails silently while looking correct.
     */
    public final void restoreFromJson(JsonObject root) {
        lock.lock();
        try {
            mutableEntries().clear();
            if (root == null || !root.has(ContextJsonKeyEnum.VERSION.key())
                    || root.get(ContextJsonKeyEnum.VERSION.key()).getAsInt() != FORMAT_VERSION) {
                debugLog.event("RESTORE", "refused: unknown or missing " + ContextJsonKeyEnum.VERSION.key());
                return;
            }
            if (root.has(ContextJsonKeyEnum.CALIBRATION_RATIO.key())) {
                estimator.restoreRatio(root.get(ContextJsonKeyEnum.CALIBRATION_RATIO.key()).getAsDouble());
            }
            JsonArray arr = root.getAsJsonArray(ContextJsonKeyEnum.ENTRIES.key());
            if (arr == null) {
                return;
            }
            List<ContextEntry> loaded = new ArrayList<>();
            long maxSequence = 0L;
            long maxGroup = 0L;
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                ContextEntry entry;
                try {
                    entry = ContextEntry.fromJson(el.getAsJsonObject());
                } catch (RuntimeException ex) {
                    continue;
                }
                if (entry.retention() == ContextRetentionEnum.PINNED) {
                    continue;
                }
                loaded.add(entry);
                maxSequence = Math.max(maxSequence, entry.sequence());
                maxGroup = Math.max(maxGroup, entry.groupId());
            }
            for (ContextEntry e : dropIncompleteGroups(loaded)) {
                mutableEntries().add(e);
            }
            // Only when something actually came back: warning about an empty history would be noise, and
            // it must NOT be keyed on native/schema mode — restore runs at session open, before
            // setNativeToolCalling is called for the first turn, so any such condition reads the default.
            if (!mutableEntries().isEmpty()) {
                ChatMessage warning = new ChatMessage(ChatRole.SYSTEM, RESTORED_CONTEXT_WARNING, List.of(), null);
                restoredContextWarning = new ContextEntry(++sequenceForMarker, -3L, System.currentTimeMillis(),
                        warning, ContextRetentionEnum.PINNED, estimateTokens(warning), null);
            }
            sequenceCounter = maxSequence;
            groupCounter = maxGroup;
            bumpGeneration();
            debugLog.event("RESTORE", "entries=" + mutableEntries().size()
                    + " warning=" + (restoredContextWarning != null));
        } finally {
            lock.unlock();
        }
    }

    protected final List<ContextEntry> entriesForTesting() {
        lock.lock();
        try {
            return List.copyOf(entries);
        } finally {
            lock.unlock();
        }
    }

    protected final boolean inTurn() {
        lock.lock();
        try {
            return inTurn;
        } finally {
            lock.unlock();
        }
    }

    protected final long currentGroupId() {
        lock.lock();
        try {
            return currentGroupId;
        } finally {
            lock.unlock();
        }
    }

    protected final List<ContextEntry> mutableEntries() {
        return entries;
    }

    protected final void bumpGeneration() {
        generation++;
    }
}
