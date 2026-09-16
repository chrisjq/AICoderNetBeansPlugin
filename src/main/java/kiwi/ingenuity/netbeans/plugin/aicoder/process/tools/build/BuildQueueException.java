package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

/**
 * A build the queue refused, with a message for the calling AI.
 */
public class BuildQueueException extends Exception {

    public BuildQueueException(String message) {
        super(message);
    }
}
