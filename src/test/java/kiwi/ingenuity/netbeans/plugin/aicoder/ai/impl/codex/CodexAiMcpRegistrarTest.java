package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CodexAiMcpRegistrarTest {

    @Test
    void identityFieldsComeFromConstructor() {
        CodexAiMcpRegistrar reg = new CodexAiMcpRegistrar("sess-1");
        assertEquals("sess-1", reg.getSessionId());
        assertEquals(AiTypeEnum.CODEX, reg.getAiType());
    }

    @Test
    void addMcpEndpointCapturesUrlAndRemoveClearsIt() {
        CodexAiMcpRegistrar reg = new CodexAiMcpRegistrar("cap-test-session");
        assertNull(reg.getEndpointUrl(), "endpoint must be null before addMcpEndpoint is called");

        String endpoint = "http://127.0.0.1:1234/mcp/codex";
        reg.addMcpEndpoint(endpoint);
        assertEquals(endpoint, reg.getEndpointUrl(), "addMcpEndpoint must capture the URL verbatim");

        reg.removeMcpEndpoint();
        assertNull(reg.getEndpointUrl(), "removeMcpEndpoint must clear the captured URL");
    }

    // ---- Codex has no PreToolUse-style hook mechanism — registerHooks/unregisterHooks
    // are no-ops; actual MCP wiring happens per-spawn via -c in CodexAiProcessManager ----
    @Test
    void registerHooksIsNoOpAndReturnsTrue() {
        CodexAiMcpRegistrar reg = new CodexAiMcpRegistrar("hook-test-session");
        assertTrue(reg.registerHooks("http://127.0.0.1:1234"));
    }

    @Test
    void unregisterHooksIsSafeNoOp() {
        CodexAiMcpRegistrar reg = new CodexAiMcpRegistrar("hook-test-session");
        assertDoesNotThrow(reg::unregisterHooks);
    }
}
