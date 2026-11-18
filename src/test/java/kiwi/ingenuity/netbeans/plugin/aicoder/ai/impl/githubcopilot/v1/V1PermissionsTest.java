package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1;

import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionRequest;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class V1PermissionsTest {

    @Test
    void mapsAllDecisions() {
        assertEquals(PermissionRequestResult.approveOnce().getKind(),
                     handle(CopilotPermissionDecision.approveOnce()).getKind());
        PermissionRequestResult rejected = handle(CopilotPermissionDecision.reject("keep this feedback"));
        assertEquals(PermissionRequestResult.reject("keep this feedback").getKind(), rejected.getKind());
        assertEquals("keep this feedback", rejected.getFeedback());
        assertEquals(PermissionRequestResult.userNotAvailable().getKind(),
                     handle(CopilotPermissionDecision.userNotAvailable()).getKind());
    }

    private PermissionRequestResult handle(CopilotPermissionDecision decision) {
        return new V1Permissions(request -> CompletableFuture.completedFuture(decision))
                .handle(new PermissionRequest(), null).join();
    }

    @Test
    void preservesRequestAndDoesNotBlockForDecision() {
        AtomicReference<CopilotPermissionRequest> captured = new AtomicReference<>();
        CompletableFuture<CopilotPermissionDecision> pending = new CompletableFuture<>();
        V1Permissions handler = new V1Permissions(request -> {
            captured.set(request);
            return pending;
        });
        PermissionRequest request = new PermissionRequest();
        request.setKind("shell");
        request.setToolCallId("call-1");
        request.setExtensionData(Map.of("command", "pwd"));
        CompletableFuture<PermissionRequestResult> result = handler.handle(request, null);
        assertFalse(result.isDone());
        assertEquals(new CopilotPermissionRequest("shell", "call-1", Map.of("command", "pwd")), captured.get());
        pending.complete(CopilotPermissionDecision.approveOnce());
        assertEquals(PermissionRequestResult.approveOnce().getKind(), result.join().getKind());
    }

    @Test
    void gateFailureCannotApprove() {
        IllegalStateException failure = new IllegalStateException("unavailable");
        CompletableFuture<PermissionRequestResult> result = new V1Permissions(request -> {
            throw failure;
        }).handle(new PermissionRequest(), null);
        assertTrue(result.isCompletedExceptionally());
    }
}
