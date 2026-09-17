package kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch;

/**
 * Why an idle-watch delivery is happening.
 */
public enum IdleWatchEventEnum {

    /**
     * The target has now been continuously idle for the watcher's timeout.
     */
    IDLE,
    /**
     * The target session was closed. This is NEVER an idle event: the delivery text must make unmistakable that the
     * idle condition was not the reason for the notice, so a coordinator cannot mistake it for its target having gone
     * quiet.
     */
    TARGET_CLOSED;
}
