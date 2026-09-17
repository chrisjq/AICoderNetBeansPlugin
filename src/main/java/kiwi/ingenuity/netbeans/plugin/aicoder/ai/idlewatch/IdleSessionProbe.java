package kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch;

/**
 * The window through which {@link IdleWatcherRegistry} reads live session state, so tests can substitute a fake instead
 * of the real {@code SessionRegistry}-backed probe.
 */
public interface IdleSessionProbe {

    /**
     * @return whether the session is still open
     */
    boolean isOpen(String sessionId);

    /**
     * @return whether the session is mid-turn right now
     */
    boolean isRunning(String sessionId);

    /**
     * The session's display name for human/AI-facing text without leaking the id, or the id itself when the session is
     * not open or has no name. Null-safe: never returns null.
     */
    String displayName(String sessionId);
}
