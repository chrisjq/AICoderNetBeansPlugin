package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class V1CopilotSdkMessageTest {

    @Test
    void aTimeoutWithNoMessageReportsItsClassNameNotNull() {
        assertEquals("java.util.concurrent.TimeoutException", V1CopilotSdk.messageOf(new TimeoutException()));
    }

    @Test
    void theDeepestCauseMessageIsReported() {
        assertEquals("root", V1CopilotSdk.messageOf(new ExecutionException("wrapper",
                                                                           new IllegalStateException("root"))));
    }
}
