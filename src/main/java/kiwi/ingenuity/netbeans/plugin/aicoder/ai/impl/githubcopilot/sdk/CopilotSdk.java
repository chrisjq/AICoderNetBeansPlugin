package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

/**
 * Starts one protocol-specific Copilot SDK without exposing it to the backend.
 */
public interface CopilotSdk {

    CopilotSdkClient start(CopilotLaunch launch) throws CopilotSdkException;

    String label();
}
