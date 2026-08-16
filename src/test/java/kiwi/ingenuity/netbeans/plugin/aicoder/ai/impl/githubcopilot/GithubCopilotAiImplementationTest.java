package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotQuotaEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class GithubCopilotAiImplementationTest {

    @Test
    void publishQuota_broadcastsToEveryCopilotListener() throws Exception {
        CountDownLatch delivered = new CountDownLatch(2);
        GithubCopilotQuotaEvent quota = new GithubCopilotQuotaEvent(
                false, 12, 300, 96.0, "2026-09-01T00:00:00Z", false);
        AiPropertyListener first = event -> {
            assertEquals(quota, event);
            delivered.countDown();
        };
        AiPropertyListener second = event -> {
            assertEquals(quota, event);
            delivered.countDown();
        };
        AiTypePropertyBus bus = AiTypePropertyBus.getInstance();
        bus.addListener(AiTypeEnum.GitHubCoPilot, first);
        bus.addListener(AiTypeEnum.GitHubCoPilot, second);
        try {
            GithubCopilotAiImplementation.publishQuota(quota);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
        finally {
            bus.removeListener(AiTypeEnum.GitHubCoPilot, first);
            bus.removeListener(AiTypeEnum.GitHubCoPilot, second);
        }
    }
}
