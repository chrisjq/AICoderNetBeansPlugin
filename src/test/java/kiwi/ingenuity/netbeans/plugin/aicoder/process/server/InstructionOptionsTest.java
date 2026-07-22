package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionOptionsTest {
    private static final String CRED_SENTENCE =
            "Your session ID and secret key are in the \"Your session identity\" block each turn.";

    @Test
    void cliHeaderContainsCredentialSentence() {
        String h = McpHookServerUtil.getGlobalInstructionsHeader(McpInstructionOptions.cli());
        assertTrue(h.contains(CRED_SENTENCE));
        assertTrue(h.contains("## Inter-AI Messaging"));
        assertTrue(h.contains("asking permission. Your session ID and secret key"));
    }

    @Test
    void apiBackendHeaderOmitsCredentialSentenceButKeepsPolicy() {
        String h = McpHookServerUtil.getGlobalInstructionsHeader(McpInstructionOptions.apiBackend());
        assertFalse(h.contains(CRED_SENTENCE));
        assertTrue(h.contains("## Inter-AI Messaging"));
        assertTrue(h.contains("asking permission.\nWhen a task needs"));
        assertTrue(h.contains("## MCP Tool Errors"));
    }

    @Test
    void legacyNoArgHeaderStillContainsCredentialSentence() {
        assertTrue(McpHookServerUtil.getGlobalInstructionsHeader().contains(CRED_SENTENCE));
    }
}
