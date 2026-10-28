package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import java.time.Duration;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettingsKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import org.openide.util.NbPreferences;

public final class OpenAiCompatiblePluginSettings {

    private static volatile Preferences testPreferences;

    private OpenAiCompatiblePluginSettings() {
    }

    private static Preferences prefs() {
        Preferences test = testPreferences;
        return test != null ? test : NbPreferences.forModule(PluginSettings.class);
    }

    public static ServerTypeEnum getServerType() {
        String value = prefs().get(OpenAiCompatiblePluginSettingsKeyEnum.SERVER_TYPE.key(),
                                   ServerTypeEnum.AUTO.name());
        try {
            return ServerTypeEnum.valueOf(value);
        }
        catch (IllegalArgumentException ex) {
            return ServerTypeEnum.AUTO;
        }
    }

    public static void setServerType(ServerTypeEnum value) {
        prefs().put(OpenAiCompatiblePluginSettingsKeyEnum.SERVER_TYPE.key(),
                    (value == null ? ServerTypeEnum.AUTO : value).name());
    }

    public static String getBaseUrl() {
        return prefs().get(OpenAiCompatiblePluginSettingsKeyEnum.BASE_URL.key(), "");
    }

    public static void setBaseUrl(String value) {
        prefs().put(OpenAiCompatiblePluginSettingsKeyEnum.BASE_URL.key(), value == null ? "" : value);
    }

    public static String getModel() {
        return prefs().get(OpenAiCompatiblePluginSettingsKeyEnum.MODEL.key(), "");
    }

    public static void setModel(String value) {
        prefs().put(OpenAiCompatiblePluginSettingsKeyEnum.MODEL.key(), value == null ? "" : value);
    }

    public static String getReasoningEffort() {
        String value = prefs().get(OpenAiCompatiblePluginSettingsKeyEnum.REASONING_EFFORT.key(), "");
        return value.isBlank() ? null : value.trim();
    }

    public static void setReasoningEffort(String value) {
        prefs().put(OpenAiCompatiblePluginSettingsKeyEnum.REASONING_EFFORT.key(), value == null ? "" : value.trim());
    }

    /**
     * The plugin-wide default for showing the model's reasoning in the chat; off unless the user turns it on.
     */
    public static boolean isShowReasoning() {
        return prefs().getBoolean(OpenAiCompatiblePluginSettingsKeyEnum.SHOW_REASONING.key(), false);
    }

    public static void setShowReasoning(boolean value) {
        prefs().putBoolean(OpenAiCompatiblePluginSettingsKeyEnum.SHOW_REASONING.key(), value);
    }

    public static boolean isDefaultKeySet() {
        return prefs().getBoolean(OpenAiCompatiblePluginSettingsKeyEnum.DEFAULT_KEY_SET.key(), false);
    }

    public static void setDefaultKeySet(boolean value) {
        prefs().putBoolean(OpenAiCompatiblePluginSettingsKeyEnum.DEFAULT_KEY_SET.key(), value);
    }

    /**
     * One-time import of Ollama (Local)'s stored URL/model into these defaults (spec §8.1).
     */
    public static synchronized boolean importFromOllamaOnce() {
        Preferences p = prefs();
        if (p.getBoolean(OpenAiCompatiblePluginSettingsKeyEnum.IMPORTED_FROM_OLLAMA.key(), false)) {
            return false;
        }
        p.putBoolean(OpenAiCompatiblePluginSettingsKeyEnum.IMPORTED_FROM_OLLAMA.key(), true);
        String urlKey = OllamaPluginSettingsKeyEnum.BASE_URL.key();
        String modelKey = OllamaPluginSettingsKeyEnum.MODEL.key();
        boolean urlStored = p.get(urlKey, null) != null;
        boolean modelStored = p.get(modelKey, null) != null;
        if (urlStored) {
            setBaseUrl(BaseUrls.normalise(p.get(urlKey, "")));
        }
        if (modelStored) {
            setModel(p.get(modelKey, ""));
        }
        return urlStored || modelStored;
    }

    /*
     * Plugin-wide values (spec §8.2, "Per session: no"). Getters return the stored value or the enum default. Byte and
     * count floors that the limits record does not hold are applied here (spec §8.1); the limits record clamps its own
     * fields in fromPluginSettings().
     */
    private static final long SIZE_CAP_FLOOR_BYTES = 1L << 20;
    private static final int MINIMUM_WINDOW_FLOOR = 2048;

