[← Back to README](../../../README.md)

# Grok backend

See also: [Backends](../../BACKENDS.md) · [Change review](../../CHANGE_REVIEW.md) · [Collaboration](../../COLLABORATION.md)

Grok runs xAI's `grok` CLI in the background for each session.

## Setup

- Install the [Grok CLI](https://x.ai/cli) and sign in in a terminal with `grok login`.
- The **Grok** tab of the AI Coder options holds the [standard backend fields](../../BACKENDS.md#where-a-backends-settings-are).

## Model and effort

Models are discovered from the agent, with fallbacks until then. Effort is sent to the agent as a session setting. The levels are per model, taken from a built-in table until the running agent reports them; an unknown model offers none. A change applies immediately to the running session.

## Tools

The plugin's MCP server is passed to Grok when the session starts; nothing is written to Grok's own config ([backend tools](../../CHANGE_REVIEW.md#backends-with-their-own-tools)).
