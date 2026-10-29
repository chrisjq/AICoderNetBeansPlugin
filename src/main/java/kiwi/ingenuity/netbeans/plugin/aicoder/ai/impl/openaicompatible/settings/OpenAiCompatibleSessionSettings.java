package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ContextBudgetSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.Pruner;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;

/**
 * Persisted per-session choices for an OpenAI-compatible endpoint.
 */
public class OpenAiCompatibleSessionSettings extends AiModelSessionSettings {

    private volatile ServerTypeEnum serverType;
    private volatile String baseUrl;
    private volatile Integer contextWindowOverride;
    private volatile ToolCallingModeEnum toolCallingMode;
    private volatile Boolean saveContextOnClose;
    private volatile Boolean apiKeySet;
    private volatile List<String> extraHeaderNames;
    private volatile Map<String, String> extraHeaderPlainValues;
    private volatile Integer firstByteTimeoutSeconds;
    private volatile Integer idleTimeoutSeconds;
    private volatile Integer totalCapMinutes;
    private volatile Integer maxToolRounds;
    private volatile Integer maxLengthContinuations;
    private volatile Integer maxMalformedRounds;
    private volatile Integer repetitionWindow;
    private volatile Integer unproductiveRounds;
    private volatile Integer nativeFailuresBeforeSchema;
    private volatile CompactionStrategyEnum compactionStrategy;
    private volatile Boolean autoCompact;
    private volatile Boolean prune;
    private volatile Double temperature;
    private volatile Double topP;
    private volatile Integer maxOutputTokens;
    private volatile String reasoningEffort;
    private volatile String summaryModel;
    private volatile Integer summariseThreshold;
    private volatile Boolean showReasoning;
    private volatile Integer reservePercent;
    private volatile Integer reserveMinimum;
    private volatile Integer reserveMaxSharePercent;
    private volatile Integer reserveAbsolute;
    private volatile Integer keepRecentPercent;
    private volatile Integer keepRecentMinimum;
    private volatile Integer keepRecentAbsolute;
    private volatile Integer toolResultSharePercent;
    private volatile Integer pruneThresholdChars;
    private volatile Integer pruneCharsKept;

    /**
     * Defaults of the loop counts (spec §8.2); the timeouts' defaults live in {@link OpenAiCompatibleLimits}.
     */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 0;
    public static final int DEFAULT_MAX_LENGTH_CONTINUATIONS = 2;
    public static final int DEFAULT_MAX_MALFORMED_ROUNDS = 2;
    public static final int DEFAULT_REPETITION_WINDOW = 4;
    public static final int DEFAULT_UNPRODUCTIVE_ROUNDS = 3;
    public static final int DEFAULT_NATIVE_FAILURES_BEFORE_SCHEMA = 2;

    /**
     * Fixed validation floors (spec §8.1) applied by the {@code effective…} getters.
     */
    public static final int MIN_REPETITION_WINDOW = 2;
    public static final int MIN_MALFORMED_ROUNDS = 1;
    public static final int MIN_UNPRODUCTIVE_ROUNDS = 1;
    public static final int MIN_NATIVE_FAILURES = 1;

    @Override
    public boolean supportsSessionMemory() {
        return true;
    }

    public Integer firstByteTimeoutSeconds() {
        return firstByteTimeoutSeconds;
    }

    public void setFirstByteTimeoutSeconds(Integer value) {
        firstByteTimeoutSeconds = value;
    }

    public Integer idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    /**
     * @param value seconds; 0 turns the idle timeout off
     */
    public void setIdleTimeoutSeconds(Integer value) {
        idleTimeoutSeconds = value;
    }

    public Integer totalCapMinutes() {
        return totalCapMinutes;
    }

    public void setTotalCapMinutes(Integer value) {
        totalCapMinutes = value;
    }

    public Integer maxToolRounds() {
        return maxToolRounds;
    }

    public void setMaxToolRounds(Integer value) {
        maxToolRounds = value;
    }

    public Integer maxLengthContinuations() {
        return maxLengthContinuations;
    }

    public void setMaxLengthContinuations(Integer value) {
        maxLengthContinuations = value;
    }

    public Integer maxMalformedRounds() {
        return maxMalformedRounds;
    }

    public void setMaxMalformedRounds(Integer value) {
        maxMalformedRounds = value;
    }

