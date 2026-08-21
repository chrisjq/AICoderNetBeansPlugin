package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

/**
 * Field names in the persisted chat-history file written by
 * {@link HistoryPersistenceManager}.
 * <p>
 * <b>ONLY ADD KEYS HERE. NEVER CHANGE OR REMOVE ONE.</b>
 * <p>
 * These values are an on-disk format holding the user's saved conversations.
 * Renaming a key does not fail loudly: the loader skips any message whose role,
 * text or timestamp is absent, so a rename empties every conversation already
 * written to disk. The user opens an old session and the transcript is blank,
 * with no error and a green build.
 * <p>
 * That this file already carries a fallback for a previous format — the "Old
 * format — bare array" branch in {@code HistoryPersistenceManager} — is the
 * proof that on-disk shapes outlive the code that wrote them. Anything you
 * rename today you will still be reading a fallback for in a year.
 * <p>
 * No golden test pins these spellings yet, unlike the context snapshot's
 * {@code ContextSnapshotFormatTest}. Adding one would be worthwhile; until then
 * this comment is the only guard.
 * <p>
 * Separate from {@link SessionPersistenceKeyEnum} because these are two
 * different files with two different shapes. {@code sessionId} appearing in
 * both is a coincidence of naming, not a shared field.
 */
public enum HistoryPersistenceKeyEnum {
    /**
     * Session this history belongs to.
     */
    SESSION_ID("sessionId"),
    /**
     * Working directory the conversation ran against.
     */
    WORKING_DIR("workingDir"),
    /**
     * Whether the MCP instruction guide had been loaded when saved.
     */
    INSTRUCTIONS_LOADED("instructionsLoaded"),
    /**
     * Array of stored messages.
     */
    MESSAGES("messages"),
    /**
     * Speaker of a stored message.
     */
    ROLE("role"),
    /**
     * Body of a stored message.
     */
    TEXT("text"),
    /**
     * When the message was recorded.
     */
    TIMESTAMP("timestamp");

    private final String key;

    HistoryPersistenceKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
