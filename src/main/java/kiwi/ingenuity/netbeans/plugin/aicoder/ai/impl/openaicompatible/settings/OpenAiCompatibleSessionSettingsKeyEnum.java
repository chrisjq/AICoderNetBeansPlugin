package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

public enum OpenAiCompatibleSessionSettingsKeyEnum {
    SERVER_TYPE("serverType"),
    BASE_URL("baseUrl"),
    CONTEXT_WINDOW_OVERRIDE("contextWindowOverride"),
    TOOL_CALLING_MODE("toolCallingMode"),
    SAVE_CONTEXT_ON_CLOSE("saveContextOnClose"),
    API_KEY_SET("apiKeySet"),
    EXTRA_HEADER_NAMES("extraHeaderNames"),
    EXTRA_HEADER_PLAIN_VALUES("extraHeaderPlainValues"),
    FIRST_BYTE_TIMEOUT_SECONDS("firstByteTimeoutSeconds"),
    IDLE_TIMEOUT_SECONDS("idleTimeoutSeconds"),
    TOTAL_CAP_MINUTES("totalCapMinutes"),
    MAX_TOOL_ROUNDS("maxToolRounds"),
    MAX_LENGTH_CONTINUATIONS("maxLengthContinuations"),
    MAX_MALFORMED_ROUNDS("maxMalformedRounds"),
    REPETITION_WINDOW("repetitionWindow"),
    UNPRODUCTIVE_ROUNDS("unproductiveRounds"),
    NATIVE_FAILURES_BEFORE_SCHEMA("nativeFailuresBeforeSchema"),
    COMPACTION_STRATEGY("compactionStrategy"),
    AUTO_COMPACT("autoCompact"),
    PRUNE("prune"),
    TEMPERATURE("temperature"),
    TOP_P("topP"),
    MAX_OUTPUT_TOKENS("maxOutputTokens"),
    REASONING_EFFORT("reasoningEffort"),
    SUMMARY_MODEL("summaryModel"),
    SUMMARISE_THRESHOLD("summariseThreshold"),
    SHOW_REASONING("showReasoning"),
    RESERVE_PERCENT("reservePercent"),
    RESERVE_MINIMUM("reserveMinimum"),
    RESERVE_MAX_SHARE_PERCENT("reserveMaxSharePercent"),
    RESERVE_ABSOLUTE("reserveAbsolute"),
    KEEP_RECENT_PERCENT("keepRecentPercent"),
    KEEP_RECENT_MINIMUM("keepRecentMinimum"),
    KEEP_RECENT_ABSOLUTE("keepRecentAbsolute"),
    TOOL_RESULT_SHARE_PERCENT("toolResultSharePercent"),
    PRUNE_THRESHOLD_CHARS("pruneThresholdChars"),
    PRUNE_CHARS_KEPT("pruneCharsKept");

    private final String key;

    OpenAiCompatibleSessionSettingsKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
