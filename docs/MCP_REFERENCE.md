[← Back to README](../README.md)

# MCP tool reference

See also: [UI guide](UI_GUIDE.md) · [Sessions](SESSIONS.md) · [Collaboration](COLLABORATION.md) · [Backends](BACKENDS.md) · [OpenCode](ai/opencode/README.md)

A quick reference for the MCP tools the plugin gives your assistant. Which tools a session gets is in [MCP tool access](MCP_TOOL_ACCESS.md); the permissions behind them are in [Settings](SETTINGS.md). For editor-save behaviour, diff review and confirmations, see [Change review](CHANGE_REVIEW.md). Tool-specific path, permission and timeout rules are listed below.

In the tables, **bold** parameters are required. Defaults and limits are in parentheses. Every call is also authenticated with the session's ID and secret, which are not listed.

## Concurrency and limits

These limits are fixed.

| Limit | Value | What happens |
|---|---|---|
| Refactorings (`RenameSymbol`, `MoveClass`, `InlineVariable`, `ChangeMethodSignature`) | One at a time, plugin-wide | Each waits up to 5 s for its refactoring slot. Git, builds and peer tools are not affected. |
| Java `MoveFile` | One at a time, plugin-wide | Waits up to 10 min 10 s for the exclusive mutation lock. |
| Per-path file tools | Concurrent when paths differ | Covers `ApplyEdit`, `WriteFile`, `SaveFile`, `CopyFile`, `MoveFile`, `DeleteFile`, `CreateDirectory`, `DeleteDirectory`, `ReformatFile`, `OrganiseImports`, `OrganiseMembers`, `FixImports`. A second write to the same file waits up to 30 s. A file is locked only while it is written, not while a diff or confirmation is open. |
| Diff and confirm prompts | [Confirmations](CHANGE_REVIEW.md#confirmations) | An unanswered prompt expires. |
| Build tools | [Build tools](#build-tools) | One shared queue. |

## Dates and times in tool output

Dates a tool shows use the **local timezone of the machine running the IDE**, not UTC:

```text
2026-08-22 21:28:48 +12:00 (Pacific/Auckland)
```

## MCP tools

These tools let the assistant work in the IDE, within the session's permissions.

### Build tools

Every build tool runs through one plugin-wide **build queue**: the Maven, Gradle and Ant build, clean-and-build, test and download tools, and the three IDE actions. The queue is first come first served across all sessions, runs one build at a time, and holds up to eight builds per project. A ninth is refused ("At most 8 builds per project can be queued or running"). `RunProject` is not queued.

Maven options: `projectList` — selected projects; `alsoMake` — required projects too; `resumeFrom` — resume at a module; `offline` — build offline; `updateSnapshots` — refresh snapshots; `profiles` — profiles to activate; `properties` — Maven properties; `threads` — parallel threads, e.g. `4` or `1C`; `failAtEnd` — continue then fail. Gradle options: `offline` — build offline; `refreshDependencies` — refresh dependencies; `properties` — Gradle properties; `systemProperties` — JVM properties; `parallel` — parallel projects; `continueOnFailure` — continue after failures. Ant options: `properties` — Ant properties; `keepGoing` — continue after failures.

Each tool takes an optional `async` flag:

| `async` | Behaviour |
|---|---|
| `false` (default) | Waits up to 120 s for a slot, then runs to the inline limit and returns the full result. The inline limit is 5 minutes, or the longest successful build or test on that project plus 20% if longer. If the build cannot start in time, or runs out of time, the result suggests `async`. |
| `true` | Returns at once with a build id and queue position. The build may run for up to 2 hours. The full result arrives as a message to the assistant; the chat shows a one-line system message such as `BUILD: BuildMavenProject SUCCESS in 2 mins, 3 secs (/path/to/project)`. |

- Inline builds cannot be stopped. `StopAsyncBuild` stops your own queued or running async builds; only the session that asked for a build can stop it.
- Every full result starts with a `Command:` line showing the exact command that ran.
- An identical build already queued or running is shared, not run twice. Property maps match in any key order; goals, tasks, targets and profiles match only in the same order. The second session receives the same result.
- The IDE actions (`BuildProject`, `CleanProject`, `CleanAndBuildProject`) wait for the build where the project reports progress. Otherwise they return once triggered and say completion could not be confirmed. A running IDE action cannot be cancelled. Their `FAILED` or `COMPLETED` status does not confirm success; check the Output window.
- `RunProject` starts Run (or Debug) and returns at once, showing any dialog the project needs. Only you can stop it, from the Output window or the debugger.
- A wrong option type is refused up front. `properties` is a key/value map; `goals`, `tasks`, `targets`, `projectList` and `profiles` are string lists; Maven `threads` is `4` or `1C`.

| Tool | Description | Parameters |
|---|---|---|
| <a id="listbuilds"></a>`ListBuilds` | Shows the build queue and the last five finished builds, with each project's longest successful run. | none |
| <a id="stopasyncbuild"></a>`StopAsyncBuild` | Cancels one of your own queued or running async builds. | **`buildId`** — build to cancel |
| <a id="buildproject"></a>`BuildProject` | Runs the IDE's Build action. | **`projectPath`** — project to build<br>`async` (false) — queue it and return at once |
| <a id="cleanproject"></a>`CleanProject` | Runs the IDE's Clean action. | **`projectPath`** — project to clean<br>`async` (false) — queue it and return at once |
| <a id="cleanandbuildproject"></a>`CleanAndBuildProject` | Runs the IDE's Clean and Build action. | **`projectPath`** — project to rebuild<br>`async` (false) — queue it and return at once |
| <a id="runproject"></a>`RunProject` | Runs the project through the IDE, or debugs it. Not queued. | **`projectPath`** — project to run<br>`debug` (false) — debug instead of run |
| <a id="buildmavenproject"></a>`BuildMavenProject` | Maven `package -DskipTests`. | **`projectPath`** — project to build<br>`goals` (`["package"]`) — replace goals<br>`skipTests` (true) — include tests when false<br>`projectList`, `alsoMake`, `resumeFrom`, `offline`, `updateSnapshots`, `profiles`, `properties`, `threads`, `failAtEnd` — Maven options<br>`async` (false) — queue it and return at once |
| <a id="cleanandbuildmavenproject"></a>`CleanAndBuildMavenProject` | Maven `clean package -DskipTests`. | **`projectPath`** — project to build<br>`goals` (`["clean", "package"]`) — replace goals; include `clean` yourself when overriding<br>`skipTests` (true) — include tests when false<br>`projectList`, `alsoMake`, `resumeFrom`, `offline`, `updateSnapshots`, `profiles`, `properties`, `threads`, `failAtEnd` — Maven options<br>`async` (false) — queue it and return at once |
| <a id="runmaventests"></a>`RunMavenTests` | Runs Maven tests, all or one class. | **`projectPath`** — project to test<br>`testClass` — one test class; default all<br>`goals` (`["test"]`) — replace goals<br>`skipTests` (false) — skip tests when true<br>`projectList`, `alsoMake`, `resumeFrom`, `offline`, `updateSnapshots`, `profiles`, `properties`, `threads`, `failAtEnd` — Maven options<br>`async` (false) — queue it and return at once |
| <a id="buildgradleproject"></a>`BuildGradleProject` | Gradle `build -x check`. | **`projectPath`** — project to build<br>`tasks` (`["build"]`) — replace tasks<br>`skipTests` (true) — run verification when false<br>`offline`, `refreshDependencies`, `properties`, `systemProperties`, `parallel`, `continueOnFailure` — Gradle options<br>`async` (false) — queue it and return at once |
| <a id="cleanandbuildgradleproject"></a>`CleanAndBuildGradleProject` | Gradle `clean build -x check`. | **`projectPath`** — project to build<br>`tasks` (`["clean", "build"]`) — replace tasks; include `clean` yourself when overriding<br>`skipTests` (true) — run verification when false<br>`offline`, `refreshDependencies`, `properties`, `systemProperties`, `parallel`, `continueOnFailure` — Gradle options<br>`async` (false) — queue it and return at once |
| <a id="rungradletests"></a>`RunGradleTests` | Runs Gradle tests, all or one class. | **`projectPath`** — project to test<br>`testClass` — one test class; default all<br>`tasks` (`["test"]`) — replace tasks<br>`skipTests` (false) — skip tests when true<br>`offline`, `refreshDependencies`, `properties`, `systemProperties`, `parallel`, `continueOnFailure` — Gradle options<br>`async` (false) — queue it and return at once |
| <a id="buildantproject"></a>`BuildAntProject` | Ant `jar`. | **`projectPath`** — project to build<br>`targets` (`["jar"]`) — replace targets<br>`properties`, `keepGoing` — Ant options<br>`async` (false) — queue it and return at once |
| <a id="cleanandbuildantproject"></a>`CleanAndBuildAntProject` | Ant `clean jar`. | **`projectPath`** — project to build<br>`targets` (`["clean", "jar"]`) — replace targets; include `clean` yourself when overriding<br>`properties`, `keepGoing` — Ant options<br>`async` (false) — queue it and return at once |
| <a id="runanttests"></a>`RunAntTests` | Runs Ant tests, all or one class. | **`projectPath`** — project to test<br>`testClass` — one test class; default all<br>`targets` (`["test"]`) — replace targets<br>`properties`, `keepGoing` — Ant options<br>`async` (false) — queue it and return at once |
| <a id="downloadmavensources"></a>`DownloadMavenSources` | Downloads dependency source archives. Does not count as a build for the inline limit. | **`projectPath`** — project whose dependencies to fetch<br>`async` (false) — queue it and return at once |
| <a id="downloadmavenjavadoc"></a>`DownloadMavenJavadoc` | Downloads dependency Javadoc. Does not count as a build for the inline limit. | **`projectPath`** — project whose dependencies to fetch<br>`async` (false) — queue it and return at once |

### Search tools

Find text, files and Java symbols.

| Tool | Description | Parameters |
|---|---|---|
| <a id="searchinfiles"></a>`SearchInFiles` | Finds literal or regex text in the source of the open projects (up to 200 results; the total is reported). | **`query`** — text or regex to find<br>`filePath` — limit to that file's project; default all<br>`filePattern` (`*.java`) — file-name glob to search<br>`isRegex` (false) — treat the query as a regex<br>`caseSensitive` (false) — match letter case |
| <a id="filterfilecontent"></a>`FilterFileContent` | Shows the lines of one file that match a pattern, with optional context. | **`filePath`** — file to search<br>**`pattern`** — text or regex to match<br>`isRegex` (false) — treat the pattern as a regex<br>`caseSensitive` (false) — match letter case<br>`contextLines` (0, ≤50) — surrounding lines per match<br>`maxMatches` (200, ≤5000) — most matches to show |
| <a id="findfile"></a>`FindFile` | Finds files or directories by name. | `pattern` — name text or regex; omit to list all<br>`directoryPath` — where to search; default open projects<br>`type` (`file`\|`dir`; file) — what to match<br>`ignoreHidden` (true) — skip hidden files<br>`maxDepth` (≤100) — how many directory levels to search<br>`isRegex` (false) — treat the pattern as a regex<br>`caseSensitive` (false) — match letter case<br>`maxMatches` (200, ≤5000) — most results to list |
| <a id="searchtypes"></a>`SearchTypes` | Finds Java types by name across the open projects (up to 100). | **`name`** — type name or pattern<br>`filePath` — limit to that file's project<br>`kind` (`prefix`\|`exact`\|`camelCase`\|`regexp`; prefix) — how the name matches<br>`includeDeps` (false) — also search dependency JARs |
| <a id="searchsymbols"></a>`SearchSymbols` | Finds methods, fields and nested symbols by name (up to 100). | **`name`** — symbol name or pattern<br>`filePath` — limit to that file's project<br>`kind` (as `SearchTypes`) — how the name matches<br>`includeDeps` (false) — also search dependency JARs |
| <a id="finddeclaration"></a>`FindDeclaration` | Finds where the symbol at a location is declared. | **`filePath`** — file containing the symbol<br>**`line`** — 1-based line<br>`column` — position on the line; default first symbol |
| <a id="findimplementations"></a>`FindImplementations` | Lists the direct subtypes or implementors of a type. | **`filePath`** — file declaring the type<br>**`line`** — 1-based declaration line |
| <a id="findusages"></a>`FindUsages` | Finds references to a type or member. | **`className`** — type to find uses of<br>`memberName` — limit to one member<br>`findSubclasses` (false) — include subtypes<br>`directSubclassesOnly` (false) — direct subtypes only<br>`searchInComments` (false) — also search comments |
| <a id="getprojectstructure"></a>`GetProjectStructure` | Shows source roots and project layout. | none |
| <a id="getclassmembers"></a>`GetClassMembers` | Lists a class's fields, constructors and methods. | **`className`** — fully qualified class name |
| <a id="gettypehierarchy"></a>`GetTypeHierarchy` | Shows a type's supertypes and subtypes. | **`className`** — fully qualified class name |
| <a id="getjavadoc"></a>`GetJavadoc` | Shows classpath Javadoc and signatures. Download Maven Javadoc first if it is missing. | **`projectPath`** — project whose classpath to use<br>**`className`** — fully qualified class name<br>`memberName` — limit to one member |

### Refactoring and source tools

Refactorings use the NetBeans refactoring engine and update references. `commitWithWarning` applies a refactoring despite non-fatal problems; fatal problems always refuse.

| Tool | Description | Parameters |
|---|---|---|
| <a id="renamesymbol"></a>`RenameSymbol` | Renames a symbol and its references. | **`newName`** — the new identifier<br>**`filePath`** — file containing the symbol<br>**`line`** — 1-based line of the symbol<br>`commitWithWarning` (false) — proceed despite warnings |
| <a id="moveclass"></a>`MoveClass` | Moves Java classes to another package, updating imports and references. With `line` it moves just that class; without it the whole file moves, and a file with several top-level types is refused. | **`targetPackage`** — destination package<br>**`filePath`** — file to move; or `filePaths`<br>`filePaths` — several files, moved as one; rejects `line`<br>`line` — declaration line of the one class to move<br>`commitWithWarning` (false) — proceed despite warnings |
| <a id="inlinevariable"></a>`InlineVariable` | Inlines a local variable at its uses. | **`filePath`** — file containing the variable<br>**`line`** — 1-based declaration or use line<br>`commitWithWarning` (false) — proceed despite warnings |
| <a id="changemethodsignature"></a>`ChangeMethodSignature` | Changes a method's name, parameters or return type and updates callers. | **`filePath`** — file containing the method<br>**`line`** — 1-based line of the method<br>`methodName` — new name; default keep<br>`returnType` — new return type; default keep<br>`overloadMethod` — add an overload instead of changing<br>`parameters` — full new list; omit to keep, `[]` removes all; a new one needs `name`, `type`, `defaultValue`<br>`commitWithWarning` (false) — proceed despite warnings |
| <a id="getdiagnostics"></a>`GetDiagnostics` | Shows compiler diagnostics for the open Java files. | none |
| <a id="navigatetoline"></a>`NavigateToLine` | Opens a file in the editor, optionally at a line. | **`filePath`** — file to open<br>`line` — 1-based line to move to<br>`focus` (true) — give the editor focus |
| <a id="fiximports"></a>`FixImports` | Adds missing imports and saves the file. A name with several candidates is left unchanged and the candidates are listed. | **`filePath`** — file to fix<br>`pickBest` (false) — import the clear best candidate |
| <a id="organiseimports"></a>`OrganiseImports` | Removes unused imports, sorts the rest by the code style and saves the file. With compile errors it removes only provably unused imports. | **`filePath`** — file to tidy |
| <a id="organisemembers"></a>`OrganiseMembers` | Reorders class members by the configured order. | **`filePath`** — file to reorder |
| <a id="reformatfile"></a>`ReformatFile` | Reformats a file with the project's code style and saves it. | **`filePath`** — file to reformat |
| <a id="runinspect"></a>`RunInspect` | Opens the NetBeans Inspect (static analysis) window. | none |

### File tools

Read, write, copy, move and delete files. Writes open a diff for you to accept; copy, move and delete ask first.

| Tool | Description | Parameters |
|---|---|---|
| <a id="getfilecontent"></a>`GetFileContent` | Reads a file, or a line range of it. The default output has line numbers and a header; `raw` gives the exact text. | **`filePath`** — file to read<br>`startLine` — first line; default start<br>`endLine` — last line; default end<br>`raw` (false) — exact text, no line numbers |
| <a id="getfileinfo"></a>`GetFileInfo` | Shows a file's, directory's or link's size, line count, encoding, MIME type, timestamps, writability and unsaved-change state. | **`filePath`** — file, directory or link |
| <a id="getcurrentfile"></a>`GetCurrentFile` | Shows the active editor's file, line and column. | none |
| <a id="getcurrentfilecontent"></a>`GetCurrentFileContent` | Reads the active editor's content. | `raw` (false) — exact text, no header |
| <a id="getopenfiles"></a>`GetOpenFiles` | Lists the open editor files. | none |
| <a id="getselectedtext"></a>`GetSelectedText` | Returns the active editor's selection. | none |
| <a id="writefile"></a>`WriteFile` | Creates or replaces a file, after you accept the diff. | **`filePath`** — file to write<br>**`content`** — the full new content |
| <a id="applyedit"></a>`ApplyEdit` | Replaces exact text in a file, after you accept the diff. | **`filePath`** — file to edit<br>**`oldString`** — exact text to replace<br>**`newString`** — replacement text<br>`replaceAll` (false) — replace every match, not just the first<br>`expectedCount` — required match count; otherwise nothing changes |
| <a id="savefile"></a>`SaveFile` | Creates a new file from `content` (refuses an existing path), or saves the file's unsaved editor changes when `content` is omitted. | **`filePath`** — file to save<br>`content` — full new content for a new file; omit to save editor changes |
| <a id="copyfile"></a>`CopyFile` | Copies a file into a directory, after confirmation. | **`sourcePath`** — file to copy<br>**`targetDirectory`** — destination; must exist<br>`newName` — new name without extension |
| <a id="deletefile"></a>`DeleteFile` | Deletes a file and closes its editor, after confirmation. Directories are refused. | **`filePath`** — file to delete |
| <a id="createdirectory"></a>`CreateDirectory` | Creates a directory and any missing parents. Succeeds if it exists; refused if part of the path is a file. | **`filePath`** — directory to create |
| <a id="deletedirectory"></a>`DeleteDirectory` | Deletes a directory tree that holds no files (empty subdirectories only), after confirmation. Refuses otherwise, naming up to 5 files; also refuses an open project's root. | **`filePath`** — directory to delete |
| <a id="closefile"></a>`CloseFile` | Closes a file's editor tab. | **`filePath`** — file to close |
| <a id="refreshfilestatus"></a>`RefreshFileStatus` | Refreshes NetBeans file and VCS state. | `filePath` — file to refresh; default all open projects |
| <a id="webrequest"></a>`WebRequest` | Makes an HTTP/HTTPS request. Methods, headers and bodies need their permissions. Loopback and private-network destinations are refused, on every redirect too, unless allowed ([Settings](SETTINGS.md#permissions-in-detail)). | **`url`** — address to request<br>`method` (GET) — HTTP method<br>`headers` — request headers<br>`body` — request body<br>`timeoutSeconds` (30, 1–300) — request time limit<br>`maxChars` (20000, 1–200000) — most response characters returned |
| <a id="getclipboard"></a>`GetClipboard` | Reads clipboard text, when the clipboard permission is on. | none |
| <a id="movefile"></a>`MoveFile` | Moves a file; a `.java` move goes through refactoring. | **`sourcePath`** — file to move<br>**`targetDirectory`** — destination; must exist<br>`targetProjectPath` — open project to move into; default same project<br>`commitWithWarning` (false) — proceed despite warnings |

### Git tools

Git tools need **Allow git access**, plus **Allow read-only git commands** or **Allow git commands that modify the repository** ([Settings](SETTINGS.md#permissions-in-detail)). Which tool needs which is in [MCP tool access](MCP_TOOL_ACCESS.md#groups-and-categories). All take **`projectPath`** (the repository or project root) except `GitBlame`, where it is optional for an absolute `file`. Values needed only for some `action` or `operation` are checked when the tool runs.

| Tool | Description | Parameters |
|---|---|---|
| <a id="getgitstatus"></a>`GetGitStatus` | Shows the current branch and short file status. | **`projectPath`** — repository |
| <a id="getgitdiff"></a>`GetGitDiff` | Shows the staged or unstaged diff. | **`projectPath`** — repository<br>`staged` (false) — show the staged diff<br>`filePaths` — limit to these paths; default whole repository |
| <a id="gitadd"></a>`GitAdd` | Stages files, hunks or line ranges. At least one of `files` and `hunks` is required. | **`projectPath`** — repository<br>`files` — paths to stage; `["."]` stages all<br>`hunks` — parts to stage: `{file}`, `{file, hunk}`, `{file, fromLine, toLine}` or all three; `hunk` — 1-based from the unstaged `GetGitDiff`; `fromLine`/`toLine` — working-tree lines |
| <a id="gitcommit"></a>`GitCommit` | Commits the index, or the listed files after staging them. | **`projectPath`** — repository<br>**`message`** — commit message<br>`files` — stage these first; default commit the index |
| <a id="gitlog"></a>`GitLog` | Shows commit history. | **`projectPath`** — repository<br>`limit` (20, 1–1000) — most commits to show<br>`file` — limit to one path<br>`filePaths` — limit to these paths<br>`follow` (false) — follow renames; needs exactly one path |
| <a id="gitpush"></a>`GitPush` | Pushes the branch. | **`projectPath`** — repository<br>`remote` (origin) — remote to push to<br>`branch` — branch to push; default current |
| <a id="gitpull"></a>`GitPull` | Pulls from a remote. | **`projectPath`** — repository<br>`remote` (origin) — remote to pull from |
| <a id="gitcheckout"></a>`GitCheckout` | Switches branch, optionally creating it. | **`projectPath`** — repository<br>**`branch`** — branch to switch to<br>`create` (false) — create the branch |
| <a id="gitbranch"></a>`GitBranch` | Lists branches, or creates one from HEAD. | **`projectPath`** — repository<br>`all` (false) — include remote branches<br>`create` — name of a branch to create |
| <a id="gitdeletebranch"></a>`GitDeleteBranch` | Deletes a local branch. | **`projectPath`** — repository<br>**`branch`** — branch to delete<br>`force` (false) — delete even if unmerged |
| <a id="gitstash"></a>`GitStash` | Pushes, lists, pops, applies or drops stashes. | **`projectPath`** — repository<br>`action` (`push`\|`list`\|`pop`\|`apply`\|`drop`; push) — what to do<br>`index` (0) — which stash to pop, apply or drop<br>`message` (WIP) — stash message<br>`includeUntracked` (false) — stash untracked files too |
| <a id="gitfetch"></a>`GitFetch` | Fetches from a remote without merging. | **`projectPath`** — repository<br>`remote` (origin) — remote to fetch |
| <a id="gitreset"></a>`GitReset` | Resets the branch to a revision, or unstages files, hunks or line ranges. With neither `files` nor `hunks` it does a full reset of the given `type`. | **`projectPath`** — repository<br>`files` — paths to unstage<br>`hunks` — parts to unstage, as in `GitAdd`, against the staged diff; `hunk` — 1-based from `GetGitDiff` with `staged` true; `fromLine`/`toLine` — index lines<br>`revision` (HEAD) — revision to reset to<br>`type` (`SOFT`\|`MIXED`\|`HARD`; MIXED) — reset mode |
| <a id="gitmerge"></a>`GitMerge` | Merges a branch into the current one. | **`projectPath`** — repository<br>**`branch`** — branch to merge |
| <a id="gitshow"></a>`GitShow` | Shows a commit, with its date in local time. | **`projectPath`** — repository<br>`revision` (HEAD) — commit to show<br>`filePaths` — limit to these paths; default whole commit |
| <a id="gitblame"></a>`GitBlame` | Shows per-line authorship of a file. | **`file`** — file to blame<br>`projectPath` — repository; optional for an absolute path |
| <a id="gitrebase"></a>`GitRebase` | Starts, continues, skips or aborts a rebase. | **`projectPath`** — repository<br>`operation` (`BEGIN`\|`CONTINUE`\|`SKIP`\|`ABORT`; BEGIN) — what to do<br>`upstream` — branch to rebase onto; required for BEGIN |
| <a id="gitcherrypick"></a>`GitCherryPick` | Starts, continues, quits or aborts a cherry-pick. | **`projectPath`** — repository<br>`operation` (`BEGIN`\|`CONTINUE`\|`QUIT`\|`ABORT`; BEGIN) — what to do<br>`revisions` — commits to pick; required for BEGIN |
| <a id="gittag"></a>`GitTag` | Lists, creates or deletes tags. | **`projectPath`** — repository<br>`action` (`list`\|`create`\|`delete`; list) — what to do<br>`name` — tag name; for create and delete<br>`revision` (HEAD) — commit to tag<br>`message` — annotation text |
| <a id="gitremote"></a>`GitRemote` | Lists, adds or removes remotes. | **`projectPath`** — repository<br>`action` (`list`\|`add`\|`remove`; list) — what to do<br>`name` — remote name; for add and remove<br>`url` — remote URL; for add |
| <a id="gitrevert"></a>`GitRevert` | Creates a commit that undoes a revision. | **`projectPath`** — repository<br>`revision` (HEAD) — commit to undo |

### Database tools

`connectionName` is the display name of a connected connection in the Database Explorer (**Services > Databases**). Database access rules and limits are in [Settings](SETTINGS.md#permissions-in-detail).

| Tool | Description | Parameters |
|---|---|---|
| <a id="listdatabaseconnections"></a>`ListDatabaseConnections` | Lists registered connections and whether each is connected. | none |
| <a id="listtables"></a>`ListTables` | Lists the tables of a connected connection. | **`connectionName`** — connection to use |
| <a id="gettableschema"></a>`GetTableSchema` | Shows column names, types, nullability and primary keys. | **`connectionName`** — connection to use<br>**`tableName`** — table to describe |
| <a id="gettabledata"></a>`GetTableData` | Reads rows, read-only, up to the row limit. | **`connectionName`** — connection to use<br>**`tableName`** — table to read<br>`limit` (row limit) — most rows to return |
| <a id="executesqlquery"></a>`ExecuteSqlQuery` | Runs a read-only query subject to the [database settings](SETTINGS.md#permissions-in-detail). | **`connectionName`** — connection to use<br>**`sql`** — the query |

### Memory tools

Registered for **OpenAI Compatible** sessions only. Each works on the session's own notes ([Long-term memory](ai/openai_compatible/MEMORY.md)).

| Tool | Description | Parameters |
|---|---|---|
| <a id="memorysave"></a>`MemorySave` | Creates or replaces one note. Replies "created" or "replaced"; a value outside the limits is refused with the reason. | **`name`** — lowercase words joined by hyphens, e.g. `prefers-tabs`<br>**`description`** — one line shown in the index<br>**`content`** — the note text |
| <a id="memoryread"></a>`MemoryRead` | Shows a note's description and content. | **`name`** — note to read |
| <a id="memorydelete"></a>`MemoryDelete` | Deletes a note. | **`name`** — note to delete |

### Collaboration tools

These tools let sessions find and message one another; see [Collaboration](COLLABORATION.md).

| Tool | Description | Parameters |
|---|---|---|
| <a id="peersessionlist"></a>`PeerSessionList` | Lists other sessions: `sessionId`, `name`, `description`, `aiType`, `active` (true = while it is working) and `mailDelivery` (when that peer reads a message, and whether `important` changes it). | none |
| <a id="peermessagesend"></a>`PeerMessageSend` | Delivers a message to a peer's inbox. | **`targetSessionId`** — peer's session id or unique name<br>**`subject`** (≤100) — subject line<br>**`message`** (≤200,000) — message text<br>`replyToMessageId` — message being answered<br>`important` — interrupt the peer while it is working<br>`expectsReply` — notify if the peer exits unanswered<br>`replyImportant` — reply interrupts you; needs `expectsReply` |
| <a id="peermessagelist"></a>`PeerMessageList` | Lists inbox summaries, tagging messages that expect a reply `[Awaiting reply]` or `[Replied]`. Marks nothing read. | none |
| <a id="peermessageread"></a>`PeerMessageRead` | Reads one message in full and marks it read. The message stays until deleted. | **`messageId`** — message to read |
| <a id="peermessagedelete"></a>`PeerMessageDelete` | Deletes one or more inbox messages. A deleted message can still be answered; if it never is, its sender gets the no-reply notice when it expires or a session exits. | `messageId` — one message to delete<br>`messageIds` — several messages; at least one of the two |
| <a id="peermessagemarkreplied"></a>`PeerMessageMarkReplied` | Marks a message answered when you replied another way, so its sender gets no false no-reply notice. | **`messageId`** — message you answered |
| <a id="peersessionisactive"></a>`PeerSessionIsActive` | Shows whether a peer is not open, idle, or working (a message then waits for its turn to end). | **`targetSessionId`** — peer to check |
| <a id="peersessiondescribe"></a>`PeerSessionDescribe` | Sets the description peers see in `PeerSessionList`. | **`description`** — what peers see |
| <a id="peeridlewatchercreate"></a>`PeerIdleWatcherCreate` | Notifies the session when a peer has been idle between turns for the given time. Needs the session's idle watcher timer setting. The wait always starts at arming, even for an idle peer. | **`targetSessionId`** — peer to watch<br>`timeoutMinutes` (5, ≥2) — idle time before firing<br>`recurring` (false) — re-arm after the peer's next turn<br>`interrupt` (false) — interrupt your turn when it fires<br>`note` (≤500) — text included in the notice |
| <a id="peeridlewatchercancel"></a>`PeerIdleWatcherCancel` | Cancels one of your watchers. Harmless for an unknown or already fired id. | **`watcherId`** — watcher to cancel |
| <a id="peeridlewatcherlist"></a>`PeerIdleWatcherList` | Lists your watchers: target, whether the clock is running, idle-since and expected-fire times, whether it already fired. | none |

### Other tools

Ask you a question, show the plugin version, and return the tools' usage guide.

| Tool | Description | Parameters |
|---|---|---|
| <a id="askuserquestion"></a>`AskUserQuestion` | Puts choices to you in the chat. [Confirmations](CHANGE_REVIEW.md#confirmations) explains how its choices are handled. Registered only for Claude, Pi, Grok and OpenCode. | **`questions`** — list of questions to ask<br>each has **`question`** — the question text<br>**`options`** — choices, each with `label` and `description`<br>`header` (≤12 characters) — short label chip<br>`multiSelect` (false) — allow several answers |
| <a id="getpluginversion"></a>`GetPluginVersion` | Shows the running plugin version. | none |
| <a id="getinstructions"></a>`GetInstructions` | Returns the usage guide for the session's tools. | none |
