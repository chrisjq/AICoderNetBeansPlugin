package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSettingsCreator;
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
 * Enumerates available AI implementations and their configurations. Each type maintains a settings creator for
 * initializing and updating type-specific configurations.
 */
public enum AiTypeEnum {
    //                                                          implemented  enabledByDefault  openAiCompatible  mailDeliveryTiming
    // ClaudeAiProcessManager:444 sends control_request(interrupt) for Mail — byte-identical to
    // what Cancel sends, so the CLI cannot tell mail from the Stop button and ends the turn,
    // taking any in-flight tool call with it.
    CLAUDE("Claude", "claude", true, true, false, MailDeliveryTimingEnum.ABORTS_TURN, new ClaudeSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS)),
    // GrokAiProcessManager:412 — "Mail IGNORED, no persistent session to inject into".
    GROK("Grok", "grok", true, true, false, MailDeliveryTimingEnum.AFTER_TURN, new GrokSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS)),
    // GithubCopilotProcessManager:562 injects via immediate-mode setPrompt, described in its own
    // javadoc as "instead of killing anything".
    GitHubCoPilot("GitHub CoPilot", "github_copilot", true, true, false, MailDeliveryTimingEnum.DURING_TURN, new GithubCopilotSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS, FORCE_MCP_TOOL_USE)),
    // No TOOL_INSTRUCTION: under TOOL_CALLS_VIA_SCHEMA the tool list is rendered
    // into the prompt from the schemas, carrying names, parameters and
    // descriptions. The per-tool instruction lines describe the same tools
    // without the parameters, so enabling both only duplicated ~9k characters.
    //                                                          implemented  enabledByDefault  openAiCompatible  mailDeliveryTiming
    // OllamaAiProcessManager:701 — "Mail IGNORED, no channel to inject into".
    OLLAMA_LOCAL("Ollama (Local)", "ollama_local", true, false, true, MailDeliveryTimingEnum.AFTER_TURN, new OllamaSettingsCreator(),
            Set.of(HEADER, ONLY_MCP_TOOL_ACCESS, SOFTEN_TOOL_DIRECTIVES, TOOL_CALLS_VIA_SCHEMA)),
    // FORCE_MCP_TOOL_USE for the same reason as Copilot: OpenCode keeps its own
    // bash/grep/read/edit tools and reached for them first, shelling out to grep
    // on project files. The "use the plugin tools INSTEAD OF built-in
    // Read/Edit/Write/Bash/Grep" guidance is already sent, but it sits below the
    // GetInstructions preamble and was read past. The flag repeats it as the
    // first line instead.
    // OpenCodeAiProcessManager:757 — "Mail IGNORED (unimplemented)".
    OPENCODE("OpenCode", "opencode", true, true, false, MailDeliveryTimingEnum.AFTER_TURN, new OpenCodeSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS, FORCE_MCP_TOOL_USE)),
    // MCP IS registered now: CodexAiProcessManager.buildMcpConfigArgs passes
    // -c mcp_servers.<name>.url / .default_tools_approval_mode / .tool_timeout_sec at
    // spawn time, with CodexAiMcpRegistrar alongside it. So the options below are the
    // live set, not a placeholder.
    // Codex is NOT MCP-only: it keeps its own shell and patch tools and asks for them via
    // item/commandExecution/requestApproval and the file-change approval path
    // (CodexAppServerHandler), so it can bypass the plugin tools exactly as OpenCode can.
    // FORCE_MCP_TOOL_USE is nevertheless absent, and that is defensible: Codex's native tool
    // calls are already surfaced to the user. A file change raises a PermissionEvent and gets
    // the same Accept/Reject diff panel as the plugin's own ApplyEdit/WriteFile, and a command
    // raises a ConfirmEvent. So a native edit here is reviewed, whereas OpenCode's `sed` runs
    // through its own bash tool and bypasses the panel entirely — that asymmetry, not the
    // mere presence of built-in tools, is what earned OpenCode and Copilot the flag. Both got
    // it only after an OBSERVED bypass. Add it to Codex if one is ever seen here.
    // CodexAiProcessManager interjects via turn/steer and its javadoc states it "never escalates
    // to Cancel", so the turn survives.
    CODEX("Codex", "codex", true, true, false, MailDeliveryTimingEnum.DURING_TURN, new CodexSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS));

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
     * Whether this AI type communicates via the OpenAI-compatible HTTP API. True only for types whose session settings
     * extend OpenAiClientSessionSettings.
     */
    private final boolean openAiCompatible;
    /**
     * Creator responsible for instantiating and updating settings
     */
    private final AiSessionSettingsCreator settingCreator;
    /**
     * Controls what this AI type receives in instruction text and tool schemas. Types that reach the plugin through a
     * bridge which injects credentials server-side omit CREDENTIALS, so they are never shown sessionId/secretKey.
     */
    private final Set<McpInstructionOptionEnum> mcpOptions;
    /**
     * When an inbox message reaches this backend if it is mid-turn, and at what cost. Decides both whether marking a
     * message important can do anything and whether the recipient needs telling afterwards that an aborted tool call
     * was not a user rejection.
     */
    private final MailDeliveryTimingEnum mailDeliveryTiming;

    AiTypeEnum(String displayName, String key, boolean isImplemented, boolean enabledByDefault,
            boolean openAiCompatible, MailDeliveryTimingEnum mailDeliveryTiming,
            AiSessionSettingsCreator settingCreator, Set<McpInstructionOptionEnum> options) {
        this.displayName = displayName;
        this.key = key;
        this.implemented = isImplemented;
        this.enabledByDefault = enabledByDefault;
        this.openAiCompatible = openAiCompatible;
        this.mailDeliveryTiming = mailDeliveryTiming;
        this.settingCreator = settingCreator;
        this.mcpOptions = options;
    }

    /**
     * When an inbox message reaches this backend if it is mid-turn. Senders use this to tell whether an important
     * message can actually interrupt the target.
     */
    public MailDeliveryTimingEnum mailDeliveryTiming() {
        return mailDeliveryTiming;
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
     * Returns true if this AI type communicates via the OpenAI-compatible HTTP API. When true, the session settings
     * will be an instance of OpenAiClientSessionSettings and context management controls apply.
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
