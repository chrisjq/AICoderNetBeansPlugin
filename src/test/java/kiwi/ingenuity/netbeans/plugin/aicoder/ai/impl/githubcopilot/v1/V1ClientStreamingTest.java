package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotPermissionGate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSessionSpec;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class V1ClientStreamingTest {

    private static CopilotSessionSpec spec() {
        CopilotPermissionGate gate = request -> new CompletableFuture<>();
        return new CopilotSessionSpec("session-1", "gpt-5", null, List.of(), Map.of(), gate, false);
    }

    @Test
    void createTurnsStreamingOn() {
        assertTrue(V1Client.createConfig(spec()).isStreaming());
    }

    @Test
    void resumeTurnsStreamingOn() {
        assertTrue(V1Client.resumeConfig(spec()).isStreaming());
    }
}
