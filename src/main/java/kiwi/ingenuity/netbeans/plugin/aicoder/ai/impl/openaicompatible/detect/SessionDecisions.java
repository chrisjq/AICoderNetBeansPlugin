package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;

/**
 * Per-session decisions that detection must not write (spec §3.5). Thread-safe: a retry on a background thread and a
 * settings change on the EDT may both touch it.
 * <p>
 * Optional-field flags delegate to one {@link CompatibilityFlags}. Also remembered here: the window learned from an
 * overflow error and that the server answered without streaming. The tool-calling decisions (the sticky schema switch
 * and the native-failure count) live in the turn package's tool-calling state, not here. {@link #reset()} clears these
 * decisions only. Facts from an older {@link #generation()} are discarded and are not cleared by reset.
 * <p>
 * A request does not use the live flags: {@link #beginRequest} hands out a copy that starts from what the session has
 * learned and, for a server of unknown or generic kind, from the conservative baseline; {@link RequestFlags#finish()}
 * then records what the request itself learned.
 */
public final class SessionDecisions {

    private final CompatibilityFlags flags = new CompatibilityFlags();
    private String generation;
    private DetectedFacts accepted;
    private Integer learnedWindow;
    private boolean nonStreaming;

    public SessionDecisions(String generation) {
        this.generation = generation;
    }

    /**
     * The live flags object, holding what the session has learned. A request must use {@link #beginRequest} so a later
     * {@link #turnOff} cannot change a request already built.
     */
    public synchronized CompatibilityFlags flags() {
        return flags;
    }

    public synchronized String generation() {
        return generation;
    }

    /**
     * The generation after a URL, model, key or server-type change. Facts from the previous generation are then
     * discarded on read. Does not clear decisions; call {@link #reset()} for that.
     */
    public synchronized void generation(String generation) {
        this.generation = generation;
    }

    public synchronized boolean supportsStreamUsage() {
        return flags.supportsStreamUsage();
    }

    public synchronized boolean supportsReasoningEffort() {
        return flags.supportsReasoningEffort();
    }

    public synchronized boolean supportsJsonSchemaResponseFormat() {
        return flags.supportsJsonSchemaResponseFormat();
    }

    public synchronized boolean usesMaxCompletionTokens() {
        return flags.usesMaxCompletionTokens();
    }

    public synchronized void turnOff(String field) {
        flags.turnOff(field);
    }

    public synchronized void dropAllOptional() {
        flags.dropAllOptional();
    }

    public synchronized Set<String> optionalFieldsPresent(ChatRequestSpec spec) {
        return flags.optionalFieldsPresent(spec);
    }

    public synchronized Integer learnedWindow() {
        return learnedWindow;
    }

    /**
     * Stores a window learned from an overflow error. Zero, negative and null clear it.
     */
    public synchronized void learnedWindow(Integer tokens) {
        learnedWindow = tokens != null && tokens > 0 ? tokens : null;
    }

    /**
     * Records that the server answered a request with one complete body instead of a stream (spec §3.3).
     *
     * @return true the first time, so the caller can note it once
     */
    public synchronized boolean recordNonStreaming() {
        boolean first = !nonStreaming;
        nonStreaming = true;
        return first;
    }

    /**
     * @return {@code FALSE} once the server was seen not to stream; otherwise what detection says, or null when unknown
     */
    public synchronized Boolean streaming() {
        if (nonStreaming) {
            return Boolean.FALSE;
        }
        DetectedFacts current = facts();
        return current == null ? null : current.streaming();
    }

    /**
     * Keeps a detection snapshot only when its generation is the current one. A stale snapshot is discarded and the
     * previously accepted snapshot is kept.
     *
     * @return true when the snapshot was kept
     */
    public synchronized boolean accept(DetectedFacts facts) {
        if (facts == null || generation == null || !generation.equals(facts.generation())) {
            return false;
        }
        accepted = facts;
        return true;
    }

    /**
     * @return the last accepted snapshot whose generation is still current, or null when it is stale or absent; once
     * the server was seen not to stream, with streaming set to false
     */
    public synchronized DetectedFacts facts() {
        if (accepted == null || generation == null || !generation.equals(accepted.generation())) {
            return null;
        }
        return nonStreaming ? accepted.withStreaming(Boolean.FALSE) : accepted;
    }

    /**
     * Clears flags, the learned window and the non-streaming note. Does not clear the generation or the accepted facts.
     */
    public synchronized void reset() {
        flags.reset();
        learnedWindow = null;
        nonStreaming = false;
    }

    /**
     * Starts one request or turn: facts and decisions as they are now, in a flags copy the request may change.
     */
    public RequestFlags beginRequest(DetectedFacts facts) {
        return beginRequestSnapshot(combine(facts, this));
    }

