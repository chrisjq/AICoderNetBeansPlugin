package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1;

import com.github.copilot.generated.rpc.SessionHistoryCompactResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotCompaction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class V1SessionCompactionTest {

    @Test
    void aNullCompactionResultReadsAsDidNotCompact() {
        CopilotCompaction outcome = V1Session.toCompaction(null);

        assertNull(outcome.success());
        assertNull(outcome.tokensRemoved());
        assertNull(outcome.messagesRemoved());
    }

    @Test
    void aCompactionResultCarriesItsCountsOver() {
        SessionHistoryCompactResult result = new SessionHistoryCompactResult(true, 1200L, 4L, "summary", null);

        CopilotCompaction outcome = V1Session.toCompaction(result);

        assertEquals(Boolean.TRUE, outcome.success());
        assertEquals(Long.valueOf(1200L), outcome.tokensRemoved());
        assertEquals(Long.valueOf(4L), outcome.messagesRemoved());
    }
}
