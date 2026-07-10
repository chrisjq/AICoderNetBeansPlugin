package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

public record QuotaInfo(
        boolean unlimited,
        long usedRequests,
        long entitlementRequests,
        double remainingPercentage,
        String resetDate) {
}
