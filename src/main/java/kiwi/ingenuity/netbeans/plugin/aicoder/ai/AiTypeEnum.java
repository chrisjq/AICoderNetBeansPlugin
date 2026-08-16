package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum.CREDENTIALS;
import static kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum.FORCE_MCP_TOOL_USE;
import static kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum.HEADER;
import static kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS;
import static kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum.SOFTEN_TOOL_DIRECTIVES;
import static kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum.TOOL_CALLS_VIA_SCHEMA;
import static kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum.TOOL_INSTRUCTION;

/**
 * Enumerates available AI implementations and their configurations. Each type
 * maintains a settings creator for initializing and updating type-specific
 * configurations.
 */
public enum AiTypeEnum {
    //                                                          implemented  enabledByDefault  openAiCompatible
    CLAUDE("Claude", "claude", true, true, false, new ClaudeSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS)),
    GROK("Grok", "grok", true, true, false, new GrokSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS)),
    GitHubCoPilot("GitHub CoPilot", "github_copilot", true, true, false, new GithubCopilotSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS, FORCE_MCP_TOOL_USE)),
    // No TOOL_INSTRUCTION: under TOOL_CALLS_VIA_SCHEMA the tool list is rendered
    // into the prompt from the schemas, carrying names, parameters and
    // descriptions. The per-tool instruction lines describe the same tools
    // without the parameters, so enabling both only duplicated ~9k characters.
    //                                                          implemented  enabledByDefault  openAiCompatible
    OLLAMA_LOCAL("Ollama (Local)", "ollama_local", true, false, true, new OllamaSettingsCreator(),
            Set.of(HEADER, ONLY_MCP_TOOL_ACCESS, SOFTEN_TOOL_DIRECTIVES, TOOL_CALLS_VIA_SCHEMA));

    public static AiTypeEnum fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (AiTypeEnum v : values()) {
            if (v.key().equals(key)) {
                return v;
            }
        }
        return null;
    }

    /**
     * User-facing display name for the AI type
     */
    private final String displayName;
    /**
     * Internal configuration key identifier
     */
    private final String key;
    /**
     * Whether this AI type is enabled by default
     */
    private final boolean enabledByDefault;
    /**
     * Whether this AI type has been implemented
     */
    private final boolean implemented;
    /**
     * Whether this AI type communicates via the OpenAI-compatible HTTP API.
     * True only for types whose session settings extend
     * OpenAiClientSessionSettings.
     */
    private final boolean openAiCompatible;
    /**
     * Creator responsible for instantiating and updating settings
     */
    private final AiSessionSettingsCreator settingCreator;
    /**
     * Controls what this AI type receives in instruction text and tool schemas.
     * Types that reach the plugin through a bridge which injects credentials
     * server-side omit CREDENTIALS, so they are never shown
     * sessionId/secretKey.
     */
    private final Set<McpInstructionOptionEnum> mcpOptions;

    AiTypeEnum(String displayName, String key, boolean isImplemented, boolean enabledByDefault,
            boolean openAiCompatible, AiSessionSettingsCreator settingCreator, Set<McpInstructionOptionEnum> options) {
        this.displayName = displayName;
        this.key = key;
        this.implemented = isImplemented;
        this.enabledByDefault = enabledByDefault;
        this.openAiCompatible = openAiCompatible;
        this.settingCreator = settingCreator;
        this.mcpOptions = options;
    }

    /**
     * Gets the user-facing display name.
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Gets the internal configuration key.
     */
    public String key() {
        return key;
    }

    /**
     * Checks if this AI type is enabled by default.
     */
    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    /**
     * Checks if this AI type has been implemented.
     */
    public boolean isImplemented() {
        return implemented;
    }

    /**
     * Returns true if this AI type communicates via the OpenAI-compatible HTTP
     * API. When true, the session settings will be an instance of
     * OpenAiClientSessionSettings and context management controls apply.
     */
    public boolean isOpenAiCompatible() {
        return openAiCompatible;
    }

    /**
     * Creates default settings for this AI type.
     *
     * @return a new AiSessionSettings instance configured for this AI type
     */
    public AiSessionSettings createDefaultSettings() {
        return settingCreator.create();
    }

    /**
     * Returns the settings creator for this AI type.
     *
     * @return the AiSessionSettingsCreator responsible for managing settings
     */
    public AiSessionSettingsCreator getSettingsCreator() {
        return settingCreator;
    }

    public Set<McpInstructionOptionEnum> getMcpOptions() {
        return mcpOptions;
    }
}
