package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

public class GithubCopilotQuotaEvent implements AiProcessImplEvent {

    private final boolean unlimited;
    private final long usedRequests;
    private final long entitlementRequests;
    private final double remainingPercentage;
    private final String resetDate;

    public GithubCopilotQuotaEvent(boolean unlimited, long usedRequests, long entitlementRequests,
            double remainingPercentage, String resetDate) {
        this.unlimited = unlimited;
        this.usedRequests = usedRequests;
        this.entitlementRequests = entitlementRequests;
        this.remainingPercentage = remainingPercentage;
        this.resetDate = resetDate;
    }

    public boolean unlimited() {
        return unlimited;
    }

    public long usedRequests() {
        return usedRequests;
    }

    public long entitlementRequests() {
        return entitlementRequests;
    }

    public double remainingPercentage() {
        return remainingPercentage;
    }

    public String resetDate() {
        return resetDate;
    }
}
