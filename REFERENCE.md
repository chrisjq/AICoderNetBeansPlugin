
# AI Coder reference

This is the detailed reference for the plugin's settings and MCP/IDE tools. Backend availability and a session's permission settings determine which tools are offered.

For what the plugin is and how to install it, see the [README](README.md). For using the NetBeans interface, see the [UI guide](UI_GUIDE.md).

## Settings

Open global defaults at **Tools > Options > AI Coder**. A session may override each applicable option; an unset override inherits the global value.

### General and session settings

| Area | Settings and behaviour |
|---|---|
| Conversation | Maximum history, save history, chat font size, and diff-context lines. |
| Session instructions | Optional session instructions, delivered on the first request or automatically at session startup. |
| Project scope | Restrict file access to the session's selected/open project directories. Enabled by default. |
| Change review | Auto-accept policy and diff presentation. Content changes normally require the NetBeans Accept/Reject review. |
| Inter-AI | Enable messaging, automatic inbox notices, and important-message interruption. Messaging and automatic notices are off by default. |
| Web requests | A master switch and separate permissions for HTTP methods, headers, and bodies. GET is allowed by default when web access is enabled; state-changing methods, custom headers, and bodies are off by default. |
| Database | Master switch, read-only sub-permissions, and result-row limit. Disabled by default; the default limit is 25 rows. Only a single SELECT statement is permitted, and the JDBC connection is additionally set read-only for the duration of the query. |
| Clipboard | Allow reading clipboard text. Disabled by default. |
| Infrastructure | MCP loopback port, save-session-on-close prompting, inbox retention and maximum size, debug JSON/context logging, and tool-use logging. Inbox defaults: 60 minutes and 1,000 messages. |

### Backend-specific settings

| Backend | Settings |
|---|---|
| Claude | CLI executable and model. Model choices are discovered and cached where possible. |
| GitHub Copilot | CLI executable and Copilot SDK model discovery, with fallback models. |
| Grok | CLI executable plus discovered/fallback model choices. |
| OpenCode | CLI executable, model, and ACP agent mode. **Build** permits normal agent work; **Plan** is read-only planning. |
| Codex | CLI/app-server executable, editable model, reasoning effort, sandbox, and approval settings. Known model choices include `gpt-5.6-terra`, `gpt-5.6-luna`, `gpt-5.5`, and `gpt-5.4-mini`. |
| Ollama (Local) | OpenAI-compatible base URL (default `http://localhost:11434`), model, context-window value, and context management. No API key is needed by the plugin. |

For OpenAI-compatible sessions, context trimming can trigger on message count, estimated tokens, or reported tokens. Strategies are no trimming, drop older messages, drop marked messages, or summarise. Configure the trigger, post-trim target, message limit, and context persistence in the Ollama/OpenAI context settings.

## Tool use rules

Call `GetInstructions` before other plugin tools. Use the IDE-aware tools below rather than shell equivalents. Paths must be absolute. Tool calls are authenticated for their own session and are restricted by the current permission settings.

