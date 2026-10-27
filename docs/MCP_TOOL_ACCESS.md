[← Back to README](../README.md)

# MCP tool access

See also: [Backends](BACKENDS.md) · [OpenAI Compatible](ai/openai_compatible/README.md) · How-to: [Limit session access](howto/LIMIT_ACCESS.md)

Each session decides which MCP (Model Context Protocol) tools — the plugin's IDE tools — its assistant sees. Tools are arranged in **groups**, groups under **categories**, and a session switches whole groups on or off. The switches are in **⚙ > Session Configuration > MCP Tools** and start from the MCP template chosen at creation ([Templates](TEMPLATES.md)). The tools themselves are in the [MCP tool reference](MCP_REFERENCE.md).

## Groups and categories

| Category | Group | Tools |
|---|---|---|
| Always on | Always on | `GetInstructions` (no switch) |
| Files | File reading | `GetFileContent`, `GetFileInfo`, `FilterFileContent` |
| Files | File editing | `ApplyEdit`, `WriteFile`, `SaveFile` |
| Files | File management | `CopyFile`, `MoveFile`, `DeleteFile`, `CreateDirectory`, `DeleteDirectory` |
| Files | Text search | `SearchInFiles`, `FindFile`, `GetProjectStructure` |
| Code | Java code intelligence | `SearchSymbols`, `SearchTypes`, `FindDeclaration`, `FindUsages`, `FindImplementations`, `GetTypeHierarchy`, `GetClassMembers`, `GetJavadoc` |
| Code | Source tidy-up | `OrganiseImports`, `ReformatFile`, `FixImports`, `OrganiseMembers` |
| Code | Refactoring | `RenameSymbol`, `MoveClass`, `InlineVariable`, `ChangeMethodSignature` |
| Editor | Diagnostics | `GetDiagnostics`, `RunInspect` |
| Editor | Editor context | `GetCurrentFile`, `GetCurrentFileContent`, `GetOpenFiles`, `GetSelectedText`, `NavigateToLine`, `CloseFile`, `RefreshFileStatus` |
| Build | Maven | `BuildMavenProject`, `CleanAndBuildMavenProject`, `RunMavenTests`, `DownloadMavenJavadoc`, `DownloadMavenSources` |
| Build | Gradle | `BuildGradleProject`, `CleanAndBuildGradleProject`, `RunGradleTests` |
| Build | Ant | `BuildAntProject`, `CleanAndBuildAntProject`, `RunAntTests` |
| Build | Project actions | `BuildProject`, `CleanAndBuildProject`, `CleanProject`, `RunProject` |
| Build | Build queue | `ListBuilds`, `StopAsyncBuild` (no switch; on whenever any build group is on) |
| Git | Git read | `GetGitStatus`, `GetGitDiff`, `GitLog`, `GitShow`, `GitBlame` |
| Git | Git write | `GitAdd`, `GitCommit`, `GitBranch`, `GitCheckout`, `GitCherryPick`, `GitDeleteBranch`, `GitFetch`, `GitMerge`, `GitPull`, `GitPush`, `GitRebase`, `GitRemote`, `GitReset`, `GitRevert`, `GitStash`, `GitTag` |
| External | Database | `ListDatabaseConnections`, `ListTables`, `GetTableSchema`, `GetTableData`, `ExecuteSqlQuery` |
| External | Web | `WebRequest` |
| External | Clipboard | `GetClipboard` |
| Collaboration | Peer messaging | `PeerMessageSend`, `PeerMessageList`, `PeerMessageRead`, `PeerMessageMarkReplied`, `PeerMessageDelete`, `PeerSessionList`, `PeerSessionDescribe`, `PeerSessionIsActive` |
| Collaboration | Idle watchers | `PeerIdleWatcherCreate`, `PeerIdleWatcherList`, `PeerIdleWatcherCancel` |
| Collaboration | Ask user | `AskUserQuestion` (some backends only; see its [entry](MCP_REFERENCE.md#askuserquestion)) |
| Collaboration | Plugin information | `GetPluginVersion` |
| Memory (OpenAI Compatible only) | Session memory | `MemorySave`, `MemoryRead`, `MemoryDelete` ([Long-term memory](ai/openai_compatible/MEMORY.md)) |

A Git tool is in Git read or Git write by its own mutating flag. That is why `GitBranch`, `GitTag`, `GitRemote`, `GitStash` and `GitFetch` are in Git write even when they only list or fetch.

## Two layers

A tool is available only when **both** allow it:

1. **The group switch** in MCP Tools (or the session's MCP template).
2. **The setting beneath the group**, for groups that have one. These are the permissions in [Settings](SETTINGS.md). The MCP Tools panel shows each under its group's switch and hides it while the group is off. The stored value is kept and applies again when the group is switched back on.

| Group | Setting that must also allow it |
|---|---|
| Database | Allow database access (with read-only options and row limit) |
| Git read | **Allow git access** and **Allow read-only git commands** |
| Git write | **Allow git access** and **Allow git commands that modify the repository** |
| Web | Allow web requests (with method, header, body and destination options) |
| Clipboard | **Allow clipboard access** |
| Peer messaging | **Allow inter-AI communication** |
| Idle watchers | **Allow idle AI watcher timer** |

Every other group has only its switch.

A tool that fails either test is left out of the tool list, and the instruction sentences that name it are removed, so the assistant is never pointed at a tool it cannot use. A call to it anyway is refused with a message naming the layer:

- **Group off:** "Tool not enabled for this session: <Tool>. The '<Group>' tool group is switched off for this session (session settings → MCP Tools)."
- **Build queue, no build group on:** "No build tool group is switched on for this session."
- **Setting off:** "<Subject> is disabled for this session. Ask the user to enable '<setting>' …". The assistant cannot change session settings itself.
- **Group another kind of session does not have:** "The 'Session memory' tools are not available in this kind of session."

## Where the switches come from

- A new session gets the switches of the **MCP template** chosen in Create Session. That is Default unless you pick another, such as the built-in **Minimal** ([Templates](TEMPLATES.md#mcp-templates)).
- A session with no tool configuration of its own (created before it had any) derives one from its permission settings the first time it starts. Database, Git read, Git write, Web and Clipboard follow their settings; every other group is on. The result is stored with the session. The built-in **Default** MCP template is seeded from the global settings by the same rule.
- After that the switches are the session's own. Edit them in MCP Tools or replace them with **Apply MCP template…** ([Changing a session afterwards](TEMPLATES.md#changing-a-session-afterwards)). Editing a template never changes an existing session.

## When a change takes effect

OpenAI Compatible computes its tool list once per turn, so a change applies from the next turn. Every other backend learns its tools when its process starts. After you save a change that exposes a tool the running backend was not told about, the plugin asks "Tool changes apply when the session restarts. Restart now?" with **Restart now** and **Later**. A restart waits for the current turn to end, and the backend resumes its conversation. Switching a tool off needs no restart, because the call is refused either way. A backend that is not running picks up the change at its next start.
