package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeTimeoutEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ClaudePersistentSessionEnvironmentTest {

    @Test
    void configureMcpToolTimeoutSuppliesDefaultWhenUnset() {
        ProcessBuilder processBuilder = new ProcessBuilder(List.of("placeholder"));
        processBuilder.environment().remove("MCP_TOOL_TIMEOUT");

        ClaudePersistentSession.configureMcpToolTimeout(processBuilder);

        assertEquals(Long.toString(ClaudeTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis()),
                processBuilder.environment().get("MCP_TOOL_TIMEOUT"));
    }

    @Test
    void configureMcpToolTimeoutPreservesUserSuppliedValue() {
        ProcessBuilder processBuilder = new ProcessBuilder(List.of("placeholder"));
        processBuilder.environment().put("MCP_TOOL_TIMEOUT", "59000");

        ClaudePersistentSession.configureMcpToolTimeout(processBuilder);

        assertEquals("59000", processBuilder.environment().get("MCP_TOOL_TIMEOUT"));
    }
}
