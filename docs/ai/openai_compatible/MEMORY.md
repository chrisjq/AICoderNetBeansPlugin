[← Back to README](../../../README.md) · [OpenAI Compatible backend](README.md)

# OpenAI Compatible: long-term memory

An [OpenAI Compatible](README.md) session has a long-term memory that survives IDE restarts, context compaction and the session's [↺ Reset](CONTEXT.md#reset). The model manages it through three MCP tools. You can read and edit the notes as plain files.

## The tools

[`MemorySave`](../../MCP_REFERENCE.md#memorysave), [`MemoryRead`](../../MCP_REFERENCE.md#memoryread) and [`MemoryDelete`](../../MCP_REFERENCE.md#memorydelete) form the switchable group **Session memory**, under the category **Memory (OpenAI Compatible only)** in MCP Tools. Like any group, it can be turned off for a session or left out of an MCP template ([MCP tool access](../../MCP_TOOL_ACCESS.md)).

- `MemorySave(name, description, content)` creates a note or replaces the one with that name. The reply says "created" or "replaced".
- `MemoryRead(name)` returns the note's description and content.
- `MemoryDelete(name)` removes it, for example when it turned out wrong.

| Field | Limit |
|---|---|
| Name | Lowercase letters and digits joined by hyphens, for example `prefers-tabs`; at most 64 characters |
| Description | One line, at most 200 characters; shown in the index |
| Content | At most 4,000 characters, so each note holds one fact |

## What the model is told

When the memory tools are offered, each turn's system prompt has a **Memory** section. It says the model has a long-term memory that survives restarts and resets, and tells it to:

- save what will matter later (the user's preferences, decisions and corrections);
- not save what the code, git history or conversation already holds;
- keep one fact per note;
- save under an existing name to replace that note;
- read a note in full by its name.

Below that is the index: "Saved notes, newest first:" and one `name: description` line per note. The index is capped at about 1,000 tokens. Notes that do not fit are counted as older notes not shown; the model can still read them by name. The section is rebuilt when a turn starts, so a note saved during one turn is in the next turn's prompt.

## Where the notes live

Each note is a file `<name>.md` in the `memory/` folder of the session's folder ([Files](README.md#files)). It starts with a small header holding the name and description, then the content, so any editor can read or correct it. A malformed file is skipped. Notes survive Reset and compaction and are deleted with the session ([Sessions](../../SESSIONS.md#creating-opening-deleting)).

Saves and deletes appear in the session's [backend debug log](../../SETTINGS.md#general-and-session-settings) by name only, never with the content.