    public Integer repetitionWindow() {
        return repetitionWindow;
    }

    public void setRepetitionWindow(Integer value) {
        repetitionWindow = value;
    }

    public Integer unproductiveRounds() {
        return unproductiveRounds;
    }

    public void setUnproductiveRounds(Integer value) {
        unproductiveRounds = value;
    }

    public Integer nativeFailuresBeforeSchema() {
        return nativeFailuresBeforeSchema;
    }

    public void setNativeFailuresBeforeSchema(Integer value) {
        nativeFailuresBeforeSchema = value;
    }

    /**
     * @return the override clamped to its floor (0 = unlimited), else {@code defaultValue}
     */
    public int effectiveMaxToolRounds(int defaultValue) {
        return Math.max(0, maxToolRounds != null ? maxToolRounds : defaultValue);
    }

    public int effectiveMaxLengthContinuations(int defaultValue) {
        return Math.max(0, maxLengthContinuations != null ? maxLengthContinuations : defaultValue);
    }

    public int effectiveMaxMalformedRounds(int defaultValue) {
        return Math.max(MIN_MALFORMED_ROUNDS, maxMalformedRounds != null ? maxMalformedRounds : defaultValue);
    }

    public int effectiveRepetitionWindow(int defaultValue) {
        return Math.max(MIN_REPETITION_WINDOW, repetitionWindow != null ? repetitionWindow : defaultValue);
    }

    public int effectiveUnproductiveRounds(int defaultValue) {
        return Math.max(MIN_UNPRODUCTIVE_ROUNDS, unproductiveRounds != null ? unproductiveRounds : defaultValue);
    }

    public int effectiveNativeFailuresBeforeSchema(int defaultValue) {
        return Math.max(MIN_NATIVE_FAILURES,
                        nativeFailuresBeforeSchema != null ? nativeFailuresBeforeSchema : defaultValue);
    }

    /**
     * Returns {@code base} with each non-null timeout override applied. The limits record applies its own safety floors
     * (first-byte above zero, total above the longer of first-byte and idle), so an out-of-range override is clamped
     * rather than rejected. A null override leaves the base value unchanged.
     */
    public OpenAiCompatibleLimits applyTo(OpenAiCompatibleLimits base) {
        OpenAiCompatibleLimits result = base;
        Integer firstByte = firstByteTimeoutSeconds;
        if (firstByte != null) {
            result = result.withFirstByteTimeout(Duration.ofSeconds(Math.max(0, firstByte)));
        }
        Integer idle = idleTimeoutSeconds;
        if (idle != null) {
            result = result.withIdleTimeout(Duration.ofSeconds(Math.max(0, idle)));
        }
        Integer total = totalCapMinutes;
        if (total != null) {
            result = result.withTotalCap(Duration.ofMinutes(Math.max(0, total)));
        }
        return result;
    }

    public CompactionStrategyEnum compactionStrategy() {
        return compactionStrategy;
    }

    public void setCompactionStrategy(CompactionStrategyEnum value) {
        compactionStrategy = value;
    }

    /**
     * @return the chosen strategy, {@link CompactionStrategyEnum#AUTO} when unset
     */
    public CompactionStrategyEnum effectiveCompactionStrategy() {
        return compactionStrategy != null ? compactionStrategy : CompactionStrategyEnum.AUTO;
    }

    public Boolean autoCompact() {
        return autoCompact;
    }

    public void setAutoCompact(Boolean value) {
        autoCompact = value;
    }

    /**
     * @return whether the history is compacted automatically when it no longer fits; on when unset
     */
    public boolean effectiveAutoCompact() {
        return autoCompact == null || autoCompact;
    }

    public Boolean prune() {
        return prune;
    }

    public void setPrune(Boolean value) {
        prune = value;
    }

    /**
     * @return whether old tool output is shortened in the model's context; on when unset
     */
    public boolean effectivePrune() {
        return prune == null || prune;
    }

    public Double temperature() {
        return temperature;
    }

    public void setTemperature(Double value) {
        temperature = value;
    }

    /**
     * @return the configured temperature, or null to use the server default
     */
    public Double effectiveTemperature() {
        return temperature;
    }

    public Double topP() {
        return topP;
    }

    public void setTopP(Double value) {
        topP = value;
    }

