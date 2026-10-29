[← Back to README](../README.md)

# AI Coder settings

See also: [MCP tool reference](MCP_REFERENCE.md) · [Templates](TEMPLATES.md) · [OpenAI Compatible](ai/openai_compatible/README.md) · [OpenAI Compatible advanced settings](ai/openai_compatible/ADVANCED.md) · How-to: [Limit session access](howto/LIMIT_ACCESS.md)

Global defaults are at **Tools > Options > Miscellaneous > AI Coder** (also under **Tools > Options > Advanced**). The panel has a General tab and one tab per backend; a backend you untick does not appear when creating a session. Each backend's tab is described on its page, reached from [Backends](BACKENDS.md).

New sessions start from the **Default** config template ([Templates](TEMPLATES.md#config-templates)); the values here seed it and are the fallback for anything a session leaves unset.

A session can override each applicable option in its **Session Configuration** ([UI guide](UI_GUIDE.md#session-configuration)). An option left unset inherits the global value.

## General and session settings

| Area | Settings |
|---|---|
| Conversation | **Max history**, save history, chat font size, diff-context lines. |
| Session instructions | Optional instructions; delivery is in [Sessions](SESSIONS.md#session-instructions). |
| Project scope | **Restrict to open project files**. On by default. |
| Change review | Per-session **Auto-Accept**, diff presentation and **Auto-deny unsupported tool calls**. See [Change review](CHANGE_REVIEW.md). |
| Inter-AI | **Allow inter-AI communication**, **Auto-notify on incoming messages**, **Allow important messages, interrupt this session**, and **Allow idle AI watcher timer**. Messaging and notices are off by default; **Allow important messages, interrupt this session** and the idle watcher timer are on. What each does: [Collaboration](COLLABORATION.md#switching-it-on). |
| Web requests | Master switch, per-method permissions, headers, bodies, localhost and private-network destinations. On by default, GET only. See [below](#permissions-in-detail). |
| Database | Master switch, read-only sub-permissions, row limit. See [below](#permissions-in-detail). |
| Git access | **Allow git access**, **Allow read-only git commands**, and **Allow git commands that modify the repository**. See [below](#permissions-in-detail). |
| Clipboard | **Allow clipboard access**. Off by default. |
| MCP tools | Which tool groups a session may use, per session or via an MCP template ([MCP tool access](MCP_TOOL_ACCESS.md)). |
| Backend debug log | Per session or in a config template; off by default.<br>Writes everything sent to and received from the backend to `backend-debug.log` in the [session folder](SESSIONS.md#what-a-session-holds). It may contain project source, but API keys and the MCP secret are redacted.<br>**Open log folder** opens it and shows the size.<br>It rolls over at 10 MB, keeping one `.1` file. OpenAI Compatible can change these limits in its [advanced settings](ai/openai_compatible/ADVANCED.md#plugin-wide-debug-log). |
| Infrastructure | MCP loopback port, terminal command override (leave empty for the system default; use `{dir}` and `{command}` placeholders), save-on-close prompting ([Sessions](SESSIONS.md#closing-a-tab)), inbox retention and size (defaults 60 minutes and 1,000 messages), debug JSON/context logging, tool-use logging. |

## Permissions in detail

**Web requests.** GET is allowed by default when web access is on. Methods that change remote state, custom headers and request bodies are off by default; enable them globally or per session. Destinations are controlled separately: loopback, link-local, private, site-local, carrier-grade NAT, IPv6 unique-local, multicast and any-local addresses are refused, on the entered URL and on every redirect hop. Two options relax this, both off by default:

- **Allow localhost destinations:** loopback and any-local addresses, for a local model or dev server. This also makes the plugin's own tool server reachable over HTTP, though a request still cannot authenticate.
- **Allow private network destinations:** private, site-local, link-local, carrier-grade NAT and IPv6 unique-local addresses. This includes `169.254.169.254`, the cloud metadata endpoint, which matters on a cloud VM.

Multicast is never allowed. A refusal names the setting that would allow it.

**Git access** is on by default, with **Allow read-only git commands** and **Allow git commands that modify the repository** both on. With **Allow git commands that modify the repository** off a session can inspect the repository but not change it. Which tool needs which permission is the Git read / Git write split in [MCP tool access](MCP_TOOL_ACCESS.md#groups-and-categories). A refusal names the setting that would allow it.

**Database access** is opt-in and read-only. A query must be a single SELECT; anything after a `;` is refused. The JDBC connection is set read-only, which some drivers treat only as a hint. The row limit (default 25) is enforced. Queries share the IDE's connection, run one at a time and are cut off after five minutes.
