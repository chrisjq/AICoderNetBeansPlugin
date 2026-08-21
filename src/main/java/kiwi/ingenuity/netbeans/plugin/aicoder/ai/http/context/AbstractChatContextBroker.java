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
 * Thread-safe: a single ReentrantLock guards all state, and the lock is never
 * held across I/O. Callers never need external locking.
 */
public abstract class AbstractChatContextBroker {

    // Bump whenever a ContextJsonKeyEnum value is renamed or removed, and give
    // the old spelling a migration path. The gate below only fires on THIS
    // number; an unbumped rename sails through it and restoreFromJson then
    // silently skips every entry whose keys it can no longer find.
    // ContextSnapshotFormatTest pins the version-1 spellings.
    private static final int FORMAT_VERSION = 1;

    /**
     * A file truncated by a crash mid-write can contain an assistant tool_calls
     * message with no matching TOOL results, or the reverse. Either produces a
     * payload the endpoint rejects with HTTP 400, so the whole group goes.
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

    private int totalGroupsTrimmed = 0;
    private volatile int lastReportedPromptTokens = 0;
    private ContextEntry trimMarker = null;
    private long sequenceForMarker = 0L;
    private volatile ContextSummariser summariser;
    private volatile boolean summarising = false;
    private volatile boolean pinnedOverBudget = false;
    private ContextEntry summaryEntry = null;

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
        }
        finally {
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
        }
        finally {
            lock.unlock();
        }
    }

    public final List<ChatMessage> snapshot() {
        lock.lock();
        try {
            List<ChatMessage> out = new ArrayList<>();
            String pinned = renderPins();
            if (!pinned.isEmpty()) {
                out.add(new ChatMessage(ChatRole.SYSTEM, pinned, List.of(), null));
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
        }
        finally {
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
        }
        finally {
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
            String pinned = renderPins();
            if (!pinned.isEmpty()) {
                total += estimateTokens(new ChatMessage(ChatRole.SYSTEM, pinned, List.of(), null));
            }
            return total;
        }
        finally {
            lock.unlock();
        }
    }

    public final long generation() {
        lock.lock();
        try {
            return generation;
        }
        finally {
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
        }
        finally {
            lock.unlock();
        }
    }

    public final void commitTurn() {
        lock.lock();
        try {
            inTurn = false;
            currentGroupId = -1L;
        }
        finally {
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
        }
        finally {
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
            totalGroupsTrimmed = 0;
            bumpGeneration();
            debugLog.event("EVICT", "clearHistory dropped=" + dropped);
        }
        finally {
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
     * Swaps the resolved settings the broker acts on. Called once per turn so a
     * preference change — the token threshold, most often — takes effect on the
     * very next request instead of requiring a session restart.
     *
     * A no-op replacement (nothing actually differs) is not logged, or the
     * debug log would fill with an identical entry every single turn. When
     * something did change, pinnedOverBudget is cleared too: it is otherwise
     * one-way sticky (only ever set true, by trimIfNeeded()/compactNow()), so
     * without this a threshold raised back to something workable would keep
     * reporting the stale over-budget state until the next trim happened to
     * re-derive it.
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
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * Removes the oldest committed group in full. The in-flight group is never
     * a candidate: trimming runs immediately before a request is built, so
     * evicting the turn's own messages would send a request with no user turn
     * in it.
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
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * Feed the estimator the usage the backend reported for the request just
     * sent. Null reported usage is ignored, leaving the raw estimate standing.
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
        }
        finally {
            lock.unlock();
        }
    }

    public final void resetCalibration() {
        lock.lock();
        try {
            estimator.reset();
            debugLog.event("CALIBRATE", "reset on model change");
        }
        finally {
            lock.unlock();
        }
    }

    public final double calibrationRatio() {
        lock.lock();
        try {
            return estimator.calibrationRatio();
        }
        finally {
            lock.unlock();
        }
    }

    public final boolean hasSeenReportedUsage() {
        lock.lock();
        try {
            return estimator.hasSeenReportedUsage();
        }
        finally {
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
            long lowWater = (long) settings.tokenThreshold() * settings.trimTargetPercent() / 100L;
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
            }
            else {
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
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * Force a trim down to the low-water mark regardless of the high-water
     * threshold. Backs the info bar's Compact button.
     *
     * When a summariser is present it is used for any trim strategy — the
     * Compact button is an explicit user request to condense, not gated on the
     * automatic-trim strategy.
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
            }
            else {
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
        }
        finally {
            lock.unlock();
        }
    }

    private boolean overHighWaterMark() {
        if (settings.maxMessages() > 0 && mutableEntries().size() > settings.maxMessages()) {
            return true;
        }
        return currentUsage() > settings.tokenThreshold();
    }

    private boolean overLowWaterMark() {
        if (settings.maxMessages() > 0 && mutableEntries().size() > settings.maxMessages()) {
            return true;
        }
        long target = (long) settings.tokenThreshold() * settings.trimTargetPercent() / 100L;
        return currentUsage() > target;
    }

    /**
     * REPORTED_TOKENS is only meaningful once the endpoint has actually
     * returned a usage object. Until then — and permanently, if it never does —
     * it behaves as ESTIMATED_TOKENS. Silently never trimming would be far
     * worse than approximating.
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
     * Evicts the oldest committed group without acquiring the lock (caller must
     * hold it). Messages from the evicted group are appended to {@code collect}
     * if non-null.
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
     * Read-release-compute-reacquire. The summariser makes a network call that
     * can take seconds, so the lock must not be held across it. A generation
     * counter guards the write-back: if anything mutated the history while we
     * were summarising, the summary describes a state that no longer exists and
     * is discarded in favour of the drop marker.
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
        }
        catch (IOException | RuntimeException ex) {
            debugLog.event("SUMMARISE", "failed: " + ex.getMessage());
        }
        finally {
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
                    ContextRetentionEnum.PINNED, 0, null);
        }
        else {
            summaryEntry.message().setContent(content);
        }
    }

    private void upsertTrimMarker() {
        String text = "[" + totalGroupsTrimmed
                + " earlier exchange(s) were trimmed to fit the context window.]";
        if (trimMarker == null) {
            trimMarker = new ContextEntry(++sequenceForMarker, -1L, System.currentTimeMillis(),
                    new ChatMessage(ChatRole.SYSTEM, text, List.of(), null),
                    ContextRetentionEnum.PINNED, 0, null);
        }
        else {
            trimMarker.message().setContent(text);
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
                arr.add(e.toJson());
            }
            root.add(ContextJsonKeyEnum.ENTRIES.key(), arr);
            debugLog.event("PERSIST", "entries=" + mutableEntries().size());
            return root;
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * Restoration is deliberately partial. Evictable entries come back verbatim
     * so group-atomic eviction still holds; pinned slots are NOT restored,
     * because instructions, identity and project state may all have changed
     * between runs and a stale system prompt fails silently while looking
     * correct.
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
                }
                catch (RuntimeException ex) {
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
            sequenceCounter = maxSequence;
            groupCounter = maxGroup;
            bumpGeneration();
            debugLog.event("RESTORE", "entries=" + mutableEntries().size());
        }
        finally {
            lock.unlock();
        }
    }

    protected final List<ContextEntry> entriesForTesting() {
        lock.lock();
        try {
            return List.copyOf(entries);
        }
        finally {
            lock.unlock();
        }
    }

    protected final boolean inTurn() {
        lock.lock();
        try {
            return inTurn;
        }
        finally {
            lock.unlock();
        }
    }

    protected final long currentGroupId() {
        lock.lock();
        try {
            return currentGroupId;
        }
        finally {
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
