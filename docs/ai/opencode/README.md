[← Back to README](../../../README.md)

# OpenCode backend

See also: [Backends](../../BACKENDS.md) · [Change review](../../CHANGE_REVIEW.md) · [Collaboration](../../COLLABORATION.md)

OpenCode runs the `opencode` CLI and its agent process for each session.

## Setup

- Install the [OpenCode](https://opencode.ai) CLI and log in to a provider with OpenCode's own tooling.
- The **OpenCode** tab of the AI Coder options holds the [standard backend fields](../../BACKENDS.md#where-a-backends-settings-are), without a default effort, plus the **Default mode** for new sessions.
- Raise OpenCode's MCP tool timeout before using long-running tools (see [below](#raising-the-mcp-tool-timeout)).

## Model and effort

| Option | Where | Notes |
|---|---|---|
| Model | Everywhere a model is chosen | Editable; the list is discovered from the running agent and remembered |
| Effort | Info bar only (no default) | The levels the agent reports for the model; a change applies immediately |
| Mode | Create Session, Session Configuration | **Build** for normal agent work, **Plan** for read-only planning |

## Tools

The plugin passes its MCP server to the agent when the ACP (Agent Client Protocol) session is created, so the IDE tools are available from the first prompt ([backend tools](../../CHANGE_REVIEW.md#backends-with-their-own-tools)). The session's MCP credentials are delivered in the prompt instructions, which also tell OpenCode to prefer the plugin's tools over its own shell. Nothing needs configuring for the tools to appear.

## Raising the MCP tool timeout

OpenCode applies its own timeout to MCP tool calls and ends longer ones with `MCP error -32001: Request timed out`. MCP calls are also subject to the plugin's [lock waits](../../MCP_REFERENCE.md#concurrency-and-limits). Tools that run for minutes, such as full test runs, clean builds ([limits](../../MCP_REFERENCE.md#build-tools)) or a prompt waiting on your approval, fail against the default.

The plugin does not change OpenCode's timeout. Add it to `~/.config/opencode/opencode.json` (or `opencode.jsonc`):

```json
{
  "experimental": {
    "mcp_timeout": 3600000
  }
}
```

**The value is in milliseconds**; the example is one hour.
