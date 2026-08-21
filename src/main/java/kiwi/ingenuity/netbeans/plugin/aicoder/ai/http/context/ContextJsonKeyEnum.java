package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

/**
 * Field names in the persisted context snapshot — the envelope written by
 * {@code AbstractChatContextBroker.toJson()} and the per-entry fields written
 * by {@code ContextEntry.toJson()}. One enum because they are one file.
 * <p>
 * <b>ONLY ADD KEYS HERE. NEVER CHANGE OR REMOVE ONE.</b>
 * <p>
 * These values are an on-disk format, and changing one throws away the user's
 * conversation history. Not "degrades it" — discards it, with no error and a
 * green test suite. The mechanism, traced through the code:
 * <ol>
 * <li>{@code ContextEntry.fromJson} reads its required fields with
 * {@code o.get(KEY).getAsLong()} and friends. Under a renamed key that
 * {@code get} returns null and the call throws.</li>
 * <li>{@code AbstractChatContextBroker.restoreFromJson} catches
 * {@code RuntimeException} per entry and does {@code continue} — so every entry
 * is skipped in turn.</li>
 * <li>The restore completes "successfully" with an empty context. The user
 * reopens a session and their history is simply gone.</li>
 * </ol>
 * Do not rely on {@link #VERSION} to catch this. That gate only fires when
 * someone deliberately bumps {@code FORMAT_VERSION}; a rename here sails
 * straight through it, because nothing links the two. The version field
 * protects deliberate format changes, not accidental ones.
 * <p>
 * {@code ContextSnapshotFormatTest} is what actually guards these values: it
 * hardcodes the literal key strings, so changing one turns the build red
 * instead of silently eating history. If that test fails after you edited this
 * enum, the test is right and the edit is wrong.
 * <p>
 * If a key genuinely must change: bump {@code FORMAT_VERSION}, keep reading the
 * old name as a fallback, and update the golden test to cover both. Anything
 * less loses data that belongs to the user, not to us.
 */
public enum ContextJsonKeyEnum {
    // Envelope
    /** Snapshot format version, checked on load. */
    VERSION("version"),
    /** Session the snapshot belongs to. */
    SESSION_ID("sessionId"),
    /** When the snapshot was written. */
    SAVED_AT("savedAt"),
    /** Learned tokens-per-character ratio carried across restarts. */
    CALIBRATION_RATIO("calibrationRatio"),
    /** Array of context entries. */
    ENTRIES("entries"),

    // Per-entry
    /** Stable entry identifier. */
    ID("id"),
    /** Speaker or origin of the entry. */
    ROLE("role"),
    /** Entry body. */
    CONTENT("content"),
    /** Ordering position within the context. */
    SEQUENCE("sequence"),
    /** When the entry was added. */
    TIMESTAMP("timestamp"),
    /** Cached token estimate, avoiding a re-count on load. */
    ESTIMATED_TOKENS("estimatedTokens"),
    /** Whether the entry is pinned, trimmable or summarisable. */
    RETENTION("retention"),
    /** Groups entries that must be trimmed together. */
    GROUP_ID("groupId"),
    /** Provider-side prompt cache identifier. */
    CACHE_ID("cacheId"),
    /** Tool name for a tool-call entry. */
    NAME("name"),
    /** Serialised tool-call arguments. */
    ARGUMENTS("arguments"),
    /** Array of tool calls attached to an assistant entry. */
    TOOL_CALLS("toolCalls"),
    /** Links a tool result back to the call that produced it. */
    TOOL_CALL_ID("toolCallId");

    private final String key;

    ContextJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
