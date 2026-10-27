[← Back to README](../../../README.md)

# GitHub Copilot backend

See also: [Backends](../../BACKENDS.md) · [Change review](../../CHANGE_REVIEW.md) · [Collaboration](../../COLLABORATION.md)

GitHub Copilot runs the `copilot` CLI and SDK for each session.

## Setup

- Install the [GitHub Copilot CLI](https://github.com/features/copilot/cli), have a Copilot-enabled GitHub account, and sign in in a terminal with `copilot login`.
- The **GitHub Copilot** tab of the AI Coder options holds the [standard backend fields](../../BACKENDS.md#where-a-backends-settings-are).

## Model and effort

Models come from the SDK's discovery, with fallbacks until it answers. The efforts offered are the ones the SDK reports for the selected model; a model that advertises none offers only the "not set" entry. Changing the effort or model of a running session rebuilds the session.

## Tools

The session is told, as the first line of its instructions, to use the plugin's tools instead of its built-in ones ([backend tools](../../CHANGE_REVIEW.md#backends-with-their-own-tools)).

## Gauges

The info bar shows the Copilot usage quota when the SDK reports it.
