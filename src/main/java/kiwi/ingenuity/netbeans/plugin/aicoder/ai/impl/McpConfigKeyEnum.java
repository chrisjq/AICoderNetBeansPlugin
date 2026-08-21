package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl;

/**
 * JSON object key names written into the AI CLI hook/MCP config files by the
 * registrar implementations ({@code ~/.claude/settings.json} and
 * {@code ~/.grok/hooks/*.json}). These strings are an external contract read by
 * the Claude and Grok CLIs — the values must never change.
 */
public enum McpConfigKeyEnum {
    // Map of hook event name to its list of hook entries (top level of the config,
    // and repeated inside each entry to hold the actual hook objects)
    HOOKS("hooks"),
    // Hook event name: fires before Edit/Write tools run; array of hook entries
    PRE_TOOL_USE("PreToolUse"),
    // Tool-name pattern selecting which tool invocations trigger the entry's hooks
    MATCHER("matcher"),
    // Transport type of each hook object ("http")
    TYPE("type"),
    // Endpoint URL each HTTP hook posts to
    URL("url");

    private final String key;

    McpConfigKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