    public static int getAssumedWindow() {
        return Math.max(getMinimumWindowForTools(), readInt(OpenAiCompatiblePluginSettingsKeyEnum.ASSUMED_WINDOW));
    }

    public static void setAssumedWindow(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.ASSUMED_WINDOW, value);
    }

    public static int getMinimumWindowForTools() {
        return Math.max(MINIMUM_WINDOW_FLOOR, readInt(OpenAiCompatiblePluginSettingsKeyEnum.MINIMUM_WINDOW_FOR_TOOLS));
    }

    public static void setMinimumWindowForTools(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.MINIMUM_WINDOW_FOR_TOOLS, value);
    }

    public static double getTrainingLengthFactor() {
        return readDouble(OpenAiCompatiblePluginSettingsKeyEnum.TRAINING_LENGTH_FACTOR);
    }

    public static void setTrainingLengthFactor(double value) {
        writeDouble(OpenAiCompatiblePluginSettingsKeyEnum.TRAINING_LENGTH_FACTOR, value);
    }

    public static int getSmallWindowWarning() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.SMALL_WINDOW_WARNING);
    }

    public static void setSmallWindowWarning(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.SMALL_WINDOW_WARNING, value);
    }

    public static int getWindowReadingsBeforeShrink() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.WINDOW_READINGS_BEFORE_SHRINK);
    }

    public static void setWindowReadingsBeforeShrink(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.WINDOW_READINGS_BEFORE_SHRINK, value);
    }

    public static int getSummaryToolResultChars() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.SUMMARY_TOOL_RESULT_CHARS);
    }

    public static void setSummaryToolResultChars(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.SUMMARY_TOOL_RESULT_CHARS, value);
    }

    public static int getSummaryRepairNudges() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.SUMMARY_REPAIR_NUDGES);
    }

    public static void setSummaryRepairNudges(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.SUMMARY_REPAIR_NUDGES, value);
    }

    public static int getOverflowRetries() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.OVERFLOW_RETRIES);
    }

    public static void setOverflowRetries(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.OVERFLOW_RETRIES, value);
    }

    public static double getCalibrationTolerance() {
        return readDouble(OpenAiCompatiblePluginSettingsKeyEnum.CALIBRATION_TOLERANCE);
    }

    public static void setCalibrationTolerance(double value) {
        writeDouble(OpenAiCompatiblePluginSettingsKeyEnum.CALIBRATION_TOLERANCE, value);
    }

    public static double getEstimatorCharsPerToken() {
        return readDouble(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_CHARS_PER_TOKEN);
    }

    public static void setEstimatorCharsPerToken(double value) {
        writeDouble(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_CHARS_PER_TOKEN, value);
    }

    public static int getEstimatorOverhead() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_OVERHEAD);
    }

    public static void setEstimatorOverhead(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_OVERHEAD, value);
    }

    public static double getEstimatorSmoothing() {
        return readDouble(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_SMOOTHING);
    }

    public static void setEstimatorSmoothing(double value) {
        writeDouble(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_SMOOTHING, value);
    }

    public static double getEstimatorMinRatio() {
        return readDouble(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_MIN_RATIO);
    }

    public static void setEstimatorMinRatio(double value) {
        writeDouble(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_MIN_RATIO, value);
    }

    public static double getEstimatorMaxRatio() {
        return readDouble(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_MAX_RATIO);
    }

    public static void setEstimatorMaxRatio(double value) {
        writeDouble(OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_MAX_RATIO, value);
    }

    public static int getEmptyReplyNudges() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.EMPTY_REPLY_NUDGES);
    }

    public static void setEmptyReplyNudges(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.EMPTY_REPLY_NUDGES, value);
    }

    public static int getToolDescriptionCapChars() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.TOOL_DESCRIPTION_CAP_CHARS);
    }

    public static void setToolDescriptionCapChars(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.TOOL_DESCRIPTION_CAP_CHARS, value);
    }

    public static int getParameterDescriptionCapChars() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.PARAMETER_DESCRIPTION_CAP_CHARS);
    }

    public static void setParameterDescriptionCapChars(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.PARAMETER_DESCRIPTION_CAP_CHARS, value);
    }

    public static int getSchemaDepth() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.SCHEMA_DEPTH);
    }

    public static void setSchemaDepth(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.SCHEMA_DEPTH, value);
    }

    public static long getTranscriptCapBytes() {
        return Math.max(SIZE_CAP_FLOOR_BYTES, readLong(OpenAiCompatiblePluginSettingsKeyEnum.TRANSCRIPT_CAP_BYTES));
    }

    public static void setTranscriptCapBytes(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.TRANSCRIPT_CAP_BYTES, value);
    }

    public static long getToolArgumentCapBytes() {
        return Math.max(SIZE_CAP_FLOOR_BYTES, readLong(OpenAiCompatiblePluginSettingsKeyEnum.TOOL_ARGUMENT_CAP_BYTES));
    }

    public static void setToolArgumentCapBytes(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.TOOL_ARGUMENT_CAP_BYTES, value);
    }

    public static long getTotalRequestCapMinutes() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.TOTAL_REQUEST_CAP_MINUTES);
    }

    public static void setTotalRequestCapMinutes(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.TOTAL_REQUEST_CAP_MINUTES, value);
    }

    public static long getConnectTimeoutSeconds() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.CONNECT_TIMEOUT_SECONDS);
    }

    public static void setConnectTimeoutSeconds(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.CONNECT_TIMEOUT_SECONDS, value);
    }

    public static long getAbandonedToolWaitSeconds() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.ABANDONED_TOOL_WAIT_SECONDS);
    }

    public static void setAbandonedToolWaitSeconds(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.ABANDONED_TOOL_WAIT_SECONDS, value);
    }

    public static long getProbeTimeoutSeconds() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.PROBE_TIMEOUT_SECONDS);
    }

    public static void setProbeTimeoutSeconds(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.PROBE_TIMEOUT_SECONDS, value);
    }

    public static long getRedetectIntervalSeconds() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.REDETECT_INTERVAL_SECONDS);
    }

    public static void setRedetectIntervalSeconds(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.REDETECT_INTERVAL_SECONDS, value);
    }

    public static long getProbeBodyCapBytes() {
        return Math.max(SIZE_CAP_FLOOR_BYTES, readLong(OpenAiCompatiblePluginSettingsKeyEnum.PROBE_BODY_CAP_BYTES));
    }

    public static void setProbeBodyCapBytes(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.PROBE_BODY_CAP_BYTES, value);
    }

    public static int getPerModelProbes() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.PER_MODEL_PROBES);
    }

    public static void setPerModelProbes(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.PER_MODEL_PROBES, value);
    }

    public static int getTransportRetries() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.TRANSPORT_RETRIES);
    }

    public static void setTransportRetries(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.TRANSPORT_RETRIES, value);
    }

    public static long getRetryBackoffSeconds() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.RETRY_BACKOFF_SECONDS);
    }

    public static void setRetryBackoffSeconds(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.RETRY_BACKOFF_SECONDS, value);
    }

    public static long getRetryAfterCapSeconds() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.RETRY_AFTER_CAP_SECONDS);
    }

    public static void setRetryAfterCapSeconds(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.RETRY_AFTER_CAP_SECONDS, value);
    }

    /**
     * Sent as {@code keep_alive} by the Ollama kind only; empty means not sent.
     */
    public static String getKeepAlive() {
        return prefs().get(OpenAiCompatiblePluginSettingsKeyEnum.KEEP_ALIVE.key(),
                           OpenAiCompatiblePluginSettingsKeyEnum.KEEP_ALIVE.defaultString());
    }

    public static void setKeepAlive(String value) {
        prefs().put(OpenAiCompatiblePluginSettingsKeyEnum.KEEP_ALIVE.key(), value == null ? "" : value);
    }

    public static long getDebugLogSizeCapBytes() {
        return Math.max(SIZE_CAP_FLOOR_BYTES, readLong(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_SIZE_CAP_BYTES));
    }

    public static void setDebugLogSizeCapBytes(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_SIZE_CAP_BYTES, value);
    }

    public static int getDebugLogRolledFiles() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_ROLLED_FILES);
    }

    public static void setDebugLogRolledFiles(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_ROLLED_FILES, value);
    }

    public static long getDebugLogQueueBytes() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_QUEUE_BYTES);
    }

    public static void setDebugLogQueueBytes(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_QUEUE_BYTES, value);
    }

    public static int getDebugLogQueueEntries() {
        return readInt(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_QUEUE_ENTRIES);
    }

    public static void setDebugLogQueueEntries(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_QUEUE_ENTRIES, value);
    }

    public static long getDebugLogMaxEntryBytes() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_MAX_ENTRY_BYTES);
    }

    public static void setDebugLogMaxEntryBytes(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_MAX_ENTRY_BYTES, value);
    }

    public static long getDebugLogDrainSeconds() {
        return readLong(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_DRAIN_SECONDS);
    }

    public static Duration getKeyringLoadTimeout() {
        long millis = readLong(OpenAiCompatiblePluginSettingsKeyEnum.KEYRING_LOAD_TIMEOUT_MILLIS);
        if (millis <= 0) {
            return Duration.ofSeconds(10);
        }
        return Duration.ofMillis(millis);
    }

    public static void setKeyringLoadTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            writeLong(OpenAiCompatiblePluginSettingsKeyEnum.KEYRING_LOAD_TIMEOUT_MILLIS, 10000L);
            return;
        }
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.KEYRING_LOAD_TIMEOUT_MILLIS, timeout.toMillis());
    }

    public static void setDebugLogDrainSeconds(long value) {
        writeLong(OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_DRAIN_SECONDS, value);
    }

    /**
     * Spec §8.1 floor for the summarise threshold, in tokens.
     */
    public static final int MIN_SUMMARISE_THRESHOLD = 4096;

    /**
     * The window at or above which Auto summarises instead of dropping (spec §6.3); never below the floor.
     */
    public static int getSummariseThreshold() {
        return Math.max(MIN_SUMMARISE_THRESHOLD, readInt(OpenAiCompatiblePluginSettingsKeyEnum.SUMMARISE_THRESHOLD));
    }

    public static void setSummariseThreshold(int value) {
        writeInt(OpenAiCompatiblePluginSettingsKeyEnum.SUMMARISE_THRESHOLD, value);
    }

    /**
     * The plugin-wide limits as one {@link OpenAiCompatibleLimits}. Unset values take the spec §8.2 defaults; the
     * record applies the §8.1 floors (connect, probe-vs-re-detect, size caps, retries, total cap). First-byte and idle
     * timeouts are per session, so they keep the record's defaults here.
     */
    public static OpenAiCompatibleLimits fromPluginSettings() {
        return OpenAiCompatibleLimits.defaults()
                .withConnectTimeout(Duration.ofSeconds(getConnectTimeoutSeconds()))
                .withTotalCap(Duration.ofMinutes(getTotalRequestCapMinutes()))
                .withAbandonedToolWait(Duration.ofSeconds(getAbandonedToolWaitSeconds()))
                .withProbeTimeout(Duration.ofSeconds(getProbeTimeoutSeconds()))
                .withRedetectInterval(Duration.ofSeconds(getRedetectIntervalSeconds()))
                .withRetryBackoff(Duration.ofSeconds(getRetryBackoffSeconds()))
                .withRetryAfterCap(Duration.ofSeconds(getRetryAfterCapSeconds()))
                .withProbeBodyCapBytes(getProbeBodyCapBytes())
                .withTranscriptCapBytes(getTranscriptCapBytes())
                .withToolArgumentCapBytes(getToolArgumentCapBytes())
                .withTransportRetries(getTransportRetries());
    }

    private static int readInt(OpenAiCompatiblePluginSettingsKeyEnum key) {
        return prefs().getInt(key.key(), key.defaultInt());
    }

    private static void writeInt(OpenAiCompatiblePluginSettingsKeyEnum key, int value) {
        prefs().putInt(key.key(), value);
    }

    private static long readLong(OpenAiCompatiblePluginSettingsKeyEnum key) {
        return prefs().getLong(key.key(), key.defaultLong());
    }

    private static void writeLong(OpenAiCompatiblePluginSettingsKeyEnum key, long value) {
        prefs().putLong(key.key(), value);
    }

    private static double readDouble(OpenAiCompatiblePluginSettingsKeyEnum key) {
        return prefs().getDouble(key.key(), key.defaultDouble());
    }

    private static void writeDouble(OpenAiCompatiblePluginSettingsKeyEnum key, double value) {
        prefs().putDouble(key.key(), value);
    }

    /**
     * Swaps the preferences node for tests (null restores the real one); public so the UI tests can isolate it too.
     */
    public static void useForTest(Preferences preferences) {
        testPreferences = preferences;
    }
}
