package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

/**
 * Field names in the persisted session-index file written by {@link SessionPersistenceManager}.
 * <p>
 * <b>ONLY ADD KEYS HERE. NEVER CHANGE OR REMOVE ONE.</b>
 * <p>
 * These values are an on-disk format. Unlike a wire protocol, where a rename breaks the next request and you find out
 * in seconds, renaming a key here breaks files written months ago: the field reads back absent and the session silently
 * loses its name, project path or AI type. Worse, this loader treats a session missing required fields as malformed and
 * skips it — see {@code SessionPersistenceManager}'s "Session missing required fields, skipping" branch — so a renamed
 * key does not degrade sessions, it deletes them from the user's list.
 * <p>
 * Nothing here has a version gate at all, so the guard is {@code SessionIndexFormatGoldenTest}: one test hand-writes an
 * index document using these literal strings and loads it, and a separate test saves and asserts the written entry's
 * key set is exactly these names. It also skips a session on a missing or unknown aiType, so that key is load-critical
 * too. Change a value here and that test goes red, which is the whole point of it. If it fails after you edited this
 * enum, the test is right and the edit is wrong.
 * <p>
 * If a key genuinely must change, keep reading the old name as a fallback indefinitely — users restore old profiles and
 * sync stale files between machines, so "everyone will have migrated by now" is not true.
 * <p>
 * The enum exists so the writer and the reader cannot disagree about a spelling — that mismatch has the same effect as
 * a rename and is easier to introduce.
 */
public enum SessionPersistenceKeyEnum {
    /**
     * Stable session identifier.
     */
    ID("id"),
    /**
     * User-visible session name.
     */
    NAME("name"),
    /**
     * Which AI implementation the session belongs to.
     */
    AI_TYPE("aiType"),
    /**
     * Absolute path of the project the session was opened against.
     */
    PROJECT_PATH("projectPath"),
    /**
     * Free-text session description shown in the UI.
     */
    DESCRIPTION("description"),
    /**
     * Creation timestamp.
     */
    CREATED_AT("createdAt"),
    /**
     * Timestamp of the most recent use, for ordering the session list.
     */
    LAST_USED_AT("lastUsedAt"),
    /**
     * Nested per-AI settings object.
     */
    CONFIG("config"),
    /**
     * When session instructions are delivered to the backend.
     */
    SESSION_INSTRUCTIONS_DELIVERY("sessionInstructionsDelivery"),
    /**
     * Whether startup instructions have already been injected.
     */
    STARTUP_INSTRUCTIONS_INJECTED("startupInstructionsInjected"),
    /**
     * The instruction text last sent, so unchanged instructions are not resent.
     */
    LAST_INJECTED_INSTRUCTIONS("lastInjectedInstructions");

    private final String key;

    SessionPersistenceKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