    /**
     * @return the configured nucleus-sampling probability, or null for the server default
     */
    public Double effectiveTopP() {
        return topP;
    }

    /**
     * @return the model that writes the summaries, or null to use the chat model
     */
    public String summaryModel() {
        return summaryModel;
    }

    public void setSummaryModel(String value) {
        summaryModel = value;
    }

    /**
     * @return the summary model, or {@code chatModel} when none is set or the setting is blank
     */
    public String effectiveSummaryModel(String chatModel) {
        String value = summaryModel;
        return value == null || value.isBlank() ? chatModel : value.trim();
    }

    public Integer maxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer value) {
        maxOutputTokens = value;
    }

    /**
     * @return the configured output-token cap, or null for the server default
     */
    public Integer effectiveMaxOutputTokens() {
        return maxOutputTokens;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String value) {
        reasoningEffort = value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * @return the configured reasoning effort, or null to omit the field
     */
    public String effectiveReasoningEffort() {
        return reasoningEffort != null ? reasoningEffort : OpenAiCompatiblePluginSettings.getReasoningEffort();
    }

    public Integer summariseThreshold() {
        return summariseThreshold;
    }

    public void setSummariseThreshold(Integer value) {
        summariseThreshold = value;
    }

    /**
     * @return this session's summarise threshold clamped to the spec §8.1 floor, else the plugin-wide value
     */
    public int effectiveSummariseThreshold() {
        return summariseThreshold != null
               ? Math.max(OpenAiCompatiblePluginSettings.MIN_SUMMARISE_THRESHOLD, summariseThreshold)
               : OpenAiCompatiblePluginSettings.getSummariseThreshold();
    }

    public Boolean showReasoning() {
        return showReasoning;
    }

    public void setShowReasoning(Boolean value) {
        showReasoning = value;
    }

    /**
     * @return whether the model's reasoning is shown in the chat; the plugin-wide default (off) when unset
     */
    public boolean effectiveShowReasoning() {
        return showReasoning != null ? showReasoning : OpenAiCompatiblePluginSettings.isShowReasoning();
    }

    /**
     * Context-budget values (spec §8.2, per session). Percentages are whole percents; each {@code effective…} getter
     * clamps to its validation range and falls back to the defaults of {@link ContextBudgetSettings} or {@link Pruner}.
     */
    public Integer reservePercent() {
        return reservePercent;
    }

    public void setReservePercent(Integer value) {
        reservePercent = value;
    }

    public int effectiveReservePercent() {
        return clampInt(reservePercent, percentOf(ContextBudgetSettings.defaults().reservePercent()), 0, 100);
    }

    public Integer reserveMinimum() {
        return reserveMinimum;
    }

    public void setReserveMinimum(Integer value) {
        reserveMinimum = value;
    }

    public int effectiveReserveMinimum() {
        return clampInt(reserveMinimum, ContextBudgetSettings.defaults().reserveMinimum(), 0, Integer.MAX_VALUE);
    }

    public Integer reserveMaxSharePercent() {
        return reserveMaxSharePercent;
    }

    public void setReserveMaxSharePercent(Integer value) {
        reserveMaxSharePercent = value;
    }

    public int effectiveReserveMaxSharePercent() {
        return clampInt(reserveMaxSharePercent, percentOf(ContextBudgetSettings.defaults().reserveMaxShare()), 1, 100);
    }

    public Integer reserveAbsolute() {
        return reserveAbsolute;
    }

    public void setReserveAbsolute(Integer value) {
        reserveAbsolute = value;
    }

    /**
     * @return the absolute reserve in tokens, or 0 when the percentage applies
     */
    public int effectiveReserveAbsolute() {
        return clampInt(reserveAbsolute, 0, 0, Integer.MAX_VALUE);
    }

    public Integer keepRecentPercent() {
        return keepRecentPercent;
    }

    public void setKeepRecentPercent(Integer value) {
        keepRecentPercent = value;
    }

    public int effectiveKeepRecentPercent() {
        return clampInt(keepRecentPercent, percentOf(ContextBudgetSettings.defaults().keepRecentPercent()), 0, 100);
    }

    public Integer keepRecentMinimum() {
        return keepRecentMinimum;
    }

    public void setKeepRecentMinimum(Integer value) {
        keepRecentMinimum = value;
    }

    public int effectiveKeepRecentMinimum() {
        return clampInt(keepRecentMinimum, ContextBudgetSettings.defaults().keepRecentMinimum(), 0, Integer.MAX_VALUE);
    }

    public Integer keepRecentAbsolute() {
        return keepRecentAbsolute;
    }

    public void setKeepRecentAbsolute(Integer value) {
        keepRecentAbsolute = value;
    }

    /**
     * @return the absolute keep-recent size in tokens, or 0 when the percentage applies
     */
    public int effectiveKeepRecentAbsolute() {
        return clampInt(keepRecentAbsolute, 0, 0, Integer.MAX_VALUE);
    }

    public Integer toolResultSharePercent() {
        return toolResultSharePercent;
    }

    public void setToolResultSharePercent(Integer value) {
        toolResultSharePercent = value;
    }

    public int effectiveToolResultSharePercent() {
        return clampInt(toolResultSharePercent, percentOf(ContextBudgetSettings.defaults().toolResultShare()), 1, 100);
    }

    public Integer pruneThresholdChars() {
        return pruneThresholdChars;
    }

    public void setPruneThresholdChars(Integer value) {
        pruneThresholdChars = value;
    }

    public int effectivePruneThresholdChars() {
        return clampInt(pruneThresholdChars, Pruner.DEFAULT_THRESHOLD_CHARS, 0, Integer.MAX_VALUE);
    }

    public Integer pruneCharsKept() {
        return pruneCharsKept;
    }

    public void setPruneCharsKept(Integer value) {
        pruneCharsKept = value;
    }

    /**
     * @return the characters kept from a pruned result, always below the prune threshold
     */
    public int effectivePruneCharsKept() {
        int threshold = effectivePruneThresholdChars();
        return clampInt(pruneCharsKept, Pruner.DEFAULT_CHARS_KEPT, 0, Math.max(0, threshold - 1));
    }

    private static int clampInt(Integer value, int fallback, int minimum, int maximum) {
        int chosen = value != null ? value : fallback;
        return Math.min(maximum, Math.max(minimum, chosen));
    }

    private static int percentOf(double fraction) {
        return (int) Math.round(fraction * 100);
    }

    public ServerTypeEnum serverType() {
        return serverType;
    }

    public void setServerType(ServerTypeEnum value) {
        serverType = value;
    }

    public ServerTypeEnum effectiveServerType() {
        return serverType != null ? serverType : ServerTypeEnum.AUTO;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String value) {
        baseUrl = value;
    }

    public String effectiveBaseUrl() {
        return baseUrl == null ? "" : baseUrl;
    }

    public Integer contextWindowOverride() {
        return contextWindowOverride;
    }

    public void setContextWindowOverride(Integer value) {
        contextWindowOverride = value;
    }

    public int effectiveContextWindowOverride() {
        return contextWindowOverride != null ? contextWindowOverride : 0;
    }

    public ToolCallingModeEnum toolCallingMode() {
        return toolCallingMode;
    }

    public void setToolCallingMode(ToolCallingModeEnum value) {
        toolCallingMode = value;
    }

    public ToolCallingModeEnum effectiveToolCallingMode() {
        return toolCallingMode != null ? toolCallingMode : ToolCallingModeEnum.AUTO;
    }

    public Boolean saveContextOnClose() {
        return saveContextOnClose;
    }

    public void setSaveContextOnClose(Boolean value) {
        saveContextOnClose = value;
    }

    public boolean effectiveSaveContextOnClose() {
        return saveContextOnClose == null || saveContextOnClose;
    }

    public Boolean apiKeySet() {
        return apiKeySet;
    }

    public void setApiKeySet(Boolean value) {
        apiKeySet = value;
    }

    public boolean effectiveApiKeySet() {
        return apiKeySet != null && apiKeySet;
    }

    public List<String> extraHeaderNames() {
        return extraHeaderNames == null ? List.of() : List.copyOf(extraHeaderNames);
    }

    public void setExtraHeaderNames(List<String> value) {
        extraHeaderNames = value == null ? null : new ArrayList<>(value);
    }

    public Map<String, String> extraHeaderPlainValues() {
        return extraHeaderPlainValues == null ? Map.of() : Map.copyOf(extraHeaderPlainValues);
    }

    public void setExtraHeaderPlainValues(Map<String, String> value) {
        extraHeaderPlainValues = value == null ? null : new LinkedHashMap<>(value);
    }

    public String effectiveModel() {
        return model() != null ? model() : OpenAiCompatiblePluginSettings.getModel();
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (serverType != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.SERVER_TYPE.key(), serverType.name());
        }
        if (baseUrl != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.BASE_URL.key(), baseUrl);
        }
        if (contextWindowOverride != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.CONTEXT_WINDOW_OVERRIDE.key(),
                               contextWindowOverride);
        }
        if (toolCallingMode != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.TOOL_CALLING_MODE.key(), toolCallingMode.name());
        }
        if (saveContextOnClose != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.SAVE_CONTEXT_ON_CLOSE.key(), saveContextOnClose);
        }
        if (apiKeySet != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.API_KEY_SET.key(), apiKeySet);
        }
        if (extraHeaderNames != null) {
            JsonArray values = new JsonArray();
            for (String name : extraHeaderNames) {
                values.add(name);
            }
            cfgObj.add(OpenAiCompatibleSessionSettingsKeyEnum.EXTRA_HEADER_NAMES.key(), values);
        }
        if (extraHeaderPlainValues != null) {
            JsonObject values = new JsonObject();
            for (Map.Entry<String, String> entry : extraHeaderPlainValues.entrySet()) {
                values.addProperty(entry.getKey(), entry.getValue());
            }
            cfgObj.add(OpenAiCompatibleSessionSettingsKeyEnum.EXTRA_HEADER_PLAIN_VALUES.key(), values);
        }
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.FIRST_BYTE_TIMEOUT_SECONDS, firstByteTimeoutSeconds);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.IDLE_TIMEOUT_SECONDS, idleTimeoutSeconds);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.TOTAL_CAP_MINUTES, totalCapMinutes);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.MAX_TOOL_ROUNDS, maxToolRounds);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.MAX_LENGTH_CONTINUATIONS, maxLengthContinuations);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.MAX_MALFORMED_ROUNDS, maxMalformedRounds);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.REPETITION_WINDOW, repetitionWindow);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.UNPRODUCTIVE_ROUNDS, unproductiveRounds);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.NATIVE_FAILURES_BEFORE_SCHEMA,
                 nativeFailuresBeforeSchema);
        if (compactionStrategy != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.COMPACTION_STRATEGY.key(),
                               compactionStrategy.name());
        }
        if (autoCompact != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.AUTO_COMPACT.key(), autoCompact);
        }
        if (prune != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.PRUNE.key(), prune);
        }
        if (temperature != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.TEMPERATURE.key(), temperature);
        }
        if (topP != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.TOP_P.key(), topP);
        }
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.MAX_OUTPUT_TOKENS, maxOutputTokens);
        if (reasoningEffort != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.REASONING_EFFORT.key(), reasoningEffort);
        }
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.SUMMARISE_THRESHOLD, summariseThreshold);
        if (summaryModel != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.SUMMARY_MODEL.key(), summaryModel);
        }
        if (showReasoning != null) {
            cfgObj.addProperty(OpenAiCompatibleSessionSettingsKeyEnum.SHOW_REASONING.key(), showReasoning);
        }
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.RESERVE_PERCENT, reservePercent);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.RESERVE_MINIMUM, reserveMinimum);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.RESERVE_MAX_SHARE_PERCENT, reserveMaxSharePercent);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.RESERVE_ABSOLUTE, reserveAbsolute);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.KEEP_RECENT_PERCENT, keepRecentPercent);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.KEEP_RECENT_MINIMUM, keepRecentMinimum);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.KEEP_RECENT_ABSOLUTE, keepRecentAbsolute);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.TOOL_RESULT_SHARE_PERCENT, toolResultSharePercent);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.PRUNE_THRESHOLD_CHARS, pruneThresholdChars);
        addIfSet(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.PRUNE_CHARS_KEPT, pruneCharsKept);
    }

    private static void addIfSet(JsonObject cfgObj, OpenAiCompatibleSessionSettingsKeyEnum key, Integer value) {
        if (value != null) {
            cfgObj.addProperty(key.key(), value);
        }
    }
}
