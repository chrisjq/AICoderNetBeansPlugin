[← Back to README](../README.md)

# AI Coder user interface guide

This guide describes the NetBeans UI for creating and operating sessions. The first steps are in [Getting started](GETTING_STARTED.md); the options are in [Settings](SETTINGS.md).

## AI Manager

**Tools > AI Manager** is the session control centre. It has four tabs: **Existing Sessions**, **Create Session**, **Templates** ([Templates](TEMPLATES.md)) and **Help**. Help has two sub-tabs:

- **MCP Tools** provides generated documentation for the available IDE tools; see the [MCP tool reference](MCP_REFERENCE.md).
- **About** shows the plugin name, installed version, and links to the project homepage and this version's release page.

| Action | Where | What it does |
|---|---|---|
| Create & Open | Create Session | Creates and opens one or more sessions with the chosen backend, project, templates and settings. The **Count** spinner creates several identical sessions named `<name>_1`, `<name>_2`, … |
| Open | Existing Sessions | Opens a saved session and restores its history and working directory. Every saved session is listed; one whose project is not open is greyed out and cannot be opened until it is. |
| Delete | Existing Sessions | After you confirm, removes the session ([what is removed](SESSIONS.md#creating-opening-deleting)). |

**Existing Sessions** and **Create Session** each have a **Close after action** checkbox that closes the dialog when the action completes.

### Session Configuration

Session settings are changed from the session itself. Open its chat tab and use the **⚙** gear above the Send button to reach **Session Configuration**: name, description, instructions, permissions, MCP tool groups, model and backend options. An unset option inherits its global default ([Settings](SETTINGS.md)).

## Creating a session

Choose a name, an open project and an **AI type** (the backend) ([Backends](BACKENDS.md)). Choose or type a model where the backend allows it, and set its effort level if it has one ([Effort and thinking](BACKENDS.md#effort-and-thinking)). Pick the templates the session starts from ([Templates](TEMPLATES.md#choosing-templates-when-creating-a-session)). Add session instructions for project-specific rules ([Sessions](SESSIONS.md#session-instructions)). Use project-file restriction and the permission controls to set the session's access boundary ([Settings](SETTINGS.md)).

## Chat tabs and context

Sessions open as dockable chat tabs. The chat renders Markdown, code blocks, status, tool activity, notifications and backend information. Closing a tab asks what to do with the session ([Sessions](SESSIONS.md#closing-a-tab)). A turn is one exchange: from your message until the assistant finishes. Each tab has a status marker:

| Colour | Meaning |
|---|---|
| Green | Ready for input |
| Orange | The backend is busy — a turn, or work it runs itself such as compacting |
| White | Waiting for your answer to a confirmation, question or diff |
| Red | The backend is not running or has failed |

The info bar above the input shows:

- the session timer;
- the model and effort pickers, where the backend has them ([Effort and thinking](BACKENDS.md#effort-and-thinking));
- usage and context gauges, where the backend reports them (which ones is on each backend's page);
- **⇒ Compact**, where the backend can summarise its conversation to free context (OpenAI Compatible adds more buttons, on [its page](ai/openai_compatible/README.md#info-bar));
- **Auto-Accept** ([Change review](CHANGE_REVIEW.md#auto-accept)) and **Save** (keep the chat history);
- **■ Stop** while a turn is in flight, which cancels the current response. It is hidden when there is nothing to cancel.

Above the Send button, the **⬇** toggle controls auto-scrolling. Auto-scroll is set per tab and is not remembered between IDE restarts.

- On when a session opens.
- Off holds your position while new assistant text, notices and inbox notifications arrive.
- On jumps to the latest message.

Your own messages and questions or Yes/No prompts always show, because you expect to see them and they block the assistant. The tooltip says *Auto Scroll - Enabled* or *Auto Scroll - Disabled*.

You can paste a clipboard image into the chat when the backend supports image input. [What the assistant is told](SESSIONS.md#what-the-assistant-is-told) covers IDE context; [History and model context](SESSIONS.md#history-and-model-context) covers saved conversation and context.

## Review prompts in the chat

AI-proposed content writes open the NetBeans diff panel with **Accept** and **Reject**. File actions, backend shell commands and `AskUserQuestion` choices appear inline in the chat with buttons. What each covers, how long it waits, and what **Auto-Accept** and **Auto-deny unsupported tool calls** change are in [Change review](CHANGE_REVIEW.md).

## Notifications

With inter-AI messaging enabled, the chat shows notices for peer messages, interruptions by important messages and idle-watcher alerts ([Collaboration](COLLABORATION.md)). When a background build finishes, its result arrives as a system message ([Build tools](MCP_REFERENCE.md#build-tools)).
