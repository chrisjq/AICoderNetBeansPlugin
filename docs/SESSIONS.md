[← Back to README](../README.md)

# Sessions, history and context

See also: [Settings](SETTINGS.md) · [Collaboration](COLLABORATION.md) · [Backends](BACKENDS.md) · [Long-term memory](ai/openai_compatible/MEMORY.md)

A session is one chat tab: one backend, one model, one working project, its own settings, history and backend state. Several sessions, on the same or different backends, run at once.

## What a session holds

A session definition is saved under `~/.netbeans/.aicoder` in your home folder: name, description, backend and its settings, generic settings, project and timestamps. Opening a saved session restores its chat history and working directory where they are still valid.

A backend may also keep per-session files under `~/.ai-coder/<backend key>/<sessionId>/` (`%APPDATA%\AI Coder` instead of `~/.ai-coder` on Windows). Examples are the [backend debug log](SETTINGS.md#general-and-session-settings) and, for OpenAI Compatible, the saved model context and memory notes ([its files](ai/openai_compatible/README.md#files)).

## Creating, opening, deleting

Sessions are created, opened and deleted in the AI Manager ([UI guide](UI_GUIDE.md#ai-manager)) and configured in their own Session Configuration ([UI guide](UI_GUIDE.md#session-configuration)). A new session starts from the templates chosen in Create Session ([Templates](TEMPLATES.md#choosing-templates-when-creating-a-session)) and the backend's defaults. Deleting a session, after confirmation, removes its saved definition, its chat history and its backend's session folder.

## Session instructions

A session can carry its own instructions, typed in Session Configuration or taken from a session-instruction template. They are delivered when the session starts (**Inject Session Instructions on Create**) or with the first user request. The session records the delivery so it is not repeated.

## History and model context

Two things are kept separately: the **chat history** you see and the **model context** the backend works with.

- A positive **Max history** drops the oldest messages from the chat history and its saved history; 0 keeps them all.
- Each backend manages the model context itself: a CLI backend keeps it inside the CLI; OpenAI Compatible keeps and compacts it in AI Coder ([Context window and compaction](ai/openai_compatible/CONTEXT.md)).
- Either can be rebuilt without the other. Corrupt saved data is ignored and rebuilt rather than blocking the session.

## What the assistant is told

On first delivery the assistant receives the session's identity, the open project locations and the active-editor context. Later requests carry only what changed in the active file and project; a backend that keeps no conversation of its own gets the full picture again each time.

## Closing a tab

Closing a chat tab asks whether to **Delete session**, **Keep for later** or **Cancel**. With the Options setting **Save Session by default When Closing Tab (If History is Saved) - Don't ask** on, the prompt is skipped: sessions with **Save** ticked are kept, the rest are deleted.

