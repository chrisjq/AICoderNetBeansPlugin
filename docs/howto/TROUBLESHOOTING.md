[← Back to README](../../README.md)

# Troubleshoot a session

Use the symptom that matches what you see.

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| **Test connection** reports **N/A** | The server cannot be reached. | Start the server, check **Base URL**, then choose **Test connection** again. |
| **API key rejected** | The configured key is not accepted. | Choose **Change** in the API-key area, enter a valid key, then test again. |
| **Tool not enabled for this session** | Its MCP tool group is off. | Open **⚙ > Session Configuration > MCP Tools** and enable its group. |
| A message such as **Git access is disabled for this session** | That permission is off. | Turn on that setting (for example, **Allow git access**) in **⚙ > Session Configuration**. |
| An enabled tool is still missing | The session has not restarted since tool settings changed. | Restart it; see [When a change takes effect](../MCP_TOOL_ACCESS.md#when-a-change-takes-effect). |
| The context warning says "Tools use ~N of W — apply Minimal" or "Window N too small for tools" | The tool set is too large for the context window. | See the [Minimal MCP template](../TEMPLATES.md#mcp-templates). |
| A build is refused because **At most 8 builds per project** are allowed | The project has reached its active-build limit. | Wait for a queued build to finish, or ask the assistant to cancel one of its async builds. |
| **MCP error -32001: Request timed out** on a long build or test | OpenCode's MCP timeout ended the tool call. | Raise it as described in [OpenCode](../ai/opencode/README.md#raising-the-mcp-tool-timeout). |
| A chat tab has a red marker or the session is not running | The CLI is not installed or you are not signed in, or, for OpenAI Compatible, the server is unreachable. | Use that backend's **Test** button in Options or **Test connection** for OpenAI Compatible, and see its backend page. |

Related: [OpenAI Compatible](../ai/openai_compatible/README.md) · [MCP tool access](../MCP_TOOL_ACCESS.md) · [MCP tool reference](../MCP_REFERENCE.md).
