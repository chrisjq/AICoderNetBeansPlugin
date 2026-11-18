package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v2;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class V2CopilotSdkTest {

    @Test
    void theSpawnedRuntimeGetsNoAutoUpdateBeforeTheCallersArgs() {
        assertEquals(List.of("--no-auto-update", "--log-level", "debug"),
                     V2CopilotSdk.spawnArgs(List.of("--log-level", "debug")));
    }

    @Test
    void noExtraArgsStillGetsNoAutoUpdate() {
        assertEquals(List.of("--no-auto-update"), V2CopilotSdk.spawnArgs(null));
    }

    @Test
    void aTimeoutWithNoMessageReportsItsClassNameNotNull() {
        assertEquals("java.util.concurrent.TimeoutException", V2CopilotSdk.messageOf(new TimeoutException()));
    }

    @Test
    void theDeepestCauseMessageIsReported() {
        assertEquals("root", V2CopilotSdk.messageOf(new ExecutionException("wrapper",
                                                                           new IllegalStateException("root"))));
    }
}
