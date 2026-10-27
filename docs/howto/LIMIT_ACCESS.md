[← Back to README](../../README.md)

# Limit what a session can touch

Give a session only the project and tools it needs.

1. Open the session and choose **⚙ > Session Configuration**.
2. Keep **Restrict to open project files** enabled unless the task needs another location.
3. Open **MCP Tools** and switch off groups the session does not need, or choose **Apply MCP template…** and select **Minimal (built-in)** ([details](../TEMPLATES.md#mcp-templates)).
4. Untick **Allow git commands that modify the repository** for read-only Git access, or **Allow git access** for none.
5. Untick **Allow web requests** (on by default) unless the task needs it; tick **Allow database access** and **Allow clipboard access** only when needed.
6. If a newly enabled tool is not available, restart the session; see [When a change takes effect](../MCP_TOOL_ACCESS.md#when-a-change-takes-effect).

Related: [Settings](../SETTINGS.md) · [MCP tool access](../MCP_TOOL_ACCESS.md).
