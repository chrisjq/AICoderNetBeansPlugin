package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class GithubCopilotPermissionPolicyTest {

    private static final String MCP_SERVER_NAME = "aicoder-nb-ki-plugin";

    // ---- the shape Copilot actually sends, captured from a live session ----
    //
    //   kind=mcp
    //   extensionData={serverName=aicoder-nb-ki-plugin,
    //                  toolName=aicoder-nb-ki-plugin-GetFileContent,
    //                  toolTitle=GetFileContent, args={...}, readOnly=false}
    //
    // The server name is in extensionData, never in the kind. Matching the server
    // name against the kind could never hit, so every one of this plugin's own
    // tool calls prompted.
    private static Map<String, Object> mcpExtensionData(String serverName) {
        return Map.of("serverName", serverName,
                "toolName", serverName + "-GetFileContent",
                "toolTitle", "GetFileContent",
                "readOnly", false);
    }

    @Test
    void liveMcpKindWithOurServerNameInExtensionDataAutoApproves() {
        assertEquals(GithubCopilotPermissionPolicy.Category.MCP_OUR_SERVER,
                GithubCopilotPermissionPolicy.classify("mcp", MCP_SERVER_NAME,
                        mcpExtensionData(MCP_SERVER_NAME)),
                "this is the exact shape a live session sends; it must not prompt");
    }

    @Test
    void mcpKindFromAnotherServerStillPrompts() {
        assertEquals(GithubCopilotPermissionPolicy.Category.UNKNOWN,
                GithubCopilotPermissionPolicy.classify("mcp", MCP_SERVER_NAME,
                        mcpExtensionData("some-other-mcp-server")),
                "this plugin gates only its own tools, so another server's must still be asked about");
    }

    @Test
    void mcpKindWithNoExtensionDataPrompts() {
        assertEquals(GithubCopilotPermissionPolicy.Category.UNKNOWN,
                GithubCopilotPermissionPolicy.classify("mcp", MCP_SERVER_NAME, null),
                "no server name means it cannot be confirmed as ours — fail closed");
    }

    @Test
    void mcpServerNameMatchIsCaseInsensitive() {
        assertEquals(GithubCopilotPermissionPolicy.Category.MCP_OUR_SERVER,
                GithubCopilotPermissionPolicy.classify("MCP", MCP_SERVER_NAME,
                        mcpExtensionData(MCP_SERVER_NAME.toUpperCase(java.util.Locale.ROOT))));
    }

    // ---- panel labelling and credential masking ----
    @Test
    void toolNamePrefersTheToolTitleOverTheCategoryLabel() {
        assertEquals("SendAiMessage",
                GithubCopilotPermissionPolicy.describeToolName(
                        GithubCopilotPermissionPolicy.Category.UNKNOWN, "mcp",
                        Map.of("toolTitle", "SendAiMessage")),
                "a live panel read \"Unknown\" while the tool title sat unused in extensionData");
    }

    @Test
    void recognisedCategoriesKeepTheirCleanLabel() {
        assertEquals("Shell", GithubCopilotPermissionPolicy.describeToolName(
                GithubCopilotPermissionPolicy.Category.SHELL, "shell(echo)", Map.of()),
                "\"Shell\" reads better than the raw kind for a recognised category");
    }

    @Test
    void unrecognisedKindIsNotEchoedIntoTheToolNameSlot() {
        // The SDK declares no constants for the request-side kind — only the reply
        // side (PermissionRequestResultKind) is an enum — so an unfamiliar value is
        // an arbitrary server string. "Unknown" is more honest there than printing
        // it where a tool name belongs; the raw kind still appears in the text.
        assertEquals("Unknown", GithubCopilotPermissionPolicy.describeToolName(
                GithubCopilotPermissionPolicy.Category.UNKNOWN, "some-future-kind(thing)", Map.of()));
        assertEquals("Unknown", GithubCopilotPermissionPolicy.describeToolName(
                GithubCopilotPermissionPolicy.Category.UNKNOWN, null, null));
    }

    @Test
    void aRecognisedMcpRequestStillShowsItsToolTitle() {
        // The case that actually matters in practice: "SendAiMessage", not "Unknown".
        assertEquals("SendAiMessage", GithubCopilotPermissionPolicy.describeToolName(
                GithubCopilotPermissionPolicy.Category.UNKNOWN, "mcp",
                Map.of("toolTitle", "SendAiMessage")));
    }

    @Test
    void credentialsAreMaskedInTheDisplayText() {
        String text = GithubCopilotPermissionPolicy.describeRequest("mcp",
                Map.of("secretKey", "848473ea-a2ab-4802-a8a5-721015c9579e"));
        assertFalse(text.contains("848473ea"), "a session secret must never be rendered: " + text);
        assertTrue(text.contains("***"), text);
    }

    @Test
    void credentialsNestedInsideAnArgsMapAreAlsoMasked() {
        // The inter-AI tools pass secretKey inside args={...}, which is how one was
        // rendered in full to the confirm panel during a live run.
        String text = GithubCopilotPermissionPolicy.describeRequest("mcp",
                Map.of("args", "{sessionId=abc, secretKey=848473ea-a2ab-4802, targetSessionId=def}"));
        assertFalse(text.contains("848473ea"), "nested secret must be masked too: " + text);
        assertTrue(text.contains("secretKey=***"), text);
    }

    @Test
    void overlongValuesAreTruncated() {
        String text = GithubCopilotPermissionPolicy.describeRequest("mcp",
                Map.of("message", "x".repeat(1000)));
        assertTrue(text.length() < 600, "one huge argument must not swamp the panel: " + text.length());
        assertTrue(text.contains("…"), text);
    }

    @Test
    void nullOrBlankKindIsUnknown() {
        assertEquals(GithubCopilotPermissionPolicy.Category.UNKNOWN,
                GithubCopilotPermissionPolicy.classify(null, MCP_SERVER_NAME, null));
        assertEquals(GithubCopilotPermissionPolicy.Category.UNKNOWN,
                GithubCopilotPermissionPolicy.classify("   ", MCP_SERVER_NAME, null));
    }

    @Test
    void ourMcpServerKindAutoApprovesInBareForm() {
        assertEquals(GithubCopilotPermissionPolicy.Category.MCP_OUR_SERVER,
                GithubCopilotPermissionPolicy.classify(MCP_SERVER_NAME, MCP_SERVER_NAME, null));
    }

    @Test
    void ourMcpServerKindAutoApprovesInFullPatternForm() {
        assertEquals(GithubCopilotPermissionPolicy.Category.MCP_OUR_SERVER,
                GithubCopilotPermissionPolicy.classify(MCP_SERVER_NAME + "(GetFileContent)", MCP_SERVER_NAME, null));
    }

    @Test
    void ourMcpServerMatchIsCaseInsensitive() {
        assertEquals(GithubCopilotPermissionPolicy.Category.MCP_OUR_SERVER,
                GithubCopilotPermissionPolicy.classify(MCP_SERVER_NAME.toUpperCase(java.util.Locale.ROOT), MCP_SERVER_NAME, null));
    }

    @Test
    void bareShellKindRoutesToShell() {
        assertEquals(GithubCopilotPermissionPolicy.Category.SHELL,
                GithubCopilotPermissionPolicy.classify("shell", MCP_SERVER_NAME, null));
    }

    @Test
    void fullPatternShellKindRoutesToShellTheSameAsBareForm() {
        assertEquals(GithubCopilotPermissionPolicy.Category.SHELL,
                GithubCopilotPermissionPolicy.classify("shell(echo)", MCP_SERVER_NAME, null));
    }

    @Test
    void writeKindRoutesToWrite() {
        assertEquals(GithubCopilotPermissionPolicy.Category.WRITE,
                GithubCopilotPermissionPolicy.classify("write(/tmp/out.txt)", MCP_SERVER_NAME, null));
    }

    @Test
    void urlKindRoutesToUrl() {
        assertEquals(GithubCopilotPermissionPolicy.Category.URL,
                GithubCopilotPermissionPolicy.classify("url(example.com)", MCP_SERVER_NAME, null));
    }

    @Test
    void unrecognisedKindIsUnknownRatherThanGuessed() {
        assertEquals(GithubCopilotPermissionPolicy.Category.UNKNOWN,
                GithubCopilotPermissionPolicy.classify("some-future-kind(thing)", MCP_SERVER_NAME, null));
    }

    @Test
    void describeRequestIncludesKindAndExtensionData() {
        String text = GithubCopilotPermissionPolicy.describeRequest("shell(echo)", Map.of("command", "echo hi"));
        assertEquals("GitHub Copilot requests permission: shell(echo) — command=echo hi", text);
    }

    @Test
    void describeRequestHandlesMissingKindAndEmptyExtensionData() {
        assertEquals("GitHub Copilot requests permission: (unknown kind)",
                GithubCopilotPermissionPolicy.describeRequest(null, Map.of()));
        assertEquals("GitHub Copilot requests permission: (unknown kind)",
                GithubCopilotPermissionPolicy.describeRequest(null, null));
    }
}
