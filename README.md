# AI Coder for NetBeans

A NetBeans IDE plugin that embeds an AI coding assistant as a dockable chat panel with full IDE context awareness. It supports multiple AI backends — **[Claude Code](https://claude.ai/code)**, **[Grok](https://docs.x.ai/build/cli)**, and **GitHub Copilot** — behind a single shared chat UI and tool server. The assistant can read and edit your project files, run builds and tests, perform IDE refactorings, search your codebase, and ask you questions — all from within NetBeans.

## Supported backends

| Backend | Driven via | Models | Status |
|---|---|---|---|
| **Claude** | `claude` CLI (`--output-format stream-json`) | opus / sonnet / haiku (default `claude-sonnet-4-6`) | Enabled by default |
| **GitHub Copilot** | `copilot` CLI in prompt mode (`copilot -p --model …`) | discovered at runtime via the Copilot SDK (with a static fallback list) | Enabled by default |
| **Grok** | `grok` CLI in headless prompt mode (`grok -p --model …`) | discovered at runtime via `grok models` (with a static fallback list) | Enabled by default |

Each backend has its own process manager, executable locator, settings, and info bar, but they all share the same chat panel, MCP tool server, and Accept/Reject diff gate. You can run multiple sessions (and multiple backends) at once.

## Features

- **Streaming chat panel** — dockable AI conversation window with Markdown rendering and syntax-highlighted code blocks
- **Pluggable backends** — switch between Claude, Grok, and GitHub Copilot; each session picks its backend and model
- **Accept / Reject diffs** — file edits proposed by the AI are shown as a diff panel; you approve or reject each change before it is applied
- **MCP tool server** — exposes IDE tools (build, test, git, search, refactor, navigation, editor context) to the AI over a local HTTP/JSON-RPC 2.0 endpoint (port 6969 by default)
- **PreToolUse hook** — intercepts the AI's write/edit/create file operations and routes them through the diff panel
- **Inter-AI messaging** — multiple AI sessions can discover and message each other within the IDE (opt-in per session)
- **Concurrency guards** — mutating tool calls are serialised so parallel refactorings cannot corrupt IDE state

## Requirements

- NetBeans IDE 30.0+
- Java 21+ (plugin is built with `maven.compiler.release` 21)
- Maven (for Maven projects)
- At least one backend CLI installed and on your `PATH` (or configured in settings):
  - **Claude** — the Claude Code CLI (`claude`)
  - **Grok** — the xAI Grok CLI (`grok`), signed in via `grok login`
  - **GitHub Copilot** — the GitHub Copilot CLI (`copilot`), signed in to a Copilot-enabled GitHub account

## Installation

Build the plugin NBM and install it via **Tools > Plugins > Downloaded**:

```bash
mvn package
```

The built `.nbm` file is in `target/`. In NetBeans: **Tools > Plugins > Downloaded > Add Plugins**, select the `.nbm`, then click **Install**.

## Usage

**Manage AI sessions via Tools → AI Manager.** This opens the AI Manager dialog where you can:

- **Create** a new session — give it a name, pick the target open project, choose the backend (Claude, Grok, or GitHub Copilot), and click **Create & Open**. You can create several at once with the count field.
- **Open** an existing session — select it and click **Open** (its project must be open in the IDE).
- **Delete** a session and its saved history.

Each opened session is a dockable chat tab. Per-session overrides (history size, file/web/database access, inter-AI messaging, model, instructions) are available from the session's configuration; anything not overridden falls back to the global defaults in **Tools > Options > AI Coder**.

## Configuration

Open **Tools > Options > AI Coder**. General settings apply to every backend; each backend also has its own tab (Claude, Grok, GitHub Copilot) for executable path and model selection.

### General

| Setting | Default | Description |
|---|---|---|
| MCP server port | 6969 | Loopback port for the IDE tool server |
| Max history | 200 | Maximum conversation turns retained |
| Save history | true | Persist history between IDE sessions |
| Diff context lines | 3 | Lines of context shown in diff panel |
| Chat font size | 13pt | Font size for the chat panel |
| Debug JSON | false | Log raw JSON traffic to the IDE output window |

### Claude backend

| Setting | Default | Description |
|---|---|---|
| Claude executable | auto-detect | Path to the `claude` CLI binary |
| Model | claude-sonnet-4-6 | Model used for chat (opus / sonnet / haiku) |

> Authentication for Claude Code is handled by the `claude` CLI itself. Logon via the claude cli before use.

### GitHub Copilot backend

| Setting | Default | Description |
|---|---|---|
| Copilot executable | auto-detect | Path to the `copilot` CLI binary |
| Model | auto-discovered | Model list is fetched via the Copilot SDK at session start; falls back to a built-in list if discovery fails |

> Authentication for GitHub Copilot is handled by the `copilot` CLI itself. Logon via the copilot cli before use.

### Grok backend

| Setting | Default | Description |
|---|---|---|
| Grok executable | auto-detect | Path to the `grok` CLI binary |
| Model | auto-discovered | Model list is fetched via `grok models` at session start; falls back to a built-in list if discovery fails |

> Authentication for Grok is handled by the `grok` CLI itself. Logon via the grok cli before use.

## MCP Tool Reference

The plugin exposes the following tools to the AI assistant over the MCP endpoint at `http://127.0.0.1:<port>/mcp`. These tools are backend-agnostic — Claude, Grok, and GitHub Copilot use the same set.

### Build Code

| Tool | Description |
|---|---|
| `BuildMavenProject` | Runs `mvn package` |
| `CleanAndBuildMavenProject` | Runs `mvn clean package` |
| `BuildGradleProject` | Runs `gradlew build` |
| `BuildAntProject` | Runs `ant jar` |
| `DownloadMavenSources` | Downloads source JARs for all dependencies (enables source browsing) |
| `DownloadMavenJavadoc` | Downloads Javadoc JARs for all dependencies (run before `GetJavadoc`) |

### Test Code

| Tool | Description |
|---|---|
| `RunMavenTests` | Runs `mvn test` (optional class filter) |
| `RunGradleTests` | Runs `gradlew test` (optional class filter) |
| `RunAntTests` | Runs `ant test` (optional class filter) |

### Git

| Tool | Description |
|---|---|
| `GetGitStatus` | Branch name and short file status |
| `GetGitDiff` | Unstaged or staged changes |
| `GitAdd` | Stages files for the next commit |
| `GitCommit` | Commits staged changes with a message; can stage files first |
| `GitLog` | Recent commit history (short hash + subject); optionally scope to a single `file` (like `git log -- <file>`) and `follow` it across renames |
| `GitPush` | Pushes the current (or specified) branch to a remote |
| `GitPull` | Fetches from a remote and merges into the current branch |
| `GitCheckout` | Switches to a branch or revision (optionally creating it) |
| `GitBranch` | Lists local branches or creates a new one from HEAD |
| `GitDeleteBranch` | Deletes a local branch |
| `GitStash` | Stash, list, pop, apply, or drop stashed changes |
| `GitFetch` | Fetches from a remote without merging |
| `GitReset` | Unstages files or resets HEAD (SOFT/MIXED/HARD) |
| `GitMerge` | Merges a branch into the current branch |
| `GitShow` | Full details (author, date, message, diff) for a commit |
| `GitBlame` | Per-line commit hash, author, and content for a file |
| `GitRebase` | Rebases the current branch onto an upstream; supports continue/skip/abort |
| `GitCherryPick` | Cherry-picks one or more commits onto the current branch |
| `GitTag` | Lists, creates, or deletes tags |
| `GitRemote` | Lists, adds, or removes git remotes |
| `GitRevert` | Reverts a commit by creating a new inverse commit |

### Help & Information

| Tool | Description |
|---|---|
| `GetProjectStructure` | Project source-file layout, organised by source root |
| `GetClassMembers` | Fields, methods, and constructors of a class |
| `GetTypeHierarchy` | Full supertype/subtype tree for a class or interface |
| `GetJavadoc` | Javadoc and method signatures for any class or member on the classpath |

### Database (read-only)

| Tool | Description |
|---|---|
| `ListDatabaseConnections` | Lists all registered Database Explorer connections and their connection status |
| `ListTables` | Lists all tables in a database schema |
| `GetTableSchema` | Returns column names, types, nullability, and primary keys for a table |
| `GetTableData` | Fetches up to the configured row limit of a table's data (SELECT *) |
| `ExecuteSqlQuery` | Runs a read-only SELECT query on a database connection (SELECT enforced) |

> **Note:** Database access must be enabled in **Tools > Options > AI Coder > General** or in session settings. All database operations are read-only; INSERT, UPDATE, DELETE, and DDL statements are blocked.

### Refactoring (IDE-safe — all references updated automatically)

| Tool | Description |
|---|---|
| `RenameSymbol` | Rename any identifier across all files in the project |
| `MoveClass` | Move a Java class to a different package, updating imports |
| `MoveFile` | Move a file; Java files use move refactoring (updates package + imports) |
| `InlineVariable` | Inline a variable at all use sites and remove the declaration |
| `ChangeMethodSignature` | Modify a method's parameters, name, or return type and update all callers |

### Search/Find (IDE-aware)

| Tool | Description |
|---|---|
| `SearchInFiles` | Grep-style text/regex search across Java source files |
| `SearchTypes` | Find Java types (class/interface/enum/annotation) by name pattern |
| `SearchSymbols` | Find methods, fields, and nested types by name |
| `FindDeclaration` | Go-to-definition: resolve a symbol to its declaration |
| `FindImplementations` | Find all direct subtypes/implementors of a type |
| `FindUsages` | Find all usages of a class or method across the project |

### Core System Functions

| Tool | Description |
|---|---|
| `GetFileContent` | Read a file's in-memory content, including unsaved editor changes |
| `WebRequest` | Fetch an HTTP or HTTPS URL with optional method, headers, request body, timeout, and response truncation. Supports GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS |
| `GetClipboard` | Read the current system clipboard text |
| `SaveFile` | Create/overwrite a file with content and save, or flush unsaved editor changes to disk |
| `DeleteFile` | Permanently delete a file, closing its editor tab and refreshing VCS status |
| `CopyFile` | Copy a file to a target directory, optionally renaming the copy |
| `RefreshFileStatus` | Refreshes NetBeans' filesystem and VCS view — call after git commits and after creating or modifying files outside the IDE |

### UI → Build

| Tool | Description |
|---|---|
| `BuildProject` | IDE build action (auto-detects Maven/Gradle/Ant) |
| `CleanProject` | IDE clean action for any project type |
| `CleanAndBuildProject` | IDE clean+build action for any project type |

### UI → Files & Text

| Tool | Description |
|---|---|
| `WriteFile` | Create/overwrite a file with content, approved via the diff panel |
| `ApplyEdit` | Replace an exact string in a file, approved via the diff panel |
| `GetCurrentFile` | Path, line, and column of the active editor cursor |
| `GetCurrentFileContent` | Full text content of the active editor tab |
| `GetOpenFiles` | List of all files currently open in the IDE |
| `GetSelectedText` | Currently selected/highlighted text in the active editor |
| `GetDiagnostics` | Compiler errors and warnings for all open Java files |
| `CloseFile` | Close an editor tab |

### UI → Navigation

| Tool | Description |
|---|---|
| `NavigateToLine` | Opens a file in the editor and jumps to a given line |

### UI → Source Code Formatting

| Tool | Description |
|---|---|
| `FixImports` | Removes unused imports and adds missing ones |
| `OrganiseImports` | Sorts and groups existing import statements |
| `OrganiseMembers` | Sorts class members according to configured member order |
| `ReformatFile` | Reformats a file using the project's code style settings |

### UI → Dialog Actions

| Tool | Description |
|---|---|
| `RunInspect` | Opens the NetBeans Inspect dialog to run static analysis across the codebase |

### Request Input from User

| Tool | Description |
|---|---|
| `AskUserQuestion` | Present the user with a question and selectable options |

### Plugin & Inter-AI Messaging

| Tool | Description |
|---|---|
| `GetPluginVersion` | Returns the running version of the NetBeans plugin |
| `GetInstructions` | Returns the full plugin usage guide (must be called once before other plugin tools) |
| `ListAiSessions` | Discover peer AI sessions open in the IDE (excludes the caller) |
| `SendAiMessage` | Send a message to another AI session's inbox |
| `GetAiMessages` | List inbox message summaries (id, subject, from) |
| `ReadAiMessage` | Read the full body of a specific inbox message and mark it read |
| `DeleteAiMessage` | Delete one or more inbox messages by id |
| `IsAiSessionActive` | Check whether a target AI session is idle or busy |
| `UpdateSessionDescription` | Update your session's description visible to peer sessions |

## WebRequest Tool Details

The **WebRequest** tool allows the AI assistant to fetch external web resources. It supports full HTTP method control, custom headers, request bodies, timeouts, and response truncation.

### Parameters

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `url` | string | ✓ | — | The HTTP or HTTPS URL to fetch |
| `method` | string | — | GET | HTTP method (GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS) |
| `headers` | object | — | none | Request headers as a JSON object of `headerName → value` |
| `body` | string | — | none | Request body (primarily useful with POST, PUT, PATCH, DELETE) |
| `timeoutSeconds` | integer | — | 30 | Request timeout in seconds (range: 1-300) |
| `maxChars` | integer | — | 20000 | Maximum response body characters to return (range: 1-200,000) |

### Response

The tool returns a JSON object with:

| Field | Description |
|---|---|
| `requestedUrl` | The original URL requested |
| `finalUrl` | The final URL after following redirects |
| `method` | The HTTP method used |
| `status` | HTTP status code |
| `headers` | Response headers (single-value as string, multi-value as array) |
| `truncated` | Boolean indicating if response body was truncated |
| `body` | Response body (UTF-8 decoded, with charset auto-detection from Content-Type) |

### Example

```json
{
  "url": "https://api.github.com/zen",
  "method": "GET",
  "maxChars": 500
}
```

## Architecture

```
NetBeans IDE
  └── AI Coder panel (dockable TopComponent)
        ├── Backend (Claude | Grok | GitHub Copilot)
        │     └── process manager  →  backend CLI subprocess
        └── McpHookServer  (loopback HTTP, port 6969)
              ├── /mcp   — MCP Streamable HTTP endpoint (tool calls)
              └── /      — PreToolUse hook (diff-panel gate for file writes)
```

Each backend drives its CLI as a subprocess and parses its streaming output to render the chat. The **Claude** backend connects via the MCP configuration written to `~/.claude/mcp.json`; the **Grok** and **GitHub Copilot** backends run their CLIs (`grok` / `copilot`) in headless prompt mode and are wired to the tool server through their own MCP registrars. In every case the hook server intercepts write/patch/create operations and presents a diff to the user before allowing or denying them.

Mutating tool calls (builds, refactorings, file writes) are serialised through a fair `ReentrantLock` to prevent concurrent IDE state corruption. Read-only tools bypass the lock entirely.

## Security

The MCP tool server exposes powerful IDE capabilities (file writes, builds, git, shell-adjacent refactors) to an AI backend, so access to it is deliberately constrained. The relevant controls:

- **Loopback-only binding** — the HTTP server binds to the loopback address (`127.0.0.1` / `[::1]`) via `InetAddress.getLoopbackAddress()`, never a routable interface. It is not reachable from other hosts on the network; only processes on the same machine can connect. The Claude MCP registrar additionally refuses to write any endpoint URL that is not loopback.
- **Per-session authentication** — every session is issued a random secret at creation. Each `tools/call` (and every inter-AI messaging tool) must carry the caller's `sessionId` **and** matching `secretKey`; requests missing or failing this check are rejected with an authentication error before any tool runs. Secrets are compared in constant time (`MessageDigest.isEqual`) to avoid timing leaks. Secrets are held in memory only and are regenerated for each new session — they are not persisted to disk.
- **Session isolation** — a valid `secretKey` authenticates only its own `sessionId`. A session cannot read another session's inbox, edit its description, or act on its behalf without that session's own secret.
- **Accept/Reject diff gate** — all AI-initiated file writes, edits, and creates are intercepted by the PreToolUse hook and routed through the NetBeans diff panel. No change touches your working tree until you explicitly approve it.
- **Read-only database access** — database tools are disabled unless you opt in (global or per-session), and even then only `SELECT` is permitted; `INSERT`/`UPDATE`/`DELETE`/DDL are blocked, enforced by both a statement-prefix check and a read-only JDBC connection.
- **Optional project-file scoping** — sessions can be restricted so file access stays within the session's open project directories.
- **Connection guardrails** — the server caps concurrent and idle connections and enforces request/response timeouts to limit runaway or stuck clients.

> **Note:** These controls guard the tool server itself. They do not replace trust in the AI backend or its CLI — the assistant still runs builds/tests and can propose any edit. The Accept/Reject gate is your final review step, so read diffs before approving. Because the port is loopback-only, treat any local process able to read your session's `secretKey` (e.g. from process arguments, logs, or the MCP config file) as able to drive the tool server — keep those readable only by your user account.

## Development

```bash
# Build
mvn package

# Run tests
mvn test

# Install into a running NetBeans (requires nbm-maven-plugin)
mvn nbm:run-ide
```

Tests are in `src/test/java` and use JUnit 5. The `McpToolTest` suite verifies that every `McpToolEnum` constant has a registered handler and that the tool/section enumeration is consistent.

## License

Copyright (c) 2026 Chris Quin.

This project is licensed under the [MIT License](LICENSE) — see the [LICENSE](LICENSE) file for details.
