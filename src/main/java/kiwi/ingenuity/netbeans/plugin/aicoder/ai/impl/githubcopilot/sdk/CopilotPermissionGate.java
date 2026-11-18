package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.util.concurrent.CompletableFuture;

/**
 * Lets the backend policy decide a permission request independently of SDK APIs.
 */
public interface CopilotPermissionGate {

    CompletableFuture<CopilotPermissionDecision> decide(CopilotPermissionRequest request);
}
