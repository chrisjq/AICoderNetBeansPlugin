package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

/**
 * How the calling AI asked for a build.
 */
public enum BuildTypeEnum {
    /**
     * Queued with {@code async: true}: the tool call returns at once and the result is delivered as a message when the
     * build finishes.
     */
    ASYNC,
    /**
     * Queued without {@code async}: the tool call waits for its turn and returns the result itself.
     */
    INLINE
}
