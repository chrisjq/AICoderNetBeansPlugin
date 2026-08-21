# AI Coder for NetBeans

> **Version compatibility:** Version 1.2.21 supports Claude, GitHub Copilot, and Grok only. Current releases also support Ollama (Local), OpenCode, and Codex.

AI Coder is a NetBeans IDE plugin that provides dockable, multi-session AI coding chats with IDE-aware context, project-scoped tools, configurable permissions, and reviewable file changes. It can work with local, CLI-based, SDK-based, ACP, app-server, and OpenAI-compatible backends through one shared chat and tool experience.

## Supported backends

| Backend | Connection | Configuration | Default status |
|---|---|---|---|
| [**Claude**](https://code.claude.com/docs/en/overview) | Long-lived `claude` CLI stream session | Executable and model | Enabled |
| [**GitHub Copilot**](https://docs.github.com/en/copilot/how-tos/copilot-cli/cli-getting-started) | Copilot SDK session | Executable and model | Enabled |
| [**Grok**](https://docs.x.ai/build/overview) | Headless `grok` CLI prompt sessions | Executable and model | Enabled |
| [**OpenCode**](https://opencode.ai/docs) | Long-lived `opencode acp` session | Executable, editable/discovered model, and Build or Plan agent mode | Enabled |
| [**Codex**](https://developers.openai.com/codex/cli/) | Long-lived Codex app-server session | Executable, editable model, reasoning effort, and sandbox/approval options | Enabled |
| [**Ollama (Local)**](https://docs.ollama.com/cli) | OpenAI-compatible HTTP API | Base URL, editable/discovered model, and context-management options | Implemented; enable in Options |

Each session has its own backend, model, settings, working project, chat history, session instructions, and optional persisted backend session/thread state. Multiple sessions and backends can run at the same time, though their file-, build- and Git-changing work is serialised across the whole plugin — see [Concurrency and limits](REFERENCE.md#concurrency-and-limits).

## What it provides

- Streaming Markdown chat with syntax-highlighted code, tool activity, status messages, and dockable session tabs.
- Paste clipboard images into chat when the selected backend supports image input.
- AI Manager for creating, opening, and deleting sessions, with reusable configuration and instruction templates.
- Per-session session instructions, with delivery on the first user request or automatically at startup.
- Reusable configuration templates and instruction templates; built-in configuration templates include Coordinator, CoderPeer, and ReviewerPeer.
- IDE context delivery: open projects, active file, session identity, and later project/file changes are supplied to the assistant. OpenAI-compatible sessions also support managed conversation context.
- Persistent sessions, conversation history, and context recovery across IDE restarts when enabled.
- Shared account usage gauges where a backend reports them: Claude rolling limits, GitHub Copilot quota, and Codex account rate limits. Context gauges show the active context against the backend-reported window where available.
- Inter-AI messaging between opted-in sessions, including inbox notifications and important-message interruption controls.
- NetBeans-aware search, navigation, diagnostics, formatting, build, test, refactoring, VCS, file, database, and web-request tools.
- Diff review for AI-proposed content writes, and explicit confirmation for destructive or location-changing file actions.

## Requirements

- NetBeans IDE 22 or newer.
- Java 17 or newer.
- Maven to build this plugin and to use its Maven build/test tools; Gradle or Ant when using their corresponding tools.
- At least one configured backend:
  - **Claude:** the `claude` CLI, authenticated with `claude login`.
  - **GitHub Copilot:** the `copilot` CLI and a Copilot-enabled GitHub account.
  - **Grok:** the `grok` CLI, authenticated with `grok login`.
  - **OpenCode:** the `opencode` CLI.
  - **Codex:** the `codex` CLI/app-server, authenticated with `codex login`.
  - **Ollama (Local):** a reachable OpenAI-compatible Ollama endpoint; no CLI is required by the plugin.

> NetBeans must be able to launch configured CLIs and use loopback networking. Sandboxed installations that block process creation, the host `PATH`, or local HTTP connections can prevent CLI/ACP/app-server backends and the MCP tool server from working.

## Installation

Build the NetBeans module:

```bash
mvn package
```

Install the generated `.nbm` from `target/` using **Tools > Plugins > Downloaded > Add Plugins**.

## Getting started

1. Configure one or more backend tabs in **Tools > Options > AI Coder**.
2. Open **Tools > AI Manager**.
3. Create a session, choose its backend and project, then select **Create & Open**.
4. Use the dockable chat tab to ask for analysis, changes, tests, or IDE operations.
5. Review every proposed content diff in the NetBeans Accept/Reject panel before it is saved — unless you enable Auto-accept, which applies changes without asking. See [Change review and safety](#change-review-and-safety).

You can set session-specific options from its configuration UI. An unset session option inherits its global default. For step-by-step use of the AI Manager, session tabs, reviews, and templates, see the [UI guide](UI_GUIDE.md).

## Configuration

### Global and per-session options

The General options tab establishes defaults. Sessions can override the following controls:

| Area | Controls |
|---|---|
| Conversation | Maximum history, save history, chat font size, and diff-context lines |
| Project scope | Restrict file access to session project directories |
| Change review | Auto-accept policy and diff presentation |
| Session instructions | Session instructions and startup/first-request delivery behavior |
| Inter-AI | Enable inter-AI messaging, automatic inbox notices, and important-message interruption |
| Web requests | Master switch plus independent permissions for GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, request headers, and request bodies |
| Database | Master switch, read-only sub-permissions, and database row limit |
| Clipboard | Explicit opt-in for clipboard reads |
| Infrastructure | MCP loopback port, save-session-on-close prompt behavior, inbox retention/size, debug JSON, debug context, and tool-use logging |

The default posture restricts file tools to project directories, disables auto-accept and clipboard access, and disables inter-AI messaging and automatic inbox notices. Important-message interruption is enabled when messaging is enabled. Database access is disabled by default with a row limit of 25; inbox entries are retained for 60 minutes with a maximum of 1,000 entries.

Web requests allow GET by default when web access is enabled. Methods that can change remote state, custom headers, and request bodies are disabled by default and must be enabled globally or for the session.

Database access is opt-in and read-only. A query must be a single SELECT — anything chained after a `;` is refused — and the JDBC connection is set read-only while it runs, which some drivers treat only as a hint. The configured row limit is enforced. Queries share the IDE's own connection, so they run one at a time and are cut off after five minutes rather than holding it indefinitely.

### Backend options

Backend tabs supply executable locations and default backend settings. Session settings preserve the selected backend-specific configuration.

| Backend | Notable options |
|---|---|
| Claude | CLI executable and model; models are discovered and cached when available |
| GitHub Copilot | CLI executable and SDK-discovered model list with fallback choices |
| Grok | CLI executable and discovered/fallback model list |
| OpenCode | CLI executable, model, and ACP-provided agent/mode configuration |
| Codex | CLI executable, model, reasoning effort, and app-server session options |
| Ollama (Local) | OpenAI-compatible base URL (default `http://localhost:11434`), model, context window, and context-management settings |

OpenCode’s mode is **Build** for normal agent work or **Plan** for read-only planning. Codex provides known model choices including `gpt-5.6-terra`, `gpt-5.6-luna`, `gpt-5.5`, and `gpt-5.4-mini`, while keeping the model field editable. Ollama needs no API key; its model and base URL can be changed for an individual session.

For OpenAI-compatible sessions, context management can trim by message count, estimated tokens, or reported tokens. Available strategies are no trimming, dropping older messages, dropping marked messages, or summarising; configure the trigger threshold, post-trim target, message limit, and context persistence in the Ollama/OpenAI context settings.

## Sessions, history, and context

Session definitions are saved in the NetBeans user area and include their name, description, backend-specific settings, associated project, timestamps, and instruction-delivery state. Opening a saved session restores its recorded history and working directory where valid. Corrupt history/context data is ignored and rebuilt rather than blocking a session.

On initial delivery, the assistant receives session identity, open project locations, and active-editor context. Later requests generally contain only changes to the active file/project state; stateless backends receive the required baseline again. Saved history and model-facing context are independent, allowing the chat transcript and backend context to recover safely.

## AI Manager and templates

**Tools > AI Manager** lets you create, open, and delete sessions; session settings are changed afterwards from the session's own gear button. A session can be associated with an open project and can use a reusable configuration template. Configuration templates preserve common non-backend settings; session-instruction templates provide reusable prompts without overwriting backend selection or credentials.

Deleting a session removes its saved history and associated local backend configuration for that session.

## MCP and IDE tool reference

The local MCP server exposes the following NetBeans-aware capabilities to compatible backends. Tool availability is also filtered by backend support and the session permission settings. For the complete tool list, usage rules, permission model, and backend/session settings, see the [tool and settings reference](REFERENCE.md).

### Build and test

| Tool | Description |
|---|---|
| [`BuildProject`](REFERENCE.md#buildproject), [`CleanProject`](REFERENCE.md#cleanproject), [`CleanAndBuildProject`](REFERENCE.md#cleanandbuildproject) | Invoke NetBeans build actions for the active project type |
| [`BuildMavenProject`](REFERENCE.md#buildmavenproject), [`CleanAndBuildMavenProject`](REFERENCE.md#cleanandbuildmavenproject), [`RunMavenTests`](REFERENCE.md#runmaventests) | Maven package/clean/test operations |
| [`BuildGradleProject`](REFERENCE.md#buildgradleproject), [`RunGradleTests`](REFERENCE.md#rungradletests) | Gradle build and test operations |
| [`BuildAntProject`](REFERENCE.md#buildantproject), [`RunAntTests`](REFERENCE.md#runanttests) | Ant build and test operations |
| [`DownloadMavenSources`](REFERENCE.md#downloadmavensources), [`DownloadMavenJavadoc`](REFERENCE.md#downloadmavenjavadoc) | Download dependency sources or Javadoc |

### Search, code intelligence, and refactoring

| Tool | Description |
|---|---|
| [`SearchInFiles`](REFERENCE.md#searchinfiles), [`SearchTypes`](REFERENCE.md#searchtypes), [`SearchSymbols`](REFERENCE.md#searchsymbols) | IDE-aware text, type, and member search; `SearchInFiles` searches all open projects when no file is supplied |
| [`FindDeclaration`](REFERENCE.md#finddeclaration), [`FindImplementations`](REFERENCE.md#findimplementations), [`FindUsages`](REFERENCE.md#findusages) | Navigate relationships in Java source |
| [`GetProjectStructure`](REFERENCE.md#getprojectstructure), [`GetClassMembers`](REFERENCE.md#getclassmembers), [`GetTypeHierarchy`](REFERENCE.md#gettypehierarchy), [`GetJavadoc`](REFERENCE.md#getjavadoc) | Inspect project and classpath information |
| [`RenameSymbol`](REFERENCE.md#renamesymbol), [`MoveClass`](REFERENCE.md#moveclass), [`MoveFile`](REFERENCE.md#movefile), [`InlineVariable`](REFERENCE.md#inlinevariable), [`ChangeMethodSignature`](REFERENCE.md#changemethodsignature) | IDE refactorings that update references where applicable |
| [`GetDiagnostics`](REFERENCE.md#getdiagnostics), [`NavigateToLine`](REFERENCE.md#navigatetoline), [`FixImports`](REFERENCE.md#fiximports), [`OrganiseImports`](REFERENCE.md#organiseimports), [`OrganiseMembers`](REFERENCE.md#organisemembers), [`ReformatFile`](REFERENCE.md#reformatfile) | Diagnostics, navigation, and source maintenance |

### Files, VCS, and system access

| Tool | Description |
|---|---|
| [`GetFileContent`](REFERENCE.md#getfilecontent), [`GetFileSizeAndMeta`](REFERENCE.md#getfilesizeandmeta), [`GetCurrentFile`](REFERENCE.md#getcurrentfile), [`GetCurrentFileContent`](REFERENCE.md#getcurrentfilecontent), [`GetOpenFiles`](REFERENCE.md#getopenfiles), [`GetSelectedText`](REFERENCE.md#getselectedtext) | Read editor and filesystem context, including unsaved editor content where applicable |
| [`WriteFile`](REFERENCE.md#writefile), [`ApplyEdit`](REFERENCE.md#applyedit), [`SaveFile`](REFERENCE.md#savefile) | Propose or save content changes through the review gate |
| [`CopyFile`](REFERENCE.md#copyfile), [`MoveFile`](REFERENCE.md#movefile), [`DeleteFile`](REFERENCE.md#deletefile) | Copy, relocate, or remove files with explicit confirmation |
| [`CloseFile`](REFERENCE.md#closefile), [`RefreshFileStatus`](REFERENCE.md#refreshfilestatus) | Manage open files and refresh NetBeans/VCS state |
| [`GetGitStatus`](REFERENCE.md#getgitstatus), [`GetGitDiff`](REFERENCE.md#getgitdiff), [`GitAdd`](REFERENCE.md#gitadd), [`GitCommit`](REFERENCE.md#gitcommit), [`GitLog`](REFERENCE.md#gitlog), [`GitPush`](REFERENCE.md#gitpush), [`GitPull`](REFERENCE.md#gitpull), [`GitCheckout`](REFERENCE.md#gitcheckout), [`GitBranch`](REFERENCE.md#gitbranch), [`GitDeleteBranch`](REFERENCE.md#gitdeletebranch), [`GitStash`](REFERENCE.md#gitstash), [`GitFetch`](REFERENCE.md#gitfetch), [`GitReset`](REFERENCE.md#gitreset), [`GitMerge`](REFERENCE.md#gitmerge), [`GitShow`](REFERENCE.md#gitshow), [`GitBlame`](REFERENCE.md#gitblame), [`GitRebase`](REFERENCE.md#gitrebase), [`GitCherryPick`](REFERENCE.md#gitcherrypick), [`GitTag`](REFERENCE.md#gittag), [`GitRemote`](REFERENCE.md#gitremote), [`GitRevert`](REFERENCE.md#gitrevert) | Git inspection and repository operations |
| [`GetClipboard`](REFERENCE.md#getclipboard) | Read clipboard text when clipboard access is enabled |
| [`WebRequest`](REFERENCE.md#webrequest) | Make permitted HTTP/HTTPS requests |

### Database and collaboration

| Tool | Description |
|---|---|
| [`ListDatabaseConnections`](REFERENCE.md#listdatabaseconnections), [`ListTables`](REFERENCE.md#listtables), [`GetTableSchema`](REFERENCE.md#gettableschema), [`GetTableData`](REFERENCE.md#gettabledata), [`ExecuteSqlQuery`](REFERENCE.md#executesqlquery) | Read-only Database Explorer access |
| [`ListAiSessions`](REFERENCE.md#listaisessions), [`SendAiMessage`](REFERENCE.md#sendaimessage), [`GetAiMessages`](REFERENCE.md#getaimessages), [`ReadAiMessage`](REFERENCE.md#readaimessage), [`DeleteAiMessage`](REFERENCE.md#deleteaimessage), [`IsAiSessionActive`](REFERENCE.md#isaisessionactive), [`UpdateSessionDescription`](REFERENCE.md#updatesessiondescription) | Inter-AI session discovery and messaging |
| [`GetPluginVersion`](REFERENCE.md#getpluginversion), [`GetInstructions`](REFERENCE.md#getinstructions), [`AskUserQuestion`](REFERENCE.md#askuserquestion), [`RunInspect`](REFERENCE.md#runinspect) | Plugin guidance, user input, and static-analysis entry points |

## Change review and safety

> **The plugin provides gates, not guarantees.** It mediates what passes through its own tool and approval layer, and it constrains backends where it can. It cannot make an assistant safe. Backends run as real processes with your credentials and your filesystem access, and one that can run shell commands can act in ways no dialog fully describes. Read what you are approving, and keep your work under version control so an unwanted change is recoverable.

Content-changing operations such as `WriteFile`, `ApplyEdit`, and content-bearing `SaveFile` are shown in the NetBeans diff review panel, and are not saved until you accept them.

`CopyFile`, `MoveFile`, and `DeleteFile` have no content diff, so they are confirmed as actions before proceeding. Shell commands proposed by a backend are confirmed the same way, showing the command that would run. Refactorings use NetBeans refactoring APIs so project references are updated consistently.

**Auto-accept removes the review step by design.** With it enabled, content writes and file actions are approved automatically and reported to the transcript after the fact rather than before. It does not extend to everything: shell commands, and any request whose subject the plugin could not identify, are still prompted every time regardless of the setting — approving those unseen is the one thing the gate exists to prevent. Auto-accept is off by default, can be set globally or per session, and is worth leaving off for anything you would not want applied unseen.

The local tool server binds only to loopback addresses. Every call is authenticated with the caller's session ID and per-session secret, and sessions cannot act as each other. Project scoping, database access, clipboard access, web permissions, inter-AI messaging, and auto-accept are all separately configurable.

## Architecture

```text
NetBeans IDE
  └── AI Coder dockable sessions
        ├── Claude / Grok CLI sessions
        ├── GitHub Copilot SDK session
        ├── OpenCode ACP session
        ├── Codex app-server session
        ├── Ollama OpenAI-compatible HTTP client
        └── Local MCP/IDE tool server and review bridge
```

The plugin keeps backend integration behind a common session/UI model. Backend-specific process managers and settings creators handle protocol details while shared components provide message rendering, persistence, IDE context, permissions, tools, and change review.

## Development

```bash
mvn package
mvn test
mvn nbm:run-ide
```

Tests are under `src/test/java` and cover protocol handling, tool registration, session settings, context management, persistence, and UI behavior.

## License

Copyright (c) 2026 Chris Quin.

This project is licensed under the [MIT License](LICENSE) — see [LICENSE](LICENSE) for details.
