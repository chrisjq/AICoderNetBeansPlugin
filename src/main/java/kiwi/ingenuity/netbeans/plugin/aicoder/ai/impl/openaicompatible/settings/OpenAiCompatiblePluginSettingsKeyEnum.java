package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

public enum OpenAiCompatiblePluginSettingsKeyEnum {
    SERVER_TYPE("ai.openai_compatible.serverType"),
    BASE_URL("ai.openai_compatible.baseUrl"),
    MODEL("ai.openai_compatible.model"),
    REASONING_EFFORT("ai.openai_compatible.reasoningEffort"),
    DEFAULT_KEY_SET("ai.openai_compatible.defaultKeySet"),
    IMPORTED_FROM_OLLAMA("ai.openai_compatible.importedFromOllama"),
    SHOW_REASONING("ai.openai_compatible.showReasoning"),

    /*
     * Plugin-wide values: every spec §8.2 row marked "Per session: no". Each has its default here; the getters and
     * setters live on OpenAiCompatiblePluginSettings.
     */
    ASSUMED_WINDOW("ai.openai_compatible.assumedWindow", 8192),
    MINIMUM_WINDOW_FOR_TOOLS("ai.openai_compatible.minimumWindowForTools", 6144),
    TRAINING_LENGTH_FACTOR("ai.openai_compatible.trainingLengthFactor", 0.8),
    SMALL_WINDOW_WARNING("ai.openai_compatible.smallWindowWarning", 16384),
    WINDOW_READINGS_BEFORE_SHRINK("ai.openai_compatible.windowReadingsBeforeShrink", 2),
    SUMMARY_TOOL_RESULT_CHARS("ai.openai_compatible.summaryToolResultChars", 2000),
    SUMMARY_REPAIR_NUDGES("ai.openai_compatible.summaryRepairNudges", 1),
    OVERFLOW_RETRIES("ai.openai_compatible.overflowRetries", 1),
    CALIBRATION_TOLERANCE("ai.openai_compatible.calibrationTolerance", 0.02),
    ESTIMATOR_CHARS_PER_TOKEN("ai.openai_compatible.estimatorCharsPerToken", 3.5),
    ESTIMATOR_OVERHEAD("ai.openai_compatible.estimatorOverhead", 4),
    ESTIMATOR_SMOOTHING("ai.openai_compatible.estimatorSmoothing", 0.3),
    ESTIMATOR_MIN_RATIO("ai.openai_compatible.estimatorMinRatio", 0.5),
    ESTIMATOR_MAX_RATIO("ai.openai_compatible.estimatorMaxRatio", 2.0),
    EMPTY_REPLY_NUDGES("ai.openai_compatible.emptyReplyNudges", 1),
    TOOL_DESCRIPTION_CAP_CHARS("ai.openai_compatible.toolDescriptionCapChars", 1024),
    PARAMETER_DESCRIPTION_CAP_CHARS("ai.openai_compatible.parameterDescriptionCapChars", 256),
    SCHEMA_DEPTH("ai.openai_compatible.schemaDepth", 2),
    TRANSCRIPT_CAP_BYTES("ai.openai_compatible.transcriptCapBytes", 64L << 20),
    TOOL_ARGUMENT_CAP_BYTES("ai.openai_compatible.toolArgumentCapBytes", 1L << 20),
    TOTAL_REQUEST_CAP_MINUTES("ai.openai_compatible.totalRequestCapMinutes", 60L),
    CONNECT_TIMEOUT_SECONDS("ai.openai_compatible.connectTimeoutSeconds", 10L),
    ABANDONED_TOOL_WAIT_SECONDS("ai.openai_compatible.abandonedToolWaitSeconds", 300L),
    PROBE_TIMEOUT_SECONDS("ai.openai_compatible.probeTimeoutSeconds", 3L),
    REDETECT_INTERVAL_SECONDS("ai.openai_compatible.redetectIntervalSeconds", 60L),
    PROBE_BODY_CAP_BYTES("ai.openai_compatible.probeBodyCapBytes", 1L << 20),
    PER_MODEL_PROBES("ai.openai_compatible.perModelProbes", 8),
    TRANSPORT_RETRIES("ai.openai_compatible.transportRetries", 1),
    RETRY_BACKOFF_SECONDS("ai.openai_compatible.retryBackoffSeconds", 2L),
    RETRY_AFTER_CAP_SECONDS("ai.openai_compatible.retryAfterCapSeconds", 30L),
    KEEP_ALIVE("ai.openai_compatible.keepAlive", "5m"),
    DEBUG_LOG_SIZE_CAP_BYTES("ai.openai_compatible.debugLogSizeCapBytes", 10L << 20),
    DEBUG_LOG_ROLLED_FILES("ai.openai_compatible.debugLogRolledFiles", 1),
    DEBUG_LOG_QUEUE_BYTES("ai.openai_compatible.debugLogQueueBytes", 8L << 20),
    DEBUG_LOG_QUEUE_ENTRIES("ai.openai_compatible.debugLogQueueEntries", 10000),
    DEBUG_LOG_MAX_ENTRY_BYTES("ai.openai_compatible.debugLogMaxEntryBytes", 256L << 10),
    DEBUG_LOG_DRAIN_SECONDS("ai.openai_compatible.debugLogDrainSeconds", 2L),
    KEYRING_LOAD_TIMEOUT_MILLIS("ai.openai_compatible.keyringLoadTimeoutMillis", 10000L),
    SUMMARISE_THRESHOLD("ai.openai_compatible.summariseThreshold", 16384);

    private final String key;
    private final Object defaultValue;

    OpenAiCompatiblePluginSettingsKeyEnum(String key) {
        this(key, null);
    }

    OpenAiCompatiblePluginSettingsKeyEnum(String key, Object defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String key() {
        return key;
    }

    public int defaultInt() {
        return ((Number) defaultValue).intValue();
    }

    public long defaultLong() {
        return ((Number) defaultValue).longValue();
    }

    public double defaultDouble() {
        return ((Number) defaultValue).doubleValue();
    }

    public String defaultString() {
        return (String) defaultValue;
    }
}
