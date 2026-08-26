# AI Coder user interface guide

This guide explains the NetBeans UI for creating and operating AI sessions.

For what the plugin is and how to install it, see the [README](README.md). For tool and settings details, see the [Reference](REFERENCE.md).

## Start here

1. Enable and configure one or more backends in **Tools > Options > Miscellaneous > AI Coder** (the same panel is also registered under **Advanced**). Some backends, including Ollama (Local), are disabled until you enable them here and will not appear when creating a session.
2. Open **Tools > AI Manager**.
3. Create a session, select its backend and project, then choose **Create & Open**.
4. Work in the session's dockable chat tab.
5. Accept or reject every proposed change in the NetBeans review panel.

## AI Manager

**Tools > AI Manager** is the session control centre.

It has four tabs: **Existing Sessions**, **Create Session**, **Templates**, and **Help**. Help holds two sub-tabs: **MCP Tools**, with generated documentation for the available tools, and **About**, showing the plugin name, installed version, and the project homepage and this version's release page as clickable links.

| Action | Where | What it does |
|---|---|---|
| Create & Open | Create Session | Creates one or more named sessions using the selected backend, project, and initial settings, and opens each one. Use the **Count** spinner to create several at once. |
| Open | Existing Sessions | Opens a selected saved session and restores its available history and working directory. Every saved session is listed; one whose project is not currently open is greyed out, and opening it is refused until that project is open. |
| Delete | Existing Sessions | Removes the saved session, its history, and its local backend session configuration. |

Both tabs offer a **Close after action** checkbox to dismiss the dialog once the action completes.

Session settings are changed from the session itself rather than from the AI Manager: open its chat tab and use the gear button to reach **Session Configuration**, which covers the session name, description, instructions, permissions, model, and backend options.

Each session has an independent backend, model, selected project, history, session instructions, permissions, and backend session/thread state. Multiple sessions, including different backends, can run concurrently.

## Creating a session

Choose a descriptive name, an open project, and a backend. The new session inherits global defaults unless you override settings in its configuration.

- Select **Claude**, **GitHub CoPilot**, **Grok**, **OpenCode**, **Codex**, or enabled **Ollama (Local)**.
- Choose or enter a model where the backend permits it.
- For OpenCode, set **Mode** to `build` for normal work or `plan` for a read-only proposal.
- Add session instructions when the session needs project-specific rules.
- Use project-file restriction and the permission controls to set the session's access boundary.

## Chat tabs and context

Opened sessions appear as dockable NetBeans chat tabs. The chat renders Markdown, code blocks, assistant status, tool activity, notifications, and backend information.

Each chat tab carries a coloured status marker: green when the session is ready for input, orange while a turn is in flight, and red when the backend is not running or has failed. While a turn is in flight the info bar also shows a **■ Stop** button, which cancels the current response; it is hidden when there is nothing to cancel.

Above the Send button sit two controls: the **⚙** gear on the left opens Session Configuration, and the **⬇** toggle on the right controls auto-scrolling. Auto-scroll is on when a session opens and is not saved between sessions, so each tab starts following the conversation and each can be set independently — useful when watching one session stream while reading back through another.

Switching it off holds your position: new assistant text, system notices and inbox notifications arrive without moving the view. Switching it back on jumps to the latest message immediately. Two things ignore the setting: your own messages, because you pressed Send and expect to see them, and questions or Yes/No prompts, because those block the assistant until you answer and must not be hidden. The tooltip reports the current state as *Auto Scroll - Enabled* or *Auto Scroll - Disabled*.

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

- **Auto-accept, in the info bar, approves content writes and file actions without asking.** It is off by default and can be set per session. It does not cover everything: shell commands, and requests whose subject could not be identified, still prompt every time even with auto-accept on.
- **A prompt left unanswered expires after 120 seconds.** That is not a rejection: the backend is told it may retry, and the buttons stop responding. If a prompt appears to have gone dead, it timed out.

## Usage and notifications

Info bars may display backend-reported context and account usage:

- Claude rolling-limit gauges.
- GitHub Copilot usage gauge.
- Codex account rate-limit gauge, including used percentage and reset time.
- Context gauge showing used versus total active context window where a backend reports it.

If inter-AI messaging is enabled, the inbox can show notices for peer messages. Automatic notices and interruption for important messages are separately configurable. When a message that expects a reply is read for the first time, its contents also instruct the assistant to reply with `SendAiMessage`; that instruction is not repeated on later reads.

Enabling important-message interruption does not guarantee it happens: the backend must also have a way to reach a session mid-turn. Claude, Codex and GitHub Copilot do; Grok, Ollama and OpenCode do not, so their messages always wait for the current turn to finish. `ListAiSessions` reports the effective behaviour per session as `mailDelivery`, combining the setting and the backend, so an assistant can see whether marking a message important will achieve anything before it does so.
