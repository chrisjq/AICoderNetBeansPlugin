package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

/**
 * A property of the backend's wire protocol that indicates how steering text is conveyed when a native tool
 * call is denied. The value is not user‑ configurable; it is a backend fact used by the plugin to decide
 * whether the user should be shown a follow‑up turn.
 *
 * Values are documented in the same style as {@link MailDeliveryTimingEnum}.
 */
public enum McpSteeringSupportEnum {
    /**
     * The backend has no native tool calls that the agent can refuse, e.g. it only accepts MCP calls. The
     * option cannot do anything, so the steering text is not relevant.
     */
    NOT_APPLICABLE,
    /**
     * The backend is able to intercept a denial, but the plugin cannot yet see the native tool call. The
     * feature is deferred, not impossible.
     */
    NOT_INTERCEPTABLE,
    /**
     * When the backend returns a denial with a message (e.g. a refusal payload that contains text) the
     * steering text travels in the same turn and no follow‑up turn is needed.
     */
    DENY_WITH_MESSAGE,
    /**
     * The backend replies with a bare token indicating denial. The steering text must therefore be delivered
     * as a separate follow‑up turn.
     */
    DENY_NEEDS_FOLLOW_UP;

    /**
     * Does this backing support steering that the agent can consume? True for {@link #DENY_WITH_MESSAGE} and
     * {@link #DENY_NEEDS_FOLLOW_UP}.
     */
    public boolean supported() {
        return this == DENY_WITH_MESSAGE || this == DENY_NEEDS_FOLLOW_UP;
    }

    /**
     * Is a follow‑up turn required after a denial? True only for {@link #DENY_NEEDS_FOLLOW_UP}.
     */
    public boolean needsFollowUp() {
        return this == DENY_NEEDS_FOLLOW_UP;
    }
}
