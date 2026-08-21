package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpInstructionOptionsTest {

    @Test
    void cliHasAllThreeIncludingCredentials() {
        var o = AiTypeEnum.CLAUDE.getMcpOptions();
        assertTrue(o.contains(McpInstructionOptionEnum.HEADER));
        assertTrue(o.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION));
        assertTrue(o.contains(McpInstructionOptionEnum.CREDENTIALS));
    }

    @Test
    void apiBackendOmitsCredentialsAndDescribesToolsInProse() {
        var o = AiTypeEnum.OLLAMA_LOCAL.getMcpOptions();
        assertTrue(o.contains(McpInstructionOptionEnum.HEADER));
        assertFalse(o.contains(McpInstructionOptionEnum.CREDENTIALS),
                "credentials are injected by the bridge");
        assertTrue(o.contains(McpInstructionOptionEnum.TOOL_CALLS_VIA_SCHEMA),
                "populating the tools array makes this backend fire a tool every turn");
        assertFalse(o.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION),
                "SchemaToolCalls renders name, parameters and description, so the "
                + "per-tool lines would only duplicate it without adding parameters");
        assertTrue(o.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS));
        assertTrue(o.contains(McpInstructionOptionEnum.SOFTEN_TOOL_DIRECTIVES));
    }

    @Test
    void cliTypesKeepTheToolsArray() {
        for (AiTypeEnum type : new AiTypeEnum[]{AiTypeEnum.CLAUDE, AiTypeEnum.GROK,
            AiTypeEnum.GitHubCoPilot}) {
            assertFalse(type.getMcpOptions().contains(McpInstructionOptionEnum.TOOL_CALLS_VIA_SCHEMA),
                    type + " handles native tool calls and must keep the standard path");
        }
    }

    @Test
    void typesWithTheirOwnFileToolsAreForcedOntoTheMcpServer() {
        // Both keep native bash/read/edit tools that the plugin cannot withhold
        // over their transport, and both were observed reaching for them first —
        // Copilot for view/edit, OpenCode for a shell grep over project files.
        // FORCE_MCP_TOOL_USE puts the "never read or write files directly with
        // your own built-in tools" line first, above the GetInstructions
        // preamble that the equivalent guidance otherwise sits below.
        for (AiTypeEnum type : new AiTypeEnum[]{AiTypeEnum.GitHubCoPilot, AiTypeEnum.OPENCODE}) {
            assertTrue(type.getMcpOptions().contains(McpInstructionOptionEnum.FORCE_MCP_TOOL_USE),
                    type + " falls back to its own file tools without the leading directive");
        }
    }

    @Test
    void returnedSetsAreImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> AiTypeEnum.CLAUDE.getMcpOptions().add(McpInstructionOptionEnum.HEADER));
    }
}
