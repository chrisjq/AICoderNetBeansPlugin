package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

/**
 * Claude PreToolUse hook payload and response keys. These are Claude's names and must NOT be aligned with
 * McpToolPropertyEnum's camelCase. Values are an external contract and must never change.
 */
public enum ClaudeHookKeyEnum {
    /**
     * Claude hook event discriminator.
     */
    HOOK_EVENT_NAME("hook_event_name"),
    /**
     * Claude session UUID.
     */
    SESSION_ID("session_id"),
    /**
     * Claude tool name.
     */
    TOOL_NAME("tool_name"),
    /**
     * Claude tool argument object.
     */
    TOOL_INPUT("tool_input"),
    /**
     * Claude Edit or Write target path.
     */
    FILE_PATH("file_path"),
    /**
     * Claude Edit source text.
     */
    OLD_STRING("old_string"),
    /**
     * Claude Edit replacement text.
     */
    NEW_STRING("new_string"),
    /**
     * Edit's "replace every occurrence" flag. Present in Claude's Edit schema, so it arrives on the wire whether or not
     * we read it — and ignoring it silently applied one replacement while reporting success for all of them.
     */
    REPLACE_ALL("replace_all"),
    /**
     * Claude Write content.
     */
    CONTENT("content"),
    /**
     * Claude hook response object.
     */
    HOOK_SPECIFIC_OUTPUT("hookSpecificOutput"),
    /**
     * Claude hook response event.
     */
    HOOK_EVENT_NAME_RESPONSE("hookEventName"),
    /**
     * Claude permission decision.
     */
    PERMISSION_DECISION("permissionDecision"),
    /**
     * Claude denial explanation.
     */
    PERMISSION_DECISION_REASON("permissionDecisionReason");

    private final String key;

    ClaudeHookKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
