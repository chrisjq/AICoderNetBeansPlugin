[← Back to README](../README.md)

# Change review and safety

See also: [UI guide](UI_GUIDE.md) · [MCP tool reference](MCP_REFERENCE.md) · [Backends](BACKENDS.md)

> **The plugin provides gates, not guarantees.** It checks what passes through its own tools and approvals, and limits what it can in each backend. That is not the same as making an assistant safe. Backends are real processes with your credentials and file access. Read what you approve, and keep your work under version control so an unwanted change is recoverable.

## The diff panel

`WriteFile` and `ApplyEdit` open in the NetBeans diff panel, as does `SaveFile` when it creates a new file. Nothing is saved until you press **Accept**; **Reject** declines. Refactorings use the NetBeans refactoring APIs, so references stay consistent and no diff is shown.

Your unsaved editor changes are saved before the prompt appears, so you keep them whether you accept or reject. The diff is built from the file on disk and does not include those edits. The assistant is told when a save happened, and the tool refuses rather than discard changes it cannot save. Reading a file saves the same way, so the assistant sees what you see.

## Confirmations

`CopyFile`, `MoveFile`, `DeleteFile` and `DeleteDirectory` have no content diff. They are confirmed in the chat with **Yes** and **No** buttons showing what would happen. Shell commands a backend proposes are confirmed the same way, showing the command. `AskUserQuestion` asks its questions in the same place.

Diff and **Yes**/**No** prompts left unanswered expire after 120 seconds. `AskUserQuestion` waits up to 300 seconds. Neither is a rejection: the backend is told it may retry and the buttons stop responding.

## Auto-Accept

**Auto-Accept** approves content writes and file actions without asking and reports them in the chat history afterwards. It is off by default and is set per session: in the info bar, in **⚙ > Session Configuration**, or from a **Config Template** when the session is created. It never approves a request whose subject the plugin could not identify. It does not approve shell commands on its own; those are governed by **Auto-deny unsupported tool calls**, below.

## Backends with their own tools

Some backends have shell and file tools beside the plugin's. **Auto-deny unsupported tool calls** (on by default) refuses those calls in favour of the plugin's tools and tells the model why. It is never auto-approved. How the refusal reaches the model depends on the backend:

| Backend | Steering on | Steering off |
|---|---|---|
| [Claude](ai/claude/README.md) | The reason reaches the model at once; it carries on in the same turn | Shell commands are not prompted through the plugin |
| [GitHub Copilot](ai/github_copilot/README.md) | The reason reaches the model at once; it carries on in the same turn | Always asks before a shell command |
| [Grok](ai/grok/README.md) | Refused without explanation, then the plugin sends a follow-up telling it to use the plugin's tool (its protocol cannot carry text) | Always asks before a shell command |
| [OpenCode](ai/opencode/README.md) | Refused without explanation, then the plugin sends a follow-up; the buttons read **Accept** and **Reject, you may need to remind it to use MCP tool manually.** | Always asks before a shell command |
| [Codex](ai/codex/README.md) | Refused with a bare "decline", then the plugin sends a follow-up | Command approvals are auto-approved when **Auto-Accept** is on; file changes still use the diff panel |
| [Pi](ai/pi/README.md) | The reason reaches the model at once; it carries on in the same turn | `bash` is not gated; `edit` and `write` use the diff panel |
| [OpenAI Compatible](ai/openai_compatible/README.md) | Not applicable: the model reaches the IDE only through the plugin's tools | — |

## The tool server

The local MCP tool server binds only to loopback addresses, and every call is authenticated per session, so sessions cannot act as each other. What a session may touch (project scope, database, clipboard, web, Git) is set per session; see [Settings](SETTINGS.md).
