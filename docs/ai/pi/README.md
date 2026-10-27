[← Back to README](../../../README.md)

# Pi backend

See also: [Backends](../../BACKENDS.md) · [Change review](../../CHANGE_REVIEW.md) · [Collaboration](../../COLLABORATION.md)

Pi runs the `pi` CLI with your chosen provider for each session.

## Setup

- Install the [pi](https://pi.dev) CLI and log in to a provider with Pi's own `/login` command.
- The **Pi** tab of the AI Coder options holds the [standard backend fields](../../BACKENDS.md#where-a-backends-settings-are), with a **Refresh** button for the model list and a version status with a **Verify…** button.

## Version verification

An untested pi version shows a warning button in the info bar and in Options until you verify it. **Verify…** asks whether the version works. **Yes** marks it verified, **Unsure** leaves it, and **No** marks it not working for this IDE session and shows a bug-report link.

## Model and effort

Models and thinking levels are discovered live from the running session, or from `pi --list-models` before one exists. `off` is a real level that tells the provider not to reason; pi offers it only on models that can honour it. It is not the picker's "don't set it" entry.

## Tools

The plugin's MCP tools reach pi through a per-session extension file that the plugin generates and that exists only while the session runs. A `pi` process started outside NetBeans does not see them ([backend tools](../../CHANGE_REVIEW.md#backends-with-their-own-tools)).

## Gauges

The info bar shows Pi context usage when the running session reports it.