- No tool falls back to the file the user is looking at. Pass `filePath` explicitly, and call `GetCurrentFile` when you want the focused editor's file.
- Read a file with `GetFileContent` before changing it.
- Reading or writing a file **saves its unsaved editor changes first**. These tools work on disk, and the editor holds its own copy, so a tool that ignored the buffer would show you text the user cannot see and overwrite the text they can. Flushing first makes the two agree, so what you read is what you edit and the user's work is preserved. The result says so when a flush happened. If the changes cannot be saved the tool refuses outright rather than proceeding and discarding them. Note the Accept/Reject diff is rendered from the file on disk *before* the flush, so it does not display the user's unsaved lines even though the write preserves them.
- For large files, call `GetFileSizeAndMeta` first and page `GetFileContent` with start/end lines.
- Use semantic refactorings before textual edits whenever possible.
- `WriteFile`, `ApplyEdit`, and content-bearing `SaveFile` show the Accept/Reject diff. `CopyFile`, `MoveFile`, and `DeleteFile` are confirmed as actions, as are shell commands proposed by a backend. **Auto-accept approves the content writes and file actions without asking, but not shell commands, and not a request whose subject the plugin could not identify** — those are prompted every time regardless of the setting. See [Concurrency and limits](#concurrency-and-limits) for how long a prompt waits before it expires.
- Run `GetDiagnostics` before proposing code fixes; refresh file/VCS state after external changes or Git operations.

## Concurrency and limits

These are enforced in code and are not configurable from the UI. They are worth knowing because each one is reachable in normal use.

| Limit | Value | What happens |
|---|---|---|
| Mutating tool calls, plugin-wide | one at a time | Every mutating tool call across **all sessions and backends** is serialised through a single lock. A call waits up to 15 minutes, then returns a mutation-lock timeout. |
| Resource locks | Git 5 min · Build 10 min · Refactor 3 min · File I/O 2 min · Session 1 min · Project structure 5 min | Git, build/test, refactoring and file-write tools also take a **global** lock for their resource type — not per project. A second session's build on an unrelated project is refused immediately with "Resource locked by session …" rather than queued. |
| Diff and confirm prompts | 120 seconds | An unanswered prompt expires. This is **not** treated as a rejection: the backend is told it may retry, and the panel's buttons stop responding. |
| Maven / Gradle / Ant build and test tools | 180 seconds | The command is killed and the call returns a timeout. Reachable with a slow test suite or a cold dependency download. `BuildProject`, `CleanProject` and `CleanAndBuildProject` are exempt — they are fire-and-forget IDE actions. |
| `AskUserQuestion` | 300 seconds | Returns "No response (timed out)". |
| `WebRequest` | 30 s default, 300 s maximum | Set per call via `timeoutSeconds`. |

The plugin-wide mutation lock is the one most likely to surprise: running several sessions concurrently is supported, but their *mutating* work is effectively single-file.

## MCP tools

Every tool additionally takes `sessionId` and `secretKey`, both required — they authenticate the call to the calling session. They are omitted from the Parameters column below, which lists only the arguments specific to each tool.

The MCP server checks both before the tool runs, so a credential failure is a JSON-RPC error (-32600) rather than a tool result. Either one missing gives "Authentication failed: `sessionId` and `secretKey` are both required on every tool call. Copy them verbatim from your session identity block."; a pair matching no session gives "Authentication failed: no session matches that `sessionId`/`secretKey` pair. Re-read your session identity block and copy both values exactly, character for character."

> **Not every constraint appears in the schema.** A tool's declared `required` list does not always describe what it will actually accept. Conditional rules — `GitTag`'s `name` being needed only for create/delete, `ChangeMethodSignature` requiring both `name` and `type` for a new parameter, `DeleteAiMessage` needing at least one of `messageId`/`messageIds` — are enforced when the tool runs, not when the arguments are validated. Several tools declare no `required` list at all despite having real constraints. An invalid combination therefore comes back as an error from the call rather than being rejected up front.

### Dates and times in tool output

Any date a tool shows you is formatted in the **local timezone of the machine running the IDE**, not UTC:

```
2026-08-22 21:28:48 +12:00 (Pacific/Auckland)
```

The UTC offset and the zone ID are always present, so the value is unambiguous even when you are reasoning about a commit or a log written elsewhere. A zero offset prints as `Z`.

This applies to the `Server time:` line and the `Sent:`/`Read:` lines of [`GetAiMessages`](#getaimessages) and [`ReadAiMessage`](#readaimessage), the modification time from [`GetFileSizeAndMeta`](#getfilesizeandmeta), and the commit date from [`GitShow`](#gitshow).

It is a display format, not an API. Nothing accepts it back as input, and no standard parser reads it — the zone ID in parentheses is not part of ISO-8601. Timestamps the plugin stores on disk are unaffected and remain machine-readable.

### Build tools

The Maven, Gradle and Ant tools run their build tool as a command and **time out after 180 seconds**, which a slow test suite or a cold dependency download can exceed. The three IDE action tools (`BuildProject`, `CleanProject`, `CleanAndBuildProject`) invoke the NetBeans action provider instead: they take no parameters, act on the open project, and return immediately without waiting for the build to finish.

| Tool | Detailed description | Parameters |
|---|---|---|
| <a id="buildproject"></a>`BuildProject` | Runs the NetBeans build action for the open project. Fire-and-forget. | • _None_ |
| <a id="cleanproject"></a>`CleanProject` | Runs the IDE clean action for the open project. Fire-and-forget. | • _None_ |
| <a id="cleanandbuildproject"></a>`CleanAndBuildProject` | Runs clean then build for the open project. Fire-and-forget. | • _None_ |
| <a id="buildmavenproject"></a>`BuildMavenProject` | Runs Maven package and returns the full output. | • `projectPath` (string) — omit to auto-detect |
| <a id="cleanandbuildmavenproject"></a>`CleanAndBuildMavenProject` | Runs Maven clean package and returns the full output. | • `projectPath` (string) — omit to auto-detect |
| <a id="runmaventests"></a>`RunMavenTests` | Runs Maven tests, optionally filtered to one class. | • `testClass` (string) — omit to run all tests<br>• `projectPath` (string) — omit to auto-detect |
| <a id="buildgradleproject"></a>`BuildGradleProject` | Runs the Gradle build and returns the full output. | • `projectPath` (string) — omit to auto-detect |
| <a id="rungradletests"></a>`RunGradleTests` | Runs Gradle tests, optionally filtered to one class. | • `testClass` (string) — omit to run all tests<br>• `projectPath` (string) — omit to auto-detect |
| <a id="buildantproject"></a>`BuildAntProject` | Runs the Ant build and returns the full output. | • `projectPath` (string) — omit to auto-detect |
| <a id="runanttests"></a>`RunAntTests` | Runs Ant tests, optionally filtered to one class. | • `testClass` (string) — omit to run all tests<br>• `projectPath` (string) — omit to auto-detect |
| <a id="downloadmavensources"></a>`DownloadMavenSources` | Downloads Maven dependency source archives. | • `projectPath` (string) — omit to auto-detect |
| <a id="downloadmavenjavadoc"></a>`DownloadMavenJavadoc` | Downloads Maven dependency Javadoc. | • `projectPath` (string) — omit to auto-detect |

### Search tools

| Tool | Detailed description | Parameters |
|---|---|---|
| <a id="searchinfiles"></a>`SearchInFiles` | Finds literal-text or regex matches across open-project source. Display is capped at 200 and the cap is not adjustable, but the reported total is the real one — `Found 350 match(es) in 12 file(s) (showing first 200)`. Supplying a source `filePath` limits the search to that file's source classpath; omitting it searches every open project's Java source roots (or the project root when no Java roots are registered), independent of the focused editor. | • `query` (string, required) — literal text or regex to find<br>• `filePath` (string) — any source file in the target project; omit to search all open projects<br>• `filePattern` (string, default `*.java`) — glob filter such as `*.java` or `*.xml`<br>• `caseSensitive` (boolean, default `false`)<br>• `isRegex` (boolean, default `false`; otherwise query is literal text) |
| <a id="searchtypes"></a>`SearchTypes` | Finds Java types by name pattern across every open project when `filePath` is omitted; supplying a source `filePath` scopes it to that project. Display is capped at 100, but the reported total is the real one — `Found 3412 type(s) (showing first 100)` — so you can tell whether to narrow the query. | • `name` (string, required) — type name or pattern<br>• `filePath` (string) — source file that scopes the search; omit to search every open project<br>• `kind` (string, default `prefix`) — how `name` is matched, not which kinds are returned: `prefix`, `exact`, `camelCase`, or `regexp`<br>• `includeDeps` (boolean, default `false`) — include dependency JARs |
| <a id="searchsymbols"></a>`SearchSymbols` | Finds methods, fields, and nested symbols by name across every open project when `filePath` is omitted; supplying a source `filePath` scopes it to that project. Display is capped at 100, but the reported total is the real one — `Found 3412 type(s) with matching symbols (showing first 100)`. | • `name` (string, required) — symbol name or pattern<br>• `filePath` (string) — source file that scopes the search; omit to search every open project<br>• `kind` (string, default `prefix`) — how `name` is matched, not which kinds are returned: `prefix`, `exact`, `camelCase`, or `regexp`<br>• `includeDeps` (boolean, default `false`) — include dependency JARs |
| <a id="finddeclaration"></a>`FindDeclaration` | Resolves a symbol at a source location to its declaration. | • `line` (integer, required) — 1-based<br>• `filePath` (string) — omit to resolve against the first open project's source root, not the editor<br>• `column` (integer) — position within the line |
| <a id="findimplementations"></a>`FindImplementations` | Returns direct subtypes or implementors of a type. | • `line` (integer, required) — 1-based<br>• `filePath` (string) — omit to resolve against the first open project's source root, not the editor |
| <a id="findusages"></a>`FindUsages` | Returns references to a type or member. | • `className` (string) — the type to search for<br>• `memberName` (string) — restrict to one member<br>• `findSubclasses` (boolean, default `false`) — also find subtypes<br>• `directSubclassesOnly` (boolean, default `false`) — only with `findSubclasses`<br>• `searchInComments` (boolean, default `false`) |
| <a id="getprojectstructure"></a>`GetProjectStructure` | Returns source roots and project layout. | • _None_ |
| <a id="getclassmembers"></a>`GetClassMembers` | Lists fields, constructors, and methods for a class. | • `className` (string, required) — fully qualified; not resolved from the user's cursor |
| <a id="gettypehierarchy"></a>`GetTypeHierarchy` | Returns supertype/subtype relationships for a class or interface. | • `className` (string, required) — fully qualified; not resolved from the user's cursor |
| <a id="getjavadoc"></a>`GetJavadoc` | Returns classpath Javadoc and signatures; download Maven Javadoc first if needed. | • `className` (string, required) — fully qualified<br>• `memberName` (string) — restrict to one member |

### Refactoring tools

These tools do not read the editor. Each refactoring requires its explicit target path; tools that operate on a source location also require `line`. Omitting either returns an error rather than acting on whatever the user happens to have open — a refactoring writes, and a target chosen by where someone last clicked is not a target the caller can check. Use [`GetCurrentFile`](#getcurrentfile) when you deliberately want the user's position; it returns the path, line and column, which you then pass explicitly. `MoveClass` is the one exception on `line`: supplying it moves just that class, and omitting it moves the whole file, which is refused when the file declares more than one top-level type.

| Tool | Detailed description | Parameters |
|---|---|---|
| <a id="renamesymbol"></a>`RenameSymbol` | Renames a symbol with NetBeans reference updates. | • `newName` (string, required) — the new identifier<br>• `filePath` (string, required)<br>• `line` (integer, required) — 1-based |
| <a id="moveclass"></a>`MoveClass` | Moves a Java class to another package with import and reference updates. With `line` it moves only the class declared there, leaving any other top-level classes in the file behind; without `line` it moves the whole file, and refuses when the file declares more than one top-level type so classes you did not name cannot move by accident. | • `targetPackage` (string, required) — e.g. `com.example.ui`<br>• `filePath` (string, required)<br>• `line` (integer) — declaration line of the class to move; omit only for a single-type file |
| <a id="movefile"></a>`MoveFile` | Moves a file; Java files use refactoring, other files use standard file move. | • `sourcePath` (string, required) — absolute path of the file to move<br>• `targetDirectory` (string, required) — destination directory; must exist |
| <a id="inlinevariable"></a>`InlineVariable` | Inlines a local variable at its use sites. | • `filePath` (string, required)<br>• `line` (integer, required) — 1-based declaration or usage |
| <a id="changemethodsignature"></a>`ChangeMethodSignature` | Changes a method name, parameters, or return type and updates callers. | • `parameters` (array) — the full desired list; per item: `originalIndex` (omit = array position, `-1` = new); a new parameter requires both `name` and `type`, and its `defaultValue` is optional (an empty string is inserted at call sites when omitted); to change an existing parameter you must supply `name` **and** `type` together, since supplying only one leaves both unchanged<br>• `filePath` (string, required)<br>• `line` (integer, required)<br>• `methodName` (string) — omit to keep<br>• `returnType` (string) — omit to keep<br>• `overloadMethod` (boolean) — true adds an overload instead of modifying |
| <a id="getdiagnostics"></a>`GetDiagnostics` | Returns compiler diagnostics for open Java files. | • _None_ |
| <a id="navigatetoline"></a>`NavigateToLine` | Opens `filePath` and moves the editor to a line. | • `filePath` (string, required) — absolute path<br>• `line` (integer, required) — 1-based |
| <a id="fiximports"></a>`FixImports` | Removes unused imports and adds resolvable missing imports. | • `filePath` (string, required) — no fallback to the focused editor |
| <a id="organiseimports"></a>`OrganiseImports` | Sorts and groups imports using project formatting rules. | • `filePath` (string, required) — no fallback to the focused editor |
| <a id="organisemembers"></a>`OrganiseMembers` | Reorders class members using configured member order. | • `filePath` (string, required) — no fallback to the focused editor |
| <a id="reformatfile"></a>`ReformatFile` | Reformats `filePath` with project code style. | • `filePath` (string, required) — no fallback to the focused editor |
| <a id="runinspect"></a>`RunInspect` | Opens NetBeans Inspect/static-analysis UI. | • _None_ |

### Files tools

| Tool | Detailed description | Parameters |
|---|---|---|
| <a id="getfilecontent"></a>`GetFileContent` | Reads a file, saving its unsaved editor changes first so the text matches what the user has on screen; use `startLine`/`endLine` to page large files. | • `filePath` (string, required) — absolute file path<br>• `startLine` (integer) — first 1-based line; omit for beginning<br>• `endLine` (integer) — last 1-based line; omit for end |
| <a id="getfilesizeandmeta"></a>`GetFileSizeAndMeta` | Gets size, lines, encoding, modification time and age, writability, and unsaved-change metadata. | • `filePath` (string, required) — absolute file path |
| <a id="getcurrentfile"></a>`GetCurrentFile` | Returns active editor path, line, and column. | • _None_ |
| <a id="getcurrentfilecontent"></a>`GetCurrentFileContent` | Returns active editor content. | • _None_ |
| <a id="getopenfiles"></a>`GetOpenFiles` | Lists open editor files. | • _None_ |
| <a id="getselectedtext"></a>`GetSelectedText` | Returns active-editor selection. | • _None_ |
| <a id="writefile"></a>`WriteFile` | Creates or replaces `filePath` with `content`; unsaved editor changes are saved first; shows the diff review. | • `filePath` (string, required) — absolute file path<br>• `content` (string, required) — full file content |
| <a id="applyedit"></a>`ApplyEdit` | Replaces exact `oldString` with `newString` in `filePath`; unsaved editor changes are saved first, so `oldString` is matched against what the user has on screen; shows the diff review. | • `filePath` (string, required) — absolute file path<br>• `oldString` (string, required) — exact text to replace<br>• `newString` (string, required) — replacement text |
| <a id="savefile"></a>`SaveFile` | Saves supplied `content` to `filePath`, or flushes that file's unsaved editor changes when `content` is omitted; content changes show review. | • `filePath` (string, required) — in both modes; no fallback to the focused editor<br>• `content` (string) — full replacement content; omit to flush unsaved editor changes |
| <a id="copyfile"></a>`CopyFile` | Copies source to a target directory; optional base `newName`; requires confirmation. | • `sourcePath` (string, required) — absolute source file<br>• `targetDirectory` (string, required) — existing destination directory<br>• `newName` (string) — base name without extension; omit to keep name |
| <a id="deletefile"></a>`DeleteFile` | Deletes `filePath`, closes its editor, and refreshes VCS; requires confirmation. | • `filePath` (string, required) — never falls back to the focused editor |
| <a id="closefile"></a>`CloseFile` | Closes the editor tab for `filePath`. | • `filePath` (string, required) — no fallback to the focused editor |
| <a id="refreshfilestatus"></a>`RefreshFileStatus` | Refreshes NetBeans filesystem/VCS state for changed project files. | • `filePath` (string) — omit to refresh all open projects |
| <a id="webrequest"></a>`WebRequest` | Requests HTTP/HTTPS `url`; optional method, headers, body, timeout, and response maximum require their matching permissions. | • `url` (string, required) — HTTP/HTTPS URL<br>• `method` (string, default `GET`)<br>• `headers` (object)<br>• `body` (string)<br>• `timeoutSeconds` (integer, default `30`) — range 1–300<br>• `maxChars` (integer, default `20000`) — range 1–200000 |
| <a id="getclipboard"></a>`GetClipboard` | Reads clipboard text when clipboard permission is enabled. | • _None_ |

### Git tools

Every Git tool requires `projectPath` — the repository or project root — except `GitBlame`, where it is optional if `file` is absolute. Some parameters below are required only for particular `action`/`operation` values; those are enforced by the handler rather than by the JSON schema, so an invalid combination returns an error rather than being rejected up front.

| Tool | Detailed description | Parameters |
|---|---|---|
| <a id="getgitstatus"></a>`GetGitStatus` | Shows current branch and short file status for `projectPath`. | • `projectPath` (string, required) |
| <a id="getgitdiff"></a>`GetGitDiff` | Shows staged or unstaged diff for `projectPath`. | • `projectPath` (string, required)<br>• `staged` (boolean, default `false`) — false shows unstaged |
| <a id="gitadd"></a>`GitAdd` | Stages supplied paths. | • `projectPath` (string, required)<br>• `files` (array, required) — non-empty; `["."]` stages all |
| <a id="gitcommit"></a>`GitCommit` | Provides the NetBeans-aware git commit operation. | • `projectPath` (string, required)<br>• `message` (string, required)<br>• `files` (array) — staged first; omit to commit the index |
| <a id="gitlog"></a>`GitLog` | Provides the NetBeans-aware git log operation. | • `projectPath` (string, required)<br>• `limit` (integer, default `20`) — range 1–1000<br>• `file` (string) — scope history to one path<br>• `follow` (boolean, default `false`) — only meaningful with `file` |
| <a id="gitpush"></a>`GitPush` | Provides the NetBeans-aware git push operation. | • `projectPath` (string, required)<br>• `remote` (string, default `origin`)<br>• `branch` (string) — defaults to the current branch |
| <a id="gitpull"></a>`GitPull` | Provides the NetBeans-aware git pull operation. | • `projectPath` (string, required)<br>• `remote` (string, default `origin`) |
| <a id="gitcheckout"></a>`GitCheckout` | Provides the NetBeans-aware git checkout operation. | • `projectPath` (string, required)<br>• `branch` (string, required)<br>• `create` (boolean, default `false`) |
| <a id="gitbranch"></a>`GitBranch` | Provides the NetBeans-aware git branch operation. | • `projectPath` (string, required)<br>• `all` (boolean, default `false`)<br>• `create` (string) — create from HEAD instead of listing |
| <a id="gitdeletebranch"></a>`GitDeleteBranch` | Provides the NetBeans-aware git delete branch operation. | • `projectPath` (string, required)<br>• `branch` (string, required)<br>• `force` (boolean, default `false`) |
| <a id="gitstash"></a>`GitStash` | Provides the NetBeans-aware git stash operation. | • `projectPath` (string, required)<br>• `action` (string, default `push`) — push/list/pop/apply/drop<br>• `index` (integer, default `0`)<br>• `message` (string, default `WIP`)<br>• `includeUntracked` (boolean, default `false`) |
| <a id="gitfetch"></a>`GitFetch` | Provides the NetBeans-aware git fetch operation. | • `projectPath` (string, required)<br>• `remote` (string, default `origin`) |
| <a id="gitreset"></a>`GitReset` | Provides the NetBeans-aware git reset operation. | • `projectPath` (string, required)<br>• `files` (array) — unstage these<br>• `revision` (string, default `HEAD`)<br>• `type` (string, default `MIXED`) — SOFT/MIXED/HARD |
| <a id="gitmerge"></a>`GitMerge` | Provides the NetBeans-aware git merge operation. | • `projectPath` (string, required)<br>• `branch` (string, required) |
| <a id="gitshow"></a>`GitShow` | Provides the NetBeans-aware git show operation, including the commit Date in local formatted time. | • `projectPath` (string, required)<br>• `revision` (string, default `HEAD`) |
| <a id="gitblame"></a>`GitBlame` | Provides the NetBeans-aware git blame operation. | • `file` (string, required) — absolute or project-relative<br>• `projectPath` (string) — optional when `file` is absolute |
| <a id="gitrebase"></a>`GitRebase` | Provides the NetBeans-aware git rebase operation. | • `projectPath` (string, required)<br>• `operation` (string, default `BEGIN`) — BEGIN/CONTINUE/SKIP/ABORT<br>• `upstream` (string) — required for BEGIN |
| <a id="gitcherrypick"></a>`GitCherryPick` | Provides the NetBeans-aware git cherry pick operation. | • `projectPath` (string, required)<br>• `operation` (string, default `BEGIN`) — BEGIN/CONTINUE/QUIT/ABORT<br>• `revisions` (array) — required for BEGIN |
| <a id="gittag"></a>`GitTag` | Provides the NetBeans-aware git tag operation. | • `projectPath` (string, required)<br>• `action` (string, default `list`) — list/create/delete<br>• `name` (string) — required for create/delete<br>• `revision` (string, default `HEAD`)<br>• `message` (string) — annotation |
| <a id="gitremote"></a>`GitRemote` | Provides the NetBeans-aware git remote operation. | • `projectPath` (string, required)<br>• `action` (string, default `list`) — list/add/remove<br>• `name` (string) — required for add/remove<br>• `url` (string) — required for add |
| <a id="gitrevert"></a>`GitRevert` | Provides the NetBeans-aware git revert operation. | • `projectPath` (string, required)<br>• `revision` (string, default `HEAD`) |

### Database tools

`connectionName` is the display name of a connection registered in **Services > Databases**, which must already be connected. Results are capped at the configured row limit. A result that reaches the limit ends with `... (row limit <configured limit> reached, results may be truncated)`, where the limit defaults to 25 — "may be", because the driver stops at the limit, so a query returning exactly that many rows is indistinguishable from one that was cut short. Unlike the search tools, no true total is available.

| Tool | Detailed description | Parameters |
|---|---|---|
| <a id="listdatabaseconnections"></a>`ListDatabaseConnections` | Lists registered Database Explorer connections and whether each is connected. | • _None_ |
| <a id="listtables"></a>`ListTables` | Lists tables in a schema reachable through a connected connection. | • `connectionName` (string, required) |
| <a id="gettableschema"></a>`GetTableSchema` | Returns column names, types, nullability, and primary keys. | • `connectionName` (string, required)<br>• `tableName` (string, required) |
| <a id="gettabledata"></a>`GetTableData` | Reads rows read-only, up to the configured row limit. Runs through the same guarded path as `ExecuteSqlQuery`, so the same serialisation and timeouts apply. | • `connectionName` (string, required)<br>• `tableName` (string, required)<br>• `limit` (integer, default the configured row limit) — capped at that limit |
| <a id="executesqlquery"></a>`ExecuteSqlQuery` | Runs a single read-only SELECT. The statement must begin with `SELECT`, and anything chained after a `;` is rejected — a trailing `;` with nothing following it is accepted. The connection is also set read-only for the duration, though that is a hint some JDBC drivers ignore, so the statement check is the load-bearing guard. Queries on one connection run one at a time; a query is cut off after 5 minutes, and a caller that waits 5 minutes for the connection gives up and reports a probable hang rather than blocking. | • `connectionName` (string, required)<br>• `sql` (string, required) — a single SELECT |

### Collaboration tools

| Tool | Detailed description | Parameters |
|---|---|---|
| <a id="listaisessions"></a>`ListAiSessions` | Lists other AI sessions as a JSON array; each entry carries `sessionId`, `name`, an optional `description`, `aiType`, any backend-specific fields, and `active` (`true` = mid-turn). Idle and busy sessions can both receive messages. | • _None_ |
| <a id="sendaimessage"></a>`SendAiMessage` | Delivers a message to a peer session's inbox. A mistyped ID and a closed peer are distinguishable: an ID matching no session returns "Error: no AI session has the ID '…'. Call `ListAiSessions` …", while a session that exists but is closed returns "Error: session '…' is not active". A peer with messaging disabled returns "Error: inter-AI communication is disabled for session '…'". | • `targetSessionId` (string, required) — from `ListAiSessions`<br>• `subject` (string, required) — max 100 chars<br>• `message` (string, required) — max 200,000 chars<br>• `replyToMessageId` (string)<br>• `important` (boolean) — interrupts the target now instead of waiting for its turn to end; needs the target's *allow important messages* enabled, else ignored<br>• `expectsReply` (boolean) — notifies you if the recipient exits without replying<br>• `replyImportant` (boolean) — only with `expectsReply`; makes the reply interrupt you |
| <a id="getaimessages"></a>`GetAiMessages` | Lists inbox summaries; does not mark anything read. | • _None_ |
| <a id="readaimessage"></a>`ReadAiMessage` | Reads one message in full and marks it read; on the first read of a message that expects a reply, appends a paragraph instructing the reader to reply with `SendAiMessage`. Re-reads do not append it. This makes the instruction self-contained if the notification arrives late or is dropped. The message stays until deleted. | • `messageId` (string, required) — from `GetAiMessages` |
| <a id="deleteaimessage"></a>`DeleteAiMessage` | Deletes one or several inbox messages. | • `messageId` (string) — a single ID<br>• `messageIds` (array) — bulk delete<br>• at least one is required; supplying both is accepted and deletes the combined set |
| <a id="isaisessionactive"></a>`IsAiSessionActive` | Reports whether a peer is mid-turn, as text rather than a boolean: not open (window closed or unregistered), open and idle, or open and currently processing a turn — in which case a message queues until that turn completes. | • `targetSessionId` (string, required) |
| <a id="updatesessiondescription"></a>`UpdateSessionDescription` | Sets the description peers see in `ListAiSessions`. | • `description` (string, required) |
| <a id="askuserquestion"></a>`AskUserQuestion` | Puts structured choices to the user. Times out after 300 seconds. | • `questions` (array, required) — per item: `question` (required), `options` (required; each `label` + `description`), `header` (chip label, max 12 chars), `multiSelect` (boolean, default `false`) |
| <a id="getpluginversion"></a>`GetPluginVersion` | Returns the version of the plugin that is currently running. | • _None_ |
| <a id="getinstructions"></a>`GetInstructions` | Returns the usage guide. Required before any other plugin tool. | • _None_ |

