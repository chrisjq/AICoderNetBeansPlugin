package kiwi.ingenuity.netbeans.plugin.aicoder.process;

/**
 * Composable flags controlling what appears in instruction text and tool
 * schemas. Always passed as a non-null {@code Set<McpInstructionOptionEnum>};
 * callers only use {@code options.contains(...)}.
 */
public enum McpInstructionOptionEnum {
    /**
     * Global/policy header prose.
     */
    HEADER,
    /**
     * Per-tool instruction lines and the tool's domain schema surface.
     */
    TOOL_INSTRUCTION,
    /**
     * Caller credentials: sessionId/secretKey in schemas and credential prose.
     */
    CREDENTIALS,
    /**
     * Is a AI that can only access these tools so shouldnt have any directive
     * about preferring MCP tools over the tools provided here.
     */
    ONLY_MCP_TOOL_ACCESS,
    /**
     * The model follows imperatives literally and will fire a tool on the first
     * turn if told to. Directives that ask for a proactive call ("call at
     * session start", "your first action is to call X") are replaced with
     * conditional phrasing that only triggers on an actual user request.
     * Independent of {@link #ONLY_MCP_TOOL_ACCESS}: a model can have no
     * built-in tools yet still follow conditionals correctly, and vice versa.
     */
    SOFTEN_TOOL_DIRECTIVES,
    /**
     * Advertise tools in the prompt text and take tool calls back through a
     * response schema, instead of populating the request's {@code tools} array.
     *
     * <p>Populating {@code tools} activates the model's tool-calling template,
     * and qwen2.5-coder then calls something on every turn regardless of the
     * request — given one irrelevant tool and the message "hi" it invented a
     * city and called it. The same tools described in prose carry the
     * information without the compulsion, and a {@code response_format} schema
     * makes the reply shape guaranteed rather than best-effort.
     */
    TOOL_CALLS_VIA_SCHEMA,
    /**
     * Each turn is sent as a fresh conversation, so nothing said on an earlier
     * turn is still in context.
     *
     * <p>Context that is normally sent once and then updated by deltas — open
     * projects, the active file — must be repeated in full every turn instead.
     * Without this the model receives the project paths on turn one and never
     * again, and then cannot resolve a file: asked to read pom.xml it invented
     * "/path/to/pom.xml", having no idea where the project lived.
     */
    STATELESS_TURNS,
    /**
     * Emit a strong, up-front directive to route every action through the MCP
     * tool server and never touch files with the agent's own built-in tools.
     *
     * <p>For agents that have their own file/shell tools and fall back to them
     * even after being told to prefer the plugin's — GitHub Copilot read a file
     * with its native {@code view} tool rather than GetFileContent. The wording
     * is what Copilot itself said would make it use the MCP tools. Appears in
     * the connection stub so it is seen before the first action.
     */
    FORCE_MCP_TOOL_USE
}
