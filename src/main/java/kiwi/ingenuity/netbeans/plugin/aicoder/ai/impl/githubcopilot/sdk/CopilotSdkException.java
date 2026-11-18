package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

/**
 * Reports a failure while starting or adapting a Copilot SDK.
 */
public class CopilotSdkException extends Exception {

    public CopilotSdkException(String message) {
        super(message);
    }

    public CopilotSdkException(String message, Throwable cause) {
        super(message, cause);
    }
}
