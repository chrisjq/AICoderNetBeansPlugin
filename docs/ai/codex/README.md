[← Back to README](../../../README.md)

# Codex backend

See also: [Backends](../../BACKENDS.md) · [Change review](../../CHANGE_REVIEW.md) · [Collaboration](../../COLLABORATION.md)

Codex runs the `codex` CLI and app-server for each session.

## Setup

- Install the [Codex CLI](https://github.com/openai/codex) and app-server, then sign in with `codex login`.
- The **Codex** tab of the AI Coder options holds the [standard backend fields](../../BACKENDS.md#where-a-backends-settings-are).

## Model and effort

The model field is editable and offers the known choices; you can also type a model ID. Reasoning effort is sent with each request, so changing it needs no restart. The levels come from the Codex server and depend on the model. **Session Configuration** also holds the app-server's sandbox and approval options.

## Tools

The plugin's MCP server is passed to the app-server when the app-server starts, with a per-server tool timeout. Codex keeps its own shell and patch tools ([backend tools](../../CHANGE_REVIEW.md#backends-with-their-own-tools)).

## Gauges

The info bar shows the account rate limit, with used percentage and reset time, when the app-server reports it.
