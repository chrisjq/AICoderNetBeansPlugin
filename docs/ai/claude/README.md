[← Back to README](../../../README.md)

# Claude backend

See also: [Backends](../../BACKENDS.md) · [Change review](../../CHANGE_REVIEW.md) · [Collaboration](../../COLLABORATION.md)

Claude runs Anthropic's `claude` CLI in the background for each session.

## Setup

- Install the [Claude Code](https://claude.com/claude-code) CLI and sign in in a terminal: run `claude` and follow the prompts, or run `claude auth login`.
- The **Claude** tab of the AI Coder options holds the [standard backend fields](../../BACKENDS.md#where-a-backends-settings-are).

## Model and effort

The model list is discovered and cached when the CLI can supply it; you can also type a model ID. Effort is `low`, `medium`, `high`, `xhigh` or `max`, passed as `--effort` at launch. Because it is fixed at launch, changing the effort or model of a running session restarts it. The CLI clamps a level the model does not support, so no level is rejected.

## Tools

The plugin's MCP tools are registered with the CLI at launch. The session's instructions tell Claude to prefer them over its own file and shell tools ([backend tools](../../CHANGE_REVIEW.md#backends-with-their-own-tools)).

## Gauges

The info bar shows Claude's rolling usage limits when the CLI reports them.

