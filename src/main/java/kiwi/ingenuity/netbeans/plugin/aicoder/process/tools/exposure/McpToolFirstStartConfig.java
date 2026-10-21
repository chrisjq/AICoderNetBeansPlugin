package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Initializes legacy-derived MCP exposure exactly once, before a backend receives a session.
 */
public final class McpToolFirstStartConfig {

    private McpToolFirstStartConfig() {
    }

    /**
     * @return true if an absent configuration was initialized.
     */
    public static boolean initialize(AiSessionSettings settings) {
        if (settings.mcpToolConfig() != null) {
            return false;
        }
        settings.setMcpToolConfig(LegacyToolGroups.derive(settings));
        return true;
    }
}
