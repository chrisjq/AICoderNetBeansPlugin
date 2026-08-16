package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void estimateCountsContentAndToolArguments() {
        TokenEstimator est = new TokenEstimator();
        ChatMessage plain = new ChatMessage(ChatRole.USER, "a".repeat(35), List.of(), null);
        ChatMessage withCall = new ChatMessage(ChatRole.ASSISTANT, null,
                List.of(new ChatToolCall("c0", "Tool", "a".repeat(35))), null);

        assertTrue(est.estimate(plain) > 10);
        assertTrue(est.estimate(withCall) > 10,
                "tool-call arguments are payload too and must be counted");
    }

    @Test
    void calibrationMovesTheRatioTowardReportedUsage() {
        TokenEstimator est = new TokenEstimator();
        ChatMessage m = new ChatMessage(ChatRole.USER, "a".repeat(350), List.of(), null);
        int before = est.estimate(m);

        for (int i = 0; i < 10; i++) {
            est.calibrate(100, 200);
        }
        int after = est.estimate(m);

        assertTrue(after > before, "under-estimating endpoints must push the estimate up");
    }

    @Test
    void ratioIsClampedAgainstCumulativeUsageEndpoints() {
        TokenEstimator est = new TokenEstimator();
        for (int i = 0; i < 50; i++) {
            est.calibrate(100, 100000);
        }
        assertEquals(2.0d, est.calibrationRatio(), 0.0001d,
                "an endpoint reporting cumulative totals must not drive the ratio to nonsense");
    }

    @Test
    void absentUsageLeavesTheRatioAlone() {
        TokenEstimator est = new TokenEstimator();
        est.calibrate(100, null);
        assertEquals(1.0d, est.calibrationRatio(), 0.0001d);
        assertFalse(est.hasSeenReportedUsage());
    }

    @Test
    void seeingUsageOnceIsRemembered() {
        TokenEstimator est = new TokenEstimator();
        est.calibrate(100, 110);
        assertTrue(est.hasSeenReportedUsage(),
                "REPORTED_TOKENS may only be trusted after usage has actually arrived");
    }

    @Test
    void resetRestoresTheNeutralRatio() {
        TokenEstimator est = new TokenEstimator();
        for (int i = 0; i < 10; i++) {
            est.calibrate(100, 200);
        }
        est.reset();
        assertEquals(1.0d, est.calibrationRatio(), 0.0001d);
    }
}
