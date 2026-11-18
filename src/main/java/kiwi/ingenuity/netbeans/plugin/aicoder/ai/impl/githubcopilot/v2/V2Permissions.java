package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v2;

import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.PermissionInvocation;
import com.microsoft.copilot.rpc.PermissionRequest;
import com.microsoft.copilot.rpc.PermissionRequestResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionGate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionRequest;

/**
 * Translates permission requests without blocking the SDK reader thread.
 */
final class V2Permissions implements PermissionHandler {

    private final CopilotPermissionGate gate;

    V2Permissions(CopilotPermissionGate gate) {
        this.gate = Objects.requireNonNull(gate);
    }

    @Override
    public CompletableFuture<PermissionRequestResult> handle(
            PermissionRequest request, PermissionInvocation invocation) {
        try {
            return gate.decide(new CopilotPermissionRequest(request.getKind(),
                                                            request.getToolCallId(), request.getExtensionData())).thenApply(V2Permissions::map);
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
