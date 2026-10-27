[← Back to README](../README.md)

# Backends

See also: [MCP tool access](MCP_TOOL_ACCESS.md) · [Sessions](SESSIONS.md)

Every session runs on one backend. All backends share the chat, IDE context, MCP tools, change review and inter-AI messaging. They differ in how they connect and sign in, what they call their effort setting, and how they treat their own tools and incoming messages.

| Backend | Connection | Page |
|---|---|---|
| Claude | Background `claude` CLI session | [ai/claude](ai/claude/README.md) |
| GitHub Copilot | `copilot` CLI session through the Copilot SDK | [ai/github_copilot](ai/github_copilot/README.md) |
| Grok | Background `grok` CLI agent session | [ai/grok](ai/grok/README.md) |
| Codex | Background Codex app-server session | [ai/codex](ai/codex/README.md) |
| Pi | Background `pi` CLI session | [ai/pi](ai/pi/README.md) |
| OpenCode | Background `opencode` CLI agent session | [ai/opencode](ai/opencode/README.md) |
| OpenAI Compatible | HTTP to an OpenAI-compatible endpoint; no CLI | [ai/openai_compatible](ai/openai_compatible/README.md) |

Setup, sign-in, model and effort options, gauges and limits are on each backend's page. Differences that span backends are compared here:

- **A backend's own shell and file tools** under **Auto-Accept** and **Auto-deny unsupported tool calls**: [Backends with their own tools](CHANGE_REVIEW.md#backends-with-their-own-tools).
- **When a busy session reads an important message:** [When a message is read](COLLABORATION.md#when-a-message-is-read).
- **Tools registered for some backends only:** [`AskUserQuestion`](MCP_REFERENCE.md#askuserquestion) and the [memory tools](MCP_REFERENCE.md#memory-tools).

## Effort and thinking

Backends that support it have a thinking or reasoning-effort picker in their Options tab, in Create Session and in the info bar. Each backend names it differently (effort, reasoning effort, thinking level). The same rules apply to all:

- **The first entry means "don't set it."** It omits the setting, so the model or CLI uses its own default. Pi's `off` is a real level, not this entry ([Pi](ai/pi/README.md#model-and-effort)).
- **A level is never sent to a model that does not support it.** Where the backend lists levels per model, only those are offered. If the model turns out not to support a stored level, it is cleared and reported once as a message — but only after the model list has loaded, so a slow load never clears it.
- **The global default is never changed automatically.** A session whose model cannot take the default runs without it.
- **When a change takes effect** is on each backend's page: some restart the session, some send it with each request, some apply it at once.

## Where a backend's settings are

Each backend has a tab in the AI Coder options ([Settings](SETTINGS.md)) with the executable field and its **Browse…**, **Auto-detect** and **Test** buttons, the default model, and the default effort or thinking level where there is one. OpenAI Compatible has no executable. A session's own values are set in Create Session and Session Configuration ([UI guide](UI_GUIDE.md#session-configuration)). Each backend's page lists what its tab holds.
