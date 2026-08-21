package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

/**
 * Field names in the JSON a tool returns, as opposed to the arguments it
 * accepts.
 * <p>
 * Kept separate from
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum} on
 * purpose. That enum names the parameters a caller sends in; these name what
 * comes back out. Several words appear in both vocabularies —
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum#METHOD}
 * is an argument to {@code WebRequest} and {@link #METHOD} is also a response
 * field — and folding them together would mean renaming a parameter silently
 * renamed a response field that callers parse. They are different contracts
 * that happen to share spellings.
 * <p>
 * These are read by the AI rather than by another program, so a rename is not
 * catastrophic; it is still worth one definition each so a tool and its
 * documentation cannot drift.
 */
public enum ToolResponseKeyEnum {
    // WebRequest response
    /**
     * The URL as supplied by the caller, before any redirects.
     */
    REQUESTED_URL("requestedUrl"),
    /**
     * The URL actually fetched, after redirects.
     */
    FINAL_URL("finalUrl"),
    /**
     * HTTP method used.
     */
    METHOD("method"),
    /**
     * HTTP status code of the response.
     */
    STATUS("status"),
    /**
     * Response headers. Note WebRequest also takes a {@code headers} argument —
     * same spelling, different direction, which is why the two vocabularies are
     * separate enums.
     */
    HEADERS("headers"),
    /**
     * Whether the body was cut short by the character cap.
     */
    TRUNCATED("truncated"),
    /**
     * Response body, possibly truncated.
     */
    BODY("body"),
    // ListAiSessions response
    /**
     * Peer session identifier, passed back as targetSessionId when messaging
     * it.
     */
    SESSION_ID("sessionId"),
    /**
     * Which AI implementation the peer session runs.
     */
    AI_TYPE("aiType"),
    /**
     * True while the peer is mid-turn; both states can still receive mail.
     */
    ACTIVE("active");

    private final String key;

    ToolResponseKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
