package kiwi.ingenuity.netbeans.plugin.aicoder.ai.session;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;

/**
 * Why a running turn is being interrupted.
 *
 * @author chris
 */
public enum InterruptTypeEnum {
    /**
     * A peer session has sent inbox mail and the target should read it without waiting for its turn to end. Backends
     * that can inject mid-turn do so; the rest ignore it and the message arrives at the normal end-of-turn flush, so
     * this is always a promptness optimisation and never the delivery mechanism itself.
     */
    Mail,
    /**
     * The user pressed Stop. Ends the turn.
     */
    Cancel;

    /**
     * What a backend injects mid-turn for {@link #Mail}.
     *
     * <p>
     * A notification, deliberately not the message body: the agent is told mail exists and fetches it with the inbox
     * tools. That keeps one copy of the message — in the inbox — instead of pasting a second into the conversation, and
     * avoids putting arbitrary peer-authored text into a turn as though the user wrote it.
     *
     * <p>
     * Lives here rather than in any one backend so Codex and OpenCode (and anything added later) share the exact
     * wording without depending on each other.
     */
    public static final String MAIL_NOTIFICATION_TEXT = "[inbox] You have a new message — check your inbox NOW with " + McpToolEnum.GET_AI_MESSAGES.toolName() + ".";
}
