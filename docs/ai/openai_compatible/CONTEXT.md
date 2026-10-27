[← Back to README](../../../README.md) · [OpenAI Compatible backend](README.md)

# OpenAI Compatible: context window and compaction

The context window is the amount of conversation and tool output the model can consider at once. When it fills, AI Coder can compact old model-facing history while keeping the visible chat. You can use **⇒ Compact**, **≡ Summary**, **↺ Reset**, or a **Context window** override to control it.

This page covers the model-facing history of an [OpenAI Compatible](README.md) session; see [History and model context](../../SESSIONS.md#history-and-model-context). Numeric defaults are in [Advanced settings](ADVANCED.md).

## Context window

The first available source below supplies the window used for compaction and the gauge.

| Source | When it is used |
|---|---|
| **Context window** session override | Always; `0` means auto. |
| Server's loaded window | Ollama `context_length` or `num_ctx`, LM Studio's loaded length, llama.cpp `n_ctx`, or vLLM `max_model_len`. |
| Server overflow response | When the server states its limit in a context-overflow error. |
| Model metadata | A matching field from `/v1/models`. |
| Training-length upper bound | Ollama training length × the factor, LM Studio `max_context_length`, or llama.cpp `n_ctx_train`. |
| Assumed window | When no server value is available. |

- A **confirmed** window comes from the override or the first three server-derived rows. Without one, automatic compaction is off. A confirmed window too small for the tool definitions refuses the turn without sending: "Context window N is too small for tools". Above the minimum, when tools still do not fit, the Minimal-template message also refuses the turn without sending.
- The assumed window is used by Manual Compact and the tool-result cap. The tools/overhead check runs only against a confirmed window.
- Hints cover "Window unknown — set the context window in session settings to enable automatic compaction" and "Small context window".
- The gauge tooltip shows used/window tokens, the window source and the server kind.

## Measuring and trimming

Tokens are estimated from characters and calibrated against the `prompt_tokens` the server reports. The history is too large when its estimate exceeds window − reserve. The reserve is a percentage of the window between a minimum and a maximum share, or an absolute token count when one is set.

**Prune** runs first when **Shorten old tool output in the model's context** is on (default). Tool results older than the kept-recent turns and longer than the prune threshold are cut to the first few hundred characters plus "[pruned: M chars]". The first prune posts "Older tool output is being shortened in the model's context to save space; your chat keeps everything."

**Compaction** then applies the chosen strategy:

| Strategy | What it does |
|---|---|
| Auto (default) | Summarises when the window is at least the summarise threshold, otherwise drops the oldest messages |
| Summarise | Summarises the oldest messages when the context is full |
| Drop oldest | Drops the oldest messages when the context is full |
| None | Never compacts; the server decides what happens when the context is full |

- The most recent part of the history is kept verbatim (a percentage of the window with a minimum, or an absolute size). Cuts fall between turns, never between a tool call and its result.
- The **Summary model** (empty = the chat model) writes summaries from a structured template. A rejected summary gets one repair nudge, then falls back to dropping.
- Each compaction posts one system message: "Context compacted (N messages summarised)" or "Context trimmed (N earlier exchanges dropped)".
- A single tool result larger than the tool-result share of the window is cut in the model's context, with a note on how to read the rest. The chat keeps the full result.

## Compact, Summary and Save context on close

**Compact automatically when the context is full** (default on) compacts before a request when the history no longer fits a confirmed window.

**⇒ Compact** compacts now, using the assumed window when none is confirmed.

- Pressed during a reply, it says "Compaction waits until the current reply finishes" and runs afterwards.
- A prompt sent during a compaction is refused with "Compaction is running — try again when it finishes."
- "Nothing to compact: the context already fits the window" means the history already fits; nothing was changed.

**≡ Summary** opens the current summary in a read-only window with a **Copy** button. It is disabled with the tooltip "No summary yet", or "Context was trimmed, not summarised" after a drop-only compaction.

**Save context on close** (default on) writes the history, summary, prune placeholders and calibration to `context.json` in the session folder ([Files](README.md#files)) and restores them on reopen. Turning it off deletes the file.

## Reset

**↺ Reset**, after a confirmation, resets the model's memory to the start of the session. It clears the model-facing history, the summary and the prune state, and deletes the saved context file. It keeps the chat log, the session's instructions and pinned context, server and window detection, calibration, model decisions and the [long-term memory](MEMORY.md) notes. The chat shows "Context reset — the model no longer has the earlier conversation (the chat log above is kept)". Reset is refused while a reply or compaction is running.
