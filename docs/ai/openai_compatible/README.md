[← Back to README](../../../README.md)

# OpenAI Compatible backend

See also: [Backends](../../BACKENDS.md) · [UI guide](../../UI_GUIDE.md) · [Collaboration](../../COLLABORATION.md)

Use this backend for [LM Studio](https://lmstudio.ai), [llama.cpp](https://github.com/ggml-org/llama.cpp), [vLLM](https://docs.vllm.ai), LocalAI, [Ollama](https://ollama.com)'s `/v1` endpoint, and hosted OpenAI-compatible APIs. It connects AI Coder to the server you already run or subscribe to.

To connect a local server, follow [Run a local model](../../howto/LOCAL_MODEL.md). For Ollama, choose **Ollama** in **Server type**; for LocalAI, choose **OpenAI-compatible (other / hosted)**.

## Where the settings are

- The **OpenAI Compatible** tab in AI Coder options ([Settings](../../SETTINGS.md)) holds defaults for new sessions and plugin-wide **Advanced** values.
- **Create Session** and **Session Configuration** hold per-session values, including per-session **Advanced** settings.

An empty session field uses the plugin default and shows "(default: X)".

## Server type and detection

### Connect

Choose a **Server type**, enter or confirm **Base URL**, then choose **Test connection**.

- **Server type** can be Auto-detect, Ollama, LM Studio, llama.cpp, vLLM or OpenAI-compatible (other / hosted). Choosing a known type fills **Base URL** with that server's default local URL when the field is empty or holds another preset's URL.
- **Test connection** checks the URL and the key currently in the field. It reports **Connected**, **API key rejected**, or **N/A**; **N/A** means the server cannot be reached.
- The read-only **Detected** panel shows the server kind, reachability, API base, streaming, capabilities, context-window information, compaction, tool-calling and models.
- If an explicit type does not match the server, the **⚠** hint recommends Auto-detect; chat is still attempted.

### Details

AI Coder removes a trailing `/`, `/v1` or `/chat/completions` from **Base URL** before sending requests. Auto-detect checks Ollama, LM Studio, llama.cpp and vLLM in that order, then treats any other responding server as generic. Detection runs in the background when the session starts, after settings change and at its configured interval.

## API key and extra headers

An API key is optional. Each session keeps its own key in the **NetBeans keyring** (the IDE's secure password store), never in the session file. Choose **Change** or **Clear** beside the key status; the change takes effect when you press **OK**. If the keyring cannot store the key, **OK** says so and the dialog stays open so you can try again. A session without its own key uses the default key from the **OpenAI Compatible** tab in **Tools > Options > Miscellaneous > AI Coder** ([Settings](../../SETTINGS.md)) only when its **Base URL** has the same server root as the default **Base URL**.

The **⚠** hint explains unavailable, missing or rejected keys. It also warns before sending a key over unencrypted `http://` to a non-local host.

**Extra headers** are in **Advanced > Request**. Headers required by the HTTP client cannot be overridden, and **Authorization** is refused while an API key is set. Values of secret-looking headers stay in the keyring; logs record header names, never their values.

## Model and effort

**Model** is an editable list populated from the server; you can type a model ID. **Reasoning effort** is sent only when selected. When the server reports levels for a model, AI Coder uses those; otherwise it offers its standard levels. If the server rejects the field, it is omitted for the rest of that session.

**Show the model's reasoning in the chat** is off by default. When enabled, the model's reasoning (reasoning fields and `<think>` text) is shown in the chat as a quoted block before the answer. It is never sent back to the model or summarised.

A model or effort choice in the [info bar](#info-bar) applies from the next turn. Changing the model re-runs detection.

## Info bar

The info bar has the model and effort lists, **⚠**, the context gauge, **⇒ Compact**, **≡ Summary** and **↺ Reset**. The controls are locked while a reply or compaction runs. Context actions are explained in [Context window and compaction](CONTEXT.md).

**⚠** appears when something needs attention. Select it for the full list and **Copy** button. Its high-priority hints cover:

- server unreachable, a rejected or unavailable key, and missing secret-header values;
- tools that do not fit the context window, a server cutting the prompt, a small or unknown window;
- a server-type mismatch, or tool definitions using much of the window.

For a small window, see the [Minimal MCP template](../../TEMPLATES.md#mcp-templates).

## Tool calling

**Tool calling** can be **Auto**, **Native** or **Schema**.

- **Native** sends standard OpenAI tool definitions.
- **Schema** puts the tool list in the prompt and asks for one JSON tool call or answer.
- **Auto** uses native tools where supported and changes to schema when they are not, or after the configured native failures.

After repeated native tool-call failures, AI Coder switches to schema from the next turn and starts a fresh model-facing context, keeping the summary if there is one; the visible chat is kept. The offered tools follow the session's [MCP Tools](../../MCP_TOOL_ACCESS.md) settings. The model reaches the IDE through plugin tools, not a separate shell or file tool.

## Files

A session stores its backend data in `~/.ai-coder/openai_compatible/<sessionId>/`:

- `context.json` holds saved model-facing context ([Context window and compaction](CONTEXT.md)).
- `backend-debug.log`, the optional backend debug log (each session has its own).
- `memory/` holds [long-term memory](MEMORY.md) notes.

Deleting the session removes the folder and its keyring entries.

## Further details

[Advanced settings](ADVANCED.md) lists timeouts and limits. [Context window and compaction](CONTEXT.md) explains the gauge, compaction and Reset. [Long-term memory](MEMORY.md) explains persistent model-managed notes.
