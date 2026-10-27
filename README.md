# AI Coder for NetBeans

AI Coder is a NetBeans plugin for dockable, multi-session AI coding chats with IDE-aware context, project-scoped tools, configurable permissions and reviewable file changes. Use it as a coding assistant, or build AI teams that implement projects with you and review each other's work.

Please consult the readme that applies to your version of the plugin you are running.

## Supported backends

[Claude](docs/ai/claude/README.md), [GitHub Copilot](docs/ai/github_copilot/README.md), [Grok](docs/ai/grok/README.md), [Codex](docs/ai/codex/README.md), [Pi](docs/ai/pi/README.md), [OpenCode](docs/ai/opencode/README.md) and [OpenAI Compatible](docs/ai/openai_compatible/README.md) (LM Studio, llama.cpp, vLLM, Ollama, hosted APIs), compared in [Backends](docs/BACKENDS.md). Each session has its own backend (the AI service it talks to), model, settings, project, chat history and instructions. Sessions of different backends run side by side.

**Ollama (Local)** is deprecated and off by default. Use OpenAI Compatible with server type Ollama instead. Existing Ollama (Local) sessions keep working.

## What it provides

- Streaming Markdown chat with syntax-highlighted code, tool activity and status, in dockable chat tabs.
- AI Manager for creating, opening and deleting sessions, with reusable **Config Template**, **MCP Template** and **Session Instructions** templates.
- Per-session instructions and IDE context; chat history saved between IDE restarts when Save history is on ([Sessions](docs/SESSIONS.md#history-and-model-context)).
- Usage and context gauges where a backend reports them.
- Inter-AI messaging between opted-in sessions, with inbox notices, interruption and idle watchers.
- NetBeans-aware search, navigation, diagnostics, build, test, refactoring, Git, file, database and web tools, with diff review for AI-proposed writes and confirmation for destructive actions.

## Requirements

- NetBeans IDE 22 or newer; Java 17 or newer.
- Maven to build this plugin and to use its Maven tools; Gradle or Ant for their tools.
- At least one backend; what to install and how to sign in is on its page.

> NetBeans must be able to launch the configured CLIs and use loopback networking. Sandboxed installations that block process creation, the host `PATH` or local HTTP connections can break CLI, ACP and app-server backends and the MCP tool server.

## Installation

```bash
mvn package
```

Install the `.nbm` from `target/` with **Tools > Plugins > Downloaded > Add Plugins**.

## Quick start

Follow the [Getting started tutorial](docs/GETTING_STARTED.md).

## Documentation

### Tutorial

- [Getting started](docs/GETTING_STARTED.md)

### How-to guides

- [Run a local model](docs/howto/LOCAL_MODEL.md)
- [Set up an AI team](docs/howto/AI_TEAM.md)
- [Limit what a session can touch](docs/howto/LIMIT_ACCESS.md)
- [Troubleshoot a session](docs/howto/TROUBLESHOOTING.md)

### Reference

- [UI guide](docs/UI_GUIDE.md) · [Settings](docs/SETTINGS.md) · [MCP tool reference](docs/MCP_REFERENCE.md)
- [MCP tool access](docs/MCP_TOOL_ACCESS.md) · [Templates](docs/TEMPLATES.md)
- [Claude](docs/ai/claude/README.md) · [GitHub Copilot](docs/ai/github_copilot/README.md) · [Grok](docs/ai/grok/README.md) · [Codex](docs/ai/codex/README.md) · [Pi](docs/ai/pi/README.md) · [OpenCode](docs/ai/opencode/README.md)
- [OpenAI Compatible](docs/ai/openai_compatible/README.md): [context](docs/ai/openai_compatible/CONTEXT.md), [advanced settings](docs/ai/openai_compatible/ADVANCED.md), [memory](docs/ai/openai_compatible/MEMORY.md)

### Explanation

- [Sessions](docs/SESSIONS.md) · [Change review](docs/CHANGE_REVIEW.md)
- [Collaboration](docs/COLLABORATION.md) · [Backends](docs/BACKENDS.md)

## Architecture

```text
NetBeans IDE
  └── AI Coder dockable sessions
        ├── backend sessions (CLI, SDK, ACP, app-server, HTTP)
        └── Local MCP tool server and IDE review bridge
```

Backends sit behind a common session and UI model. Shared components handle rendering, persistence, IDE context, permissions, tools and change review.

## Development

```bash
mvn package
mvn test
mvn nbm:run-ide
```

Tests are under `src/test/java`.

## License

Copyright (c) 2026 Chris Quin. Licensed under the [MIT License](LICENSE).
