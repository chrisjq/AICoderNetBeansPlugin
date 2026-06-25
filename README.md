# AI Coder for NetBeans

A NetBeans IDE plugin that embeds an AI coding assistant as a dockable chat panel with full IDE context awareness. It supports multiple AI backends — **[Claude Code](https://claude.ai/code)** and **GitHub Copilot** — behind a single shared chat UI and tool server. The assistant can read and edit your project files, run builds and tests, perform IDE refactorings, search your codebase, and ask you questions — all from within NetBeans.

## Supported backends

| Backend | Driven via | Models | Status |
|---|---|---|---|
| **Claude** | `claude` CLI (`--output-format stream-json`) | opus / sonnet / haiku (default `claude-sonnet-4-6`) | Enabled by default |
| **GitHub Copilot** | `copilot` CLI in prompt mode (`copilot -p --model …`) | discovered at runtime via the Copilot SDK (with a static fallback list) | Enabled by default |

Each backend has its own process manager, executable locator, settings, and info bar, but they all share the same chat panel, MCP tool server, and Accept/Reject diff gate. You can run multiple sessions (and multiple backends) at once.

## Features

- **Streaming chat panel** — dockable AI conversation window with Markdown rendering and syntax-highlighted code blocks
- **Pluggable backends** — switch between Claude and GitHub Copilot; each session picks its backend and model
- **Accept / Reject diffs** — file edits proposed by the AI are shown as a diff panel; you approve or reject each change before it is applied
- **MCP tool server** — exposes IDE tools (build, test, git, search, refactor, navigation, editor context) to the AI over a local HTTP/JSON-RPC 2.0 endpoint (port 6969 by default)
- **PreToolUse hook** — intercepts the AI's write/edit/create file operations and routes them through the diff panel
- **Inter-AI messaging** — multiple AI sessions can discover and message each other within the IDE (opt-in per session)
- **Concurrency guards** — mutating tool calls are serialised so parallel refactorings cannot corrupt IDE state

## Requirements

- NetBeans IDE 26.0+
- Java 17+
- Maven (for Maven projects)
- At least one backend CLI installed and on your `PATH` (or configured in settings):
  - **Claude** — the Claude Code CLI (`claude`)
  - **GitHub Copilot** — the GitHub Copilot CLI (`copilot`), signed in to a Copilot-enabled GitHub account

## Installation

Build the plugin NBM and install it via **Tools > Plugins > Downloaded**:

```bash
mvn package
```

The built `.nbm` file is in `target/`. In NetBeans: **Tools > Plugins > Downloaded > Add Plugins**, select the `.nbm`, then click **Install**.

## Configuration

Open **Tools > Options > AI Coder**. General settings apply to every backend; each backend also has its own tab (Claude, GitHub Copilot) for executable path and model selection.

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

## MCP Tool Reference

The plugin exposes the following tools to the AI assistant over the MCP endpoint at `http://127.0.0.1:<port>/mcp`. These tools are backend-agnostic — both Claude and GitHub Copilot use the same set.

### Build

| Tool | Description |
|---|---|
| `BuildMavenProject` | Runs `mvn package` |
| `BuildGradleProject` | Runs `gradlew build` |
| `BuildAntProject` | Runs `ant jar` |
| `BuildProject` / `CleanProject` / `CleanAndBuildProject` | IDE build actions |
| `DownloadMavenSources` | Downloads source JARs for library source browsing |
| `DownloadMavenJavadoc` | Downloads Javadoc JARs (run before `GetJavadoc`) |

### Test

| Tool | Description |
|---|---|
| `RunMavenTests` | Runs `mvn test` (optional class filter) |
| `RunGradleTests` | Runs `gradlew test` (optional class filter) |
| `RunAntTests` | Runs `ant test` (optional class filter) |

### Git

| Tool | Description |
|---|---|
| `GetGitStatus` | Branch name and file status |
| `GetGitDiff` | Unstaged or staged changes |

### Help & Navigation

| Tool | Description |
|---|---|
| `GetProjectStructure` | Project layout overview |
| `GetClassMembers` | Fields, methods, and constructors of a class |
| `GetTypeHierarchy` | Full supertype/subtype tree |
| `GetJavadoc` | Javadoc for any class or member |
| `NavigateToLine` | Opens a file in the editor at a given line |

### Search

| Tool | Description |
|---|---|
| `SearchInFiles` | Full-text search across project files |
| `SearchTypes` | Find types by name |
| `SearchSymbols` | Find symbols by name |
| `FindDeclaration` | Jump to declaration |
| `FindImplementations` | Find all implementations of an interface/method |
| `FindUsages` | Find all usages of a symbol |

### Refactoring (IDE-safe — all references updated automatically)

| Tool | Description |
|---|---|
| `RenameSymbol` | Rename any identifier |
| `MoveClass` | Move a class to a different package |
| `InlineVariable` | Inline a local variable |
| `ChangeMethodSignature` | Modify a method's parameters or return type |

### Source Formatting

| Tool | Description |
|---|---|
| `FixImports` | Add missing imports and remove unused ones |
| `OrganiseImports` | Sort and group import statements |
| `OrganiseMembers` | Sort class members by configured order |
| `ReformatFile` | Apply project code-style formatting |

### File & Editor Context

| Tool | Description |
|---|---|
| `GetFileContent` | Read any file by absolute path |
| `GetCurrentFile` | Path of the active editor tab |
| `GetCurrentFileContent` | Content of the active editor tab |
| `GetOpenFiles` | All currently open editor tabs |
| `GetSelectedText` | Currently selected text |
| `GetDiagnostics` | Compiler errors and warnings |
| `GetClipboard` | Current clipboard text |
| `SaveFile` | Save a file to disk |
| `CloseFile` | Close an editor tab |
| `RefreshFileStatus` | Refreshes NetBeans' filesystem and VCS view — call after git commits and after creating or modifying files outside the IDE so NetBeans detects them immediately |

### Other

| Tool | Description |
|---|---|
| `AskUserQuestion` | Present the user with a question and selectable options |
| `RunInspect` | Run the IDE code inspection |
| `GetPluginVersion` | Returns the running plugin version |

## Architecture

```
NetBeans IDE
  └── AI Coder panel (dockable TopComponent)
        ├── Backend (Claude | GitHub Copilot)
        │     └── process manager  →  backend CLI subprocess
        └── McpHookServer  (loopback HTTP, port 6969)
              ├── /mcp   — MCP Streamable HTTP endpoint (tool calls)
              └── /      — PreToolUse hook (diff-panel gate for file writes)
```

Each backend drives its CLI as a subprocess and parses its streaming output to render the chat. The **Claude** backend connects via the MCP configuration written to `~/.claude/mcp.json`; the **GitHub Copilot** backend runs the `copilot` CLI in prompt mode and is configured through the Copilot MCP registrar. In both cases the hook server intercepts write/patch/create operations and presents a diff to the user before allowing or denying them.

Mutating tool calls (builds, refactorings, file writes) are serialised through a fair `ReentrantLock` to prevent concurrent IDE state corruption. Read-only tools bypass the lock entirely.

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
