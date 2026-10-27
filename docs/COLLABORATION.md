[← Back to README](../README.md)

# Collaboration between sessions

See also: [UI guide](UI_GUIDE.md) · [Sessions](SESSIONS.md) · [Backends](BACKENDS.md) · How-to: [Set up an AI team](howto/AI_TEAM.md)

Sessions open in the same IDE can message each other. One assistant can delegate work to others, review their results and be told when a peer has gone quiet. [Settings](SETTINGS.md#general-and-session-settings) covers the default communication permissions.

## Switching it on

In **⚙ > Session Configuration > MCP Tools**, switch on the **Peer messaging** group and tick **Allow inter-AI communication**. Both are needed. Two settings sit beside them (defaults in [Settings](SETTINGS.md#general-and-session-settings)):

- **Auto-notify on incoming messages:** the session is told when mail arrives.
- **Allow important messages, interrupt this session:** a message may interrupt while it is working.

The **Idle watchers** group with **Allow idle AI watcher timer** lets a session be notified when a peer has been idle for a while.

## The tools

Every messaging and idle-watcher tool is in the [MCP tool reference](MCP_REFERENCE.md#collaboration-tools). How long inbox entries are kept is an Infrastructure setting ([Settings](SETTINGS.md#general-and-session-settings)).

## When a message is read

An ordinary message waits in the inbox until the recipient finishes its turn. With automatic notices on, the recipient is then told it has mail.

An **important** message is delivered sooner, but only if the recipient allows important messages and its backend can take a message while it is working. Otherwise it waits like any other. The peer-list tool says which backends deliver early, so a sender can see whether marking a message important will help.

| Backend | Important message while busy |
|---|---|
| [GitHub Copilot](ai/github_copilot/README.md) | Sent into the running turn and read without stopping it |
| [Codex](ai/codex/README.md) | Delivered into the running turn without stopping it |
| [OpenAI Compatible](ai/openai_compatible/README.md) | Read while it is running a turn, without stopping it |
| [Pi](ai/pi/README.md) | Read after the current tool calls finish, without interrupting the work |
| [Claude](ai/claude/README.md) | The turn is interrupted as the Stop button does, and any running tool call is cancelled. The plugin then explains that mail, not you, cut it short |
| [Grok](ai/grok/README.md), [OpenCode](ai/opencode/README.md) | The turn ends after any running tool call returns; the plugin then explains that mail cut it short |

## Roles

A common arrangement is a **Coordinator** that delegates, **CoderPeer** sessions that do the work and a **ReviewerPeer** that reviews it. The plugin ships matching config and instruction templates; [Templates](TEMPLATES.md#config-templates) lists the settings each one turns on.
