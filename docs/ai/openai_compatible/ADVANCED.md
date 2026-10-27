[← Back to README](../../../README.md) · [OpenAI Compatible backend](README.md)

# OpenAI Compatible: advanced settings

You rarely need these; defaults suit most setups. This page lists every timeout and limit of the [OpenAI Compatible](README.md) backend. [Context window and compaction](CONTEXT.md) explains the context-related values.

## Per session

These are in the **Advanced** section of Create Session and Session Configuration; an empty value uses the plugin default. The other per-session defaults are fixed.

### Per session: Window and compaction

| Setting | Default |
|---|---|
| Summarise threshold | 16,384 tokens (minimum 4,096) |
| Reserve (% of window) / minimum / max share | 15% / 1,024 tokens / 50% |
| Reserve absolute | 0 tokens = use the percentage |
| Keep recent (% of window) / minimum | 25% / 2,048 tokens |
| Keep recent absolute | 0 tokens = use the percentage |
| Tool-result share (% of window − reserve) | 25% |
| Prune threshold / chars kept | 4,000 / 200 chars |

### Per session: Tool calls

| Setting | Default |
|---|---|
| Max tool rounds | 0 = unlimited (a hosted endpoint with no cap gets a one-time reminder to set one) |
| Max length continuations | 2 |
| Max malformed rounds | 2 (minimum 1) |
| Repetition window | 4 calls (minimum 2) |
| Unproductive rounds | 3 (minimum 1) |
| Native failures before Schema | 2 (minimum 1) |
| Temperature (0–2), Top P ((0,1]), Max output tokens | empty = not sent |
| Extra headers | none; rules are in [API key and extra headers](README.md#api-key-and-extra-headers) |

### Per session: Network and timeouts

| Setting | Default |
|---|---|
| First-byte timeout | 600 s |
| Idle timeout | 120 s; 0 = off |
| Total request cap | 60 min |

## Plugin-wide

These are set only in the OpenAI Compatible tab (Advanced) of the AI Coder options; a few are also the default for a per-session value, as noted.

### Plugin-wide: Window and compaction

| Setting | Default |
|---|---|
| Assumed window | 8,192 |
| Summarise threshold | 16,384 — default for the per-session value |
| Minimum window for tools | 6,144 |
| Training-length factor | 0.8 |
| Small-window warning below | 16,384 |
| Window readings before shrinking | 2 |
| Summary tool-result length | 2,000 chars |
| Summary repair nudges | 1 |
| Overflow retries | 1 |
| Calibration tolerance | 0.02 |
| Estimator chars/token, overhead, smoothing, min ratio, max ratio | 3.5, 4, 0.3, 0.5, 2.0 |
| Empty-reply nudges | 1 |

### Plugin-wide: Tool calls

| Setting | Default |
|---|---|
| Tool description cap / Parameter description cap | 1,024 / 256 chars |
| Schema nesting depth | 2 |
| Transcript cap / Tool-call argument cap | 64 MB / 1 MB |
| Abandoned-tool wait | 300 s |

### Plugin-wide: Network and timeouts

| Setting | Default |
|---|---|
| Total request cap | 60 min — default for the per-session value |
| Connect timeout | 10 s |
| Probe timeout / Re-detect interval / Probe body cap | 3 s / 60 s / 1 MB |
| Per-model probes | 8 |
| Transport retries / Retry backoff / Retry-After cap | 1 / 2 s / 30 s |
| keep_alive (Ollama kind only) | `5m`; empty = not sent |

### Plugin-wide: Debug log

| Setting | Default |
|---|---|
| Debug log size cap / rolled files | 10 MB / 1 |
| Debug log queue / queue entries / max entry | 8 MB / 10,000 / 256 KB |
| Debug log drain | 2 s |
