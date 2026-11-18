package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

/**
 * Represents the protocol-neutral response to a Copilot permission request.
 */
public sealed interface CopilotPermissionDecision {

    record ApproveOnce() implements CopilotPermissionDecision {
    }

    record Reject(String feedback) implements CopilotPermissionDecision {

    }

    record UserNotAvailable() implements CopilotPermissionDecision {
    }

    static CopilotPermissionDecision approveOnce() {
        return new ApproveOnce();
    }

    static CopilotPermissionDecision reject(String feedback) {
        return new Reject(feedback);
    }

    static CopilotPermissionDecision userNotAvailable() {
        return new UserNotAvailable();
    }
}
