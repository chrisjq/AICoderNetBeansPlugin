package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionOptionsTest {

    private static final String CRED_SENTENCE
            = "Your session ID and secret key are in the \"Your session identity\" block each turn.";

    @Test
    void cliHeaderContainsCredentialSentence() {
        String h = McpHookServerUtil.getGlobalInstructionsHeader(AiTypeEnum.CLAUDE.getMcpOptions());
        assertTrue(h.contains(CRED_SENTENCE));
        assertTrue(h.contains("## Inter-AI Messaging"));
        assertTrue(h.contains("When a task needs"));
    }

    @Test
    void apiBackendHeaderOmitsCredentialSentenceButKeepsPolicy() {
        String h = McpHookServerUtil.getGlobalInstructionsHeader(AiTypeEnum.OLLAMA_LOCAL.getMcpOptions());
        assertFalse(h.contains(CRED_SENTENCE));
        assertTrue(h.contains("## Inter-AI Messaging"));
        assertFalse(h.contains("When a task needs"));
        assertTrue(h.contains("## MCP Tool Errors"));
    }

    @Test
    void legacyNoArgHeaderStillContainsCredentialSentence() {
        assertTrue(McpHookServerUtil.getGlobalInstructionsHeader(AiTypeEnum.CLAUDE.getMcpOptions()).contains(CRED_SENTENCE));
    }
}
