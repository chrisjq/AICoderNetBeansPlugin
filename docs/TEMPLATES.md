[← Back to README](../README.md)

# Templates

See also: [Settings](SETTINGS.md)

The plugin keeps three kinds of reusable template. All are edited under **Tools > AI Manager > Templates**, in the tabs **MCP Templates**, **Config Templates** and **Session Instructions**. Each editor lists the stored templates (Name, Updated, Created) and has **Save**, **Cancel** and **Delete** buttons; deleting asks "Delete template?". Built-in entries can be edited but never deleted, so their Delete button is hidden.

| Template kind | What it holds | Built-in entries |
|---|---|---|
| Config template | Generic session settings: conversation, permissions, behaviour switches | **Default**, **Coordinator**, **CoderPeer**, **ReviewerPeer** |
| MCP template | Which MCP tool groups are on or off | **Default**, **Minimal** |
| Session instruction template | A named block of session instructions | **Coordinator**, **CoderPeer**, **ReviewerPeer** |

A config template never carries the backend, model, credentials or session instructions. An MCP template carries only group switches.

## Config templates

A config template snapshots shared session values: **Max history**, save history, project-files restriction, inter-AI messaging, inbox notices, important messages, idle watcher, **Auto-deny unsupported tool calls**, **Auto-Accept**, web and database permissions, Git read and write, clipboard access and the backend debug log.

**Default** is seeded from the plugin's global defaults when the store is created, and re-seeded if missing. It is editable and is what new sessions start from unless another template is chosen. The three role templates are created once. Their differences from the global defaults are:

| Template | Settings |
|---|---|
| Coordinator | Inter-AI messaging on, with inbox notices |
| CoderPeer | As Coordinator, but web requests off |
| ReviewerPeer | As Coordinator, but Git **Write off**: a reviewer reads history, diffs and blame and never commits, resets or pushes |

Applying a config template copies every value into the session's settings. The session's backend and backend-specific settings are untouched.

## MCP templates

An MCP template records which switchable tool groups are **off**; everything else is on. Its editor has one checkbox per group, under the categories Files, Code, Editor, Build, Git, External, Collaboration and Memory (OpenAI Compatible only). The Build queue group has no checkbox: it is derived and shown as "Build queue (derived: on/off)". A new template starts from the Default's current switches. Groups and tools are in [MCP tool access](MCP_TOOL_ACCESS.md#groups-and-categories).

Two templates are built in and seeded into every store whenever they are missing. An existing one is never overwritten.

- **Default** starts from the groups implied by the plugin's global permission defaults, by the rule in [Where the switches come from](MCP_TOOL_ACCESS.md#where-the-switches-come-from). Edit it to change what new sessions get.
- **Minimal** has only **File reading**, **File editing** and **Text search** on. `GetInstructions` stays available because the Always on group has no switch. Apply it when a model's context window is too small for the full tool list; the [OpenAI Compatible](ai/openai_compatible/README.md#info-bar) info bar says so. In the template pickers the built-in one is labelled "Minimal (built-in)", so a template of your own called Minimal stays distinguishable.

A template store that fails to parse is moved aside as `.corrupt-<timestamp>` and a fresh one is seeded. A store that merely cannot be read is left alone, and an in-memory Default is used until it can be read.

## Session instruction templates

A session instruction template is a name and a body of text. Choosing one in Create Session puts its body into the session's instructions ([Sessions](SESSIONS.md#session-instructions)). The three built-ins are plain text you can edit:

- **Coordinator:** Finds peers, delegates tasks, checks results and reports outcomes; does not commit.
- **CoderPeer:** Waits for tasks from a peer, completes them and replies with the result; does not commit.
- **ReviewerPeer:** Waits for review tasks from a peer and replies with verified findings; does not commit.

The full text is editable under Templates > Session Instructions.

With the matching config templates they give a coordinating, an implementation and a review-only session that can message each other ([Collaboration](COLLABORATION.md#roles)).

## Choosing templates when creating a session

The **Create Session** tab of the AI Manager ([UI guide](UI_GUIDE.md#ai-manager)) has three combos:

- **Config Template:** Default first, then the rest by name. Required; Default is used if nothing else is picked.
- **MCP Template:** Default first, Minimal (built-in) second, then the rest by name. Default is preselected; Minimal never is.
- **Session Instructions:** **None** first, then templates by name. **Inject Session Instructions on Create** decides when the chosen one is delivered ([Sessions](SESSIONS.md#session-instructions)).

**Create & Open** copies the config template's values into each new session, sets the MCP template's switches as the session's own tool configuration, and stores the chosen instructions. Two templates can share a name; the combos then number them.

## Changing a session afterwards

The **MCP Tools** panel of Session Configuration holds the per-session group switches, the settings under each group and an **Apply MCP template…** button. The button lists templates in the picker's order. If the chosen template differs from the current switches, it asks "Applying "<name>" replaces the tool switches set in this dialog. Continue?" first. Whether the running backend needs a restart is in [When a change takes effect](MCP_TOOL_ACCESS.md#when-a-change-takes-effect).

Templates are stored with lock files, separately from sessions; see [what a session holds](SESSIONS.md#what-a-session-holds). Editing a template never changes sessions created from it.
