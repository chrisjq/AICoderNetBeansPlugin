package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

/**
 * JSON object key names in Anthropic HTTP API responses read by
 * {@link AnthropicApiClient} ({@code /v1/models} and {@code /api/oauth/usage}).
 * Kept separate from {@link ClaudeJsonKeyEnum}, which describes the Claude
 * CLI's own stream-json / settings / credentials vocabulary — these are the
 * api.anthropic.com wire contract and the values must never change.
 */
public enum AnthropicApiJsonKeyEnum {
    // /v1/models response
    DATA("data"),
    MODEL_ID("id"),
    // /api/oauth/usage response: per-window buckets, each carrying a utilization percentage
    FIVE_HOUR("five_hour"),
    SEVEN_DAY("seven_day"),
    UTILIZATION("utilization");

    private final String key;

    AnthropicApiJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
