package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1;

import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionGate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionRequest;

/**
 * Translates permission requests without blocking the SDK reader thread.
 */
final class V1Permissions implements PermissionHandler {

    private final CopilotPermissionGate gate;

    V1Permissions(CopilotPermissionGate gate) {
        this.gate = Objects.requireNonNull(gate);
    }

    @Override
    public CompletableFuture<PermissionRequestResult> handle(
            PermissionRequest request, PermissionInvocation invocation) {
        try {
            return gate.decide(new CopilotPermissionRequest(request.getKind(),
                                                            request.getToolCallId(), request.getExtensionData())).thenApply(V1Permissions::map);
        }
        catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    static PermissionRequestResult map(CopilotPermissionDecision decision) {
        Objects.requireNonNull(decision);
        if (decision instanceof CopilotPermissionDecision.ApproveOnce) {
            return PermissionRequestResult.approveOnce();
        }
        if (decision instanceof CopilotPermissionDecision.Reject rejected) {
            return PermissionRequestResult.reject(rejected.feedback());
        }
        return PermissionRequestResult.userNotAvailable();
    }
}
