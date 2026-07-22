package kiwi.ingenuity.netbeans.plugin.aicoder.process;

/**
 * Composable flags controlling what appears in instruction text and tool
 * schemas. Always passed as a non-null {@code Set<McpInstructionOptionEnum>};
 * callers only use {@code options.contains(...)}.
 */
public enum McpInstructionOptionEnum {
    /** Global/policy header prose. */
    HEADER,
    /** Per-tool instruction lines and the tool's domain schema surface. */
    TOOL_INSTRUCTION,
    /** Caller credentials: sessionId/secretKey in schemas and credential prose. */
    CREDENTIALS
}
