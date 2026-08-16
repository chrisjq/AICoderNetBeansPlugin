package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

public class GithubCopilotQuotaEvent implements AiProcessImplEvent, AiPropertyEvent {

    private final boolean unlimited;
    private final long usedRequests;
    private final long entitlementRequests;
    private final double remainingPercentage;
    private final String resetDate;
    private final boolean showResetDate;

    public GithubCopilotQuotaEvent(boolean unlimited, long usedRequests, long entitlementRequests,
            double remainingPercentage, String resetDate, boolean showResetDate) {
        this.unlimited = unlimited;
        this.usedRequests = usedRequests;
        this.entitlementRequests = entitlementRequests;
        this.remainingPercentage = remainingPercentage;
        this.resetDate = resetDate;
        this.showResetDate = showResetDate;
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

    public boolean showResetDate() {
        return showResetDate;
    }

}
