# AI Coder user interface guide

This guide explains the NetBeans UI for creating and operating AI sessions.

For what the plugin is and how to install it, see the [README](README.md). For tool and settings details, see the [Reference](REFERENCE.md).

## Start here

1. Configure one or more backends in **Tools > Options > AI Coder**.
2. Open **Tools > AI Manager**.
3. Create a session, select its backend and project, then choose **Create & Open**.
4. Work in the session's dockable chat tab.
5. Accept or reject every proposed change in the NetBeans review panel.

## AI Manager

**Tools > AI Manager** is the session control centre.

It has four tabs: **Existing Sessions**, **Create Session**, **Templates**, and **Help** — the last of which includes generated documentation for the MCP tools.

| Action | Where | What it does |
|---|---|---|
| Create & Open | Create Session | Creates one or more named sessions using the selected backend, project, and initial settings, and opens each one. Use the **Count** spinner to create several at once. |
| Open | Existing Sessions | Opens a selected saved session and restores its available history and working directory. Only sessions whose project is currently open are listed. |
| Delete | Existing Sessions | Removes the saved session, its history, and its local backend session configuration. |

Both tabs offer a **Close after action** checkbox to dismiss the dialog once the action completes.

Session settings are changed from the session itself rather than from the AI Manager: open its chat tab and use the gear button to reach **Session Configuration**, which covers the session name, description, instructions, permissions, model, and backend options.

Each session has an independent backend, model, selected project, history, session instructions, permissions, and backend session/thread state. Multiple sessions, including different backends, can run concurrently.

## Creating a session

Choose a descriptive name, an open project, and a backend. The new session inherits global defaults unless you override settings in its configuration.

- Select **Claude**, **GitHub Copilot**, **Grok**, **OpenCode**, **Codex**, or enabled **Ollama (Local)**.
- Choose or enter a model where the backend permits it.
- For OpenCode, choose **Build** for normal work or **Plan** for a read-only proposal.
- Add session instructions when the session needs project-specific rules.
- Use project-file restriction and the permission controls to set the session's access boundary.

## Chat tabs and context

Opened sessions appear as dockable NetBeans chat tabs. The chat renders Markdown, code blocks, assistant status, tool activity, notifications, and backend information.

At initial delivery, the session supplies identity, open projects, and active-editor context. Later requests send changes where possible, while stateless backends receive the baseline required to work correctly. Context and transcript history are persisted independently when enabled.

Paste an image from the clipboard into chat when the selected backend supports image input.

### Searching open projects

When the assistant uses `SearchInFiles` without a `filePath`, it searches every open project's Java source roots (or a project root that has no registered Java roots). This makes project-wide search independent of which editor tab is active. Supplying a source file narrows the search to that file's source classpath; `filePattern`, case-sensitive matching, and regex matching are available when needed. See the [SearchInFiles reference](REFERENCE.md#searchinfiles) for parameter details.

## Templates

The AI Manager provides two reusable template types:

| Template | Purpose |
|---|---|
| Configuration template | Reuses common non-backend settings such as permissions, history, and UI-related session options. It does not overwrite backend credentials or selection. |
| Session-instruction template | Reuses prompts and operating rules without replacing the session's backend configuration. |

Built-in configuration templates include **Coordinator**, **CoderPeer**, and **ReviewerPeer**. Use them as starting points for a coordinating session, an implementation session, or a review-focused session.

## Instructions and session persistence

Special instructions can be sent automatically when a session starts or on the first user request. The UI records whether the selected delivery has occurred, avoiding unintended repetition.

When history saving is enabled, the plugin restores session definitions, conversation history, and recoverable context after the IDE restarts. Invalid saved content is ignored and rebuilt instead of preventing the session from opening.

## Change review

AI-proposed content writes are shown in the NetBeans diff review panel. Use **Accept** to apply the change or **Reject** to decline it. This panel is the confirmation step for `WriteFile`, `ApplyEdit`, and content-bearing `SaveFile` operations.

Copying, moving, and deleting files are confirmed as actions rather than diffs, because they change no content. Shell commands proposed by a backend are confirmed the same way, showing the command that would run. NetBeans refactoring tools retain their native reference-aware behaviour.

Two things to know about these prompts:

- **Auto-accept, in the info bar, approves all of them without asking** — content writes, file actions, and shell commands alike. It is off by default and can be set per session.
- **A prompt left unanswered expires after 120 seconds.** That is not a rejection: the backend is told it may retry, and the buttons stop responding. If a prompt appears to have gone dead, it timed out.

## Usage and notifications

Info bars may display backend-reported context and account usage:

- Claude rolling-limit gauges.
- GitHub Copilot usage gauge.
- Codex account rate-limit gauge, including used percentage and reset time.
- Context gauge showing used versus total active context window where a backend reports it.

If inter-AI messaging is enabled, the inbox can show notices for peer messages. Automatic notices and interruption for important messages are separately configurable.