    /**
     * Starts a request from an already captured immutable snapshot. This overload prevents a poller update between
     * snapshot capture and request construction from mixing facts and flags from different generations.
     */
    public RequestFlags beginRequestSnapshot(Combined combined) {
        return new RequestFlags(combined);
    }

    /**
     * One immutable view of facts and decisions for a single request. Facts whose generation is not
     * {@link #generation()} are discarded ({@code facts} is null); the decisions are still copied. For facts that are
     * absent or of generic kind the flags start from the conservative baseline (no reasoning-effort and no JSON-schema
     * response format) on top of what the session has learned.
     */
    public static Combined combine(DetectedFacts facts, SessionDecisions decisions) {
        if (decisions == null) {
            return new Combined(null, baseline(null, new CompatibilityFlags()), null, null);
        }
        synchronized (decisions) {
            DetectedFacts current = facts != null && decisions.generation != null
                    && decisions.generation.equals(facts.generation()) ? facts : null;
            Boolean streaming = decisions.nonStreaming ? Boolean.FALSE : current == null ? null : current.streaming();
            return new Combined(current, baseline(current, copyFlags(decisions.flags)), decisions.learnedWindow,
                                streaming);
        }
    }

    /**
     * Whether a server is treated conservatively: nothing detected, or detected as a generic OpenAI-compatible server
     * (spec §3.5).
     */
    public static boolean conservative(DetectedFacts facts) {
        return facts == null || facts.kind() == null || facts.kind() == ServerKind.GENERIC;
    }

    private static CompatibilityFlags baseline(DetectedFacts facts, CompatibilityFlags flags) {
        if (conservative(facts)) {
            flags.turnOff(CompatibilityFlags.REASONING_EFFORT);
            flags.turnOff(CompatibilityFlags.RESPONSE_FORMAT);
        }
        return flags;
    }

    private static CompatibilityFlags copyFlags(CompatibilityFlags source) {
        CompatibilityFlags copy = new CompatibilityFlags();
        if (source == null) {
            return copy;
        }
        if (!source.supportsStreamUsage()) {
            copy.turnOff(CompatibilityFlags.STREAM_OPTIONS);
        }
        if (!source.supportsReasoningEffort()) {
            copy.turnOff(CompatibilityFlags.REASONING_EFFORT);
        }
        if (!source.supportsJsonSchemaResponseFormat()) {
            copy.turnOff(CompatibilityFlags.RESPONSE_FORMAT);
        }
        if (source.usesMaxCompletionTokens()) {
            copy.turnOff(CompatibilityFlags.MAX_TOKENS);
        }
        return copy;
    }

    /**
     * Facts and decisions as they were when the request was built. {@code flags} is a copy, not the live session flags.
     */
    public record Combined(DetectedFacts facts, CompatibilityFlags flags, Integer learnedWindow, Boolean streaming) {

    }

    /**
     * The flags one turn works with. The client turns a flag off in {@link #flags()} when the server rejects the field;
     * {@link #finish()} copies those findings to the session, so the next turn starts without the field. A field the
     * baseline withheld is not recorded as rejected.
     */
    public final class RequestFlags {

        private final CompatibilityFlags requestFlags;
        private final boolean streamUsageAtStart;
        private final boolean reasoningEffortAtStart;
        private final boolean responseFormatAtStart;
        private final boolean maxCompletionTokensAtStart;

        private RequestFlags(Combined combined) {
            requestFlags = combined.flags();
            streamUsageAtStart = requestFlags.supportsStreamUsage();
            reasoningEffortAtStart = requestFlags.supportsReasoningEffort();
            responseFormatAtStart = requestFlags.supportsJsonSchemaResponseFormat();
            maxCompletionTokensAtStart = requestFlags.usesMaxCompletionTokens();
        }

        /**
         * The copy the request sends with and the client updates.
         */
        public CompatibilityFlags flags() {
            return requestFlags;
        }

        /**
         * Records in the session every field that was usable at the start and has been rejected since.
         */
        public void finish() {
            synchronized (SessionDecisions.this) {
                if (streamUsageAtStart && !requestFlags.supportsStreamUsage()) {
                    flags.turnOff(CompatibilityFlags.STREAM_OPTIONS);
                }
                if (reasoningEffortAtStart && !requestFlags.supportsReasoningEffort()) {
                    flags.turnOff(CompatibilityFlags.REASONING_EFFORT);
                }
                if (responseFormatAtStart && !requestFlags.supportsJsonSchemaResponseFormat()) {
                    flags.turnOff(CompatibilityFlags.RESPONSE_FORMAT);
                }
                if (!maxCompletionTokensAtStart && requestFlags.usesMaxCompletionTokens()) {
                    flags.turnOff(CompatibilityFlags.MAX_TOKENS);
                }
            }
        }
    }
}
