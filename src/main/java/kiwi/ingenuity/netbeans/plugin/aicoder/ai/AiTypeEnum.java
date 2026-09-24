package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiSettingsCreator;
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
 * Enumerates available AI implementations and their configurations. Each type maintains a settings creator
 * for initializing and updating type-specific configurations.
 */
public enum AiTypeEnum {
    //                                                          implemented  enabledByDefault  openAiCompatible  mailDeliveryTiming
    // ClaudeAiProcessManager:444 sends control_request(interrupt) for Mail — byte-identical to
    // what Cancel sends, so the CLI cannot tell mail from the Stop button and ends the turn,
    // taking any in-flight tool call with it.
    // DENY_WITH_MESSAGE: the PreToolUse hook answers "deny" with a permissionDecisionReason, and
    // Claude Code documents that reason as shown to the model (it is withheld for "allow"/"ask").
    // The call is blocked but the turn carries on, so no follow-up turn is owed.
    CLAUDE("Claude", "claude", true, true, false, MailDeliveryTimingEnum.ABORTS_TURN, McpSteeringSupportEnum.DENY_WITH_MESSAGE, new ClaudeSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS), null, null),
    // GrokAiProcessManager:412 — "Mail IGNORED, no persistent session to inject into".
    // NOT_INTERCEPTABLE, not NOT_APPLICABLE: Grok DOES write a Claude-shaped PreToolUse hook
    // (GrokAiMcpRegistrar:80,87) so it is probably reachable — but it runs --always-approve and its
    // deny-reason semantics have never been verified. Deferred, not impossible.
    GROK("Grok", "grok", true, true, false, MailDeliveryTimingEnum.AFTER_TURN, McpSteeringSupportEnum.NOT_INTERCEPTABLE, new GrokSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS), null, null),
    // GithubCopilotProcessManager:562 injects via immediate-mode setPrompt, described in its own
    // javadoc as "instead of killing anything".
    // DENY_WITH_MESSAGE: PermissionRequestResult.reject(feedback) carries our text and the handler
    // already forwards decision.message() verbatim — this backend is where the pattern came from.
    GitHubCoPilot("GitHub CoPilot", "github_copilot", true, true, false, MailDeliveryTimingEnum.DURING_TURN, McpSteeringSupportEnum.DENY_WITH_MESSAGE, new GithubCopilotSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS, FORCE_MCP_TOOL_USE), null, null),
    // No TOOL_INSTRUCTION: under TOOL_CALLS_VIA_SCHEMA the tool list is rendered
    // into the prompt from the schemas, carrying names, parameters and
    // descriptions. The per-tool instruction lines describe the same tools
    // without the parameters, so enabling both only duplicated ~9k characters.
    //                                                          implemented  enabledByDefault  openAiCompatible  mailDeliveryTiming
    // OllamaAiProcessManager:701 — "Mail IGNORED, no channel to inject into".
    // NOT_APPLICABLE: ONLY_MCP_TOOL_ACCESS — this backend reaches the IDE through the in-process MCP
    // bridge and has no native file/shell tools of its own, so there is no such call to refuse.
    OLLAMA_LOCAL("Ollama (Local)", "ollama_local", true, false, true, MailDeliveryTimingEnum.AFTER_TURN, McpSteeringSupportEnum.NOT_APPLICABLE, new OllamaSettingsCreator(),
            Set.of(HEADER, ONLY_MCP_TOOL_ACCESS, SOFTEN_TOOL_DIRECTIVES, TOOL_CALLS_VIA_SCHEMA), null, null),
    // FORCE_MCP_TOOL_USE for the same reason as Copilot: OpenCode keeps its own
    // bash/grep/read/edit tools and reached for them first, shelling out to grep
    // on project files. The "use the plugin tools INSTEAD OF built-in
    // Read/Edit/Write/Bash/Grep" guidance is already sent, but it sits below the
    // GetInstructions preamble and was read past. The flag repeats it as the
    // first line instead.
    // DENY_NEEDS_FOLLOW_UP: the ACP reply to session/request_permission is an optionId and nothing
    // else (OpenCodeAcpClientHandler:741), so steering text cannot ride the refusal and must arrive
    // as an agent-only turn afterwards.
    OPENCODE("OpenCode", "opencode", true, true, false, MailDeliveryTimingEnum.ABORTS_TURN, McpSteeringSupportEnum.DENY_NEEDS_FOLLOW_UP, new OpenCodeSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS, FORCE_MCP_TOOL_USE),
            "Accept", "Reject, you may need to remind it to use MCP tool manually."),
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
    // DENY_NEEDS_FOLLOW_UP: approvalDecision maps a refusal to the bare string "decline"
    // (CodexAppServerHandler:208) with no room for text, so steering must arrive as a later turn.
    CODEX("Codex", "codex", true, true, false, MailDeliveryTimingEnum.DURING_TURN, McpSteeringSupportEnum.DENY_NEEDS_FOLLOW_UP, new CodexSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS), null, null),
    // PiAiProcessManager#interrupt(Mail) sends `steer` while a turn is running — pi queues it and delivers it once
    // the running tool call finishes, never aborting it (spec, verified live against pi 0.85.1).
    // DENY_WITH_MESSAGE: the generated per-session pi extension blocks a gated call with
    // {block:true, reason}, and that reason reaches the model — see reviewGatedCall in
    // aicoder-pi-extension.ts.template.
    PI("Pi", "pi", true, true, false, MailDeliveryTimingEnum.DURING_TURN, McpSteeringSupportEnum.DENY_WITH_MESSAGE, new PiSettingsCreator(), Set.of(HEADER, TOOL_INSTRUCTION, CREDENTIALS), null, null);

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
     * Whether this AI type communicates via the OpenAI-compatible HTTP API. True only for types whose session
     * settings extend OpenAiClientSessionSettings.
     */
    private final boolean openAiCompatible;
    /**
     * Creator responsible for instantiating and updating settings
     */
    private final AiSessionSettingsCreator settingCreator;
    /**
     * Controls what this AI type receives in instruction text and tool schemas. Types that reach the plugin
     * through a bridge which injects credentials server-side omit CREDENTIALS, so they are never shown
     * sessionId/secretKey.
     */
    private final Set<McpInstructionOptionEnum> mcpOptions;
    /**
     * When an inbox message reaches this backend if it is mid-turn, and at what cost. Decides both whether
     * marking a message important can do anything and whether the recipient needs telling afterwards that an
     * aborted tool call was not a user rejection.
     */
    private final MailDeliveryTimingEnum mailDeliveryTiming;
    /**
     * Whether this backend can be steered away from its own native tools, and if so whether the steering text
     * survives the refusal. Like {@link #mailDeliveryTiming} this is a property of the backend's wire
     * protocol, not a setting: it decides both whether the option is offered at all for this type and whether
     * a refusal must be followed by an agent-only turn to carry the text the refusal could not.
     */
    private final McpSteeringSupportEnum mcpSteeringSupport;
    /**
     * Tooltip for the confirm dialog's accept button, or {@code null} when this backend supplies none. A
     * tooltip that says nothing useful teaches the user to ignore them all, so a backend gets text only when
     * it has genuinely backend-specific advice for the moment of decision.
     */
    private final String confirmAcceptTooltip;
    /**
     * Tooltip for the confirm dialog's reject button, or {@code null} when this backend supplies none.
     */
    private final String confirmRejectTooltip;

    AiTypeEnum(String displayName, String key, boolean isImplemented, boolean enabledByDefault,
            boolean openAiCompatible, MailDeliveryTimingEnum mailDeliveryTiming,
            McpSteeringSupportEnum mcpSteeringSupport,
            AiSessionSettingsCreator settingCreator, Set<McpInstructionOptionEnum> options,
            String confirmAcceptTooltip, String confirmRejectTooltip) {
        this.displayName = displayName;
        this.key = key;
        this.implemented = isImplemented;
        this.enabledByDefault = enabledByDefault;
        this.openAiCompatible = openAiCompatible;
        this.mailDeliveryTiming = mailDeliveryTiming;
        this.mcpSteeringSupport = mcpSteeringSupport;
        this.settingCreator = settingCreator;
        this.mcpOptions = options;
        this.confirmAcceptTooltip = confirmAcceptTooltip;
        this.confirmRejectTooltip = confirmRejectTooltip;
    }

    /**
     * When an inbox message reaches this backend if it is mid-turn. Senders use this to tell whether an
     * important message can actually interrupt the target.
     */
    public MailDeliveryTimingEnum mailDeliveryTiming() {
        return mailDeliveryTiming;
    }

    /**
     * Exposes the backend's steering support status.
     */
    public McpSteeringSupportEnum mcpSteeringSupport() {
        return mcpSteeringSupport;
    }

    /**
     * Tooltip text for the confirm dialog's accept button for this backend, or {@code null} to show no
     * tooltip.
     */
    public String confirmAcceptTooltip() {
        return confirmAcceptTooltip;
    }

    /**
     * Tooltip text for the confirm dialog's reject button for this backend, or {@code null} to show no
     * tooltip.
     */
    public String confirmRejectTooltip() {
        return confirmRejectTooltip;
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
     * Returns true if this AI type communicates via the OpenAI-compatible HTTP API. When true, the session
     * settings will be an instance of OpenAiClientSessionSettings and context management controls apply.
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
