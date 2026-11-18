package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

/**
 * Carries a nullable quota snapshot without binding callers to an SDK model.
 */
public record CopilotQuotaSnapshot(Boolean unlimited, Long usedRequests,
                                   Long entitlementRequests, Double remainingPercentage,
                                   String resetDate) {

}
