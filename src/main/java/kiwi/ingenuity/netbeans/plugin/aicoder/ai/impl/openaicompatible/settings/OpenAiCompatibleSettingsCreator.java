package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleBasicPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

public class OpenAiCompatibleSettingsCreator
        extends AiModelSessionSettingsCreator<OpenAiCompatibleSessionSettings> {

    @Override
    public AiSessionCreateSettingsPanel<OpenAiCompatibleSessionSettings> createSettingsPanel() {
        return new OpenAiCompatibleBasicPanel();
    }

    @Override
    public AiSessionCreateSettingsPanel<OpenAiCompatibleSessionSettings> createSettingsPanel(
            String sessionId, OpenAiCompatibleSessionSettings current) {
        return sessionId == null || sessionId.isBlank()
               ? createSettingsPanel()
               : new OpenAiCompatibleBasicPanel(new SessionSecrets(sessionId, current != null && current.effectiveApiKeySet()));
    }

    @Override
    public OpenAiCompatibleSessionSettings create() {
        return new OpenAiCompatibleSessionSettings();
    }

    @Override
    public void update(OpenAiCompatibleSessionSettings settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        setEnum(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.SERVER_TYPE.key(),
                ServerTypeEnum.class, settings::setServerType);
        setString(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.BASE_URL.key(), settings::setBaseUrl);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.CONTEXT_WINDOW_OVERRIDE.key(),
               settings::setContextWindowOverride);
        setEnum(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.TOOL_CALLING_MODE.key(),
                ToolCallingModeEnum.class, settings::setToolCallingMode);
        setBoolean(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.SAVE_CONTEXT_ON_CLOSE.key(),
                   settings::setSaveContextOnClose);
        setBoolean(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.API_KEY_SET.key(), settings::setApiKeySet);

        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.FIRST_BYTE_TIMEOUT_SECONDS.key(),
               settings::setFirstByteTimeoutSeconds);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.IDLE_TIMEOUT_SECONDS.key(),
               settings::setIdleTimeoutSeconds);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.TOTAL_CAP_MINUTES.key(),
               settings::setTotalCapMinutes);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.MAX_TOOL_ROUNDS.key(), settings::setMaxToolRounds);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.MAX_LENGTH_CONTINUATIONS.key(),
               settings::setMaxLengthContinuations);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.MAX_MALFORMED_ROUNDS.key(),
               settings::setMaxMalformedRounds);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.REPETITION_WINDOW.key(),
               settings::setRepetitionWindow);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.UNPRODUCTIVE_ROUNDS.key(),
               settings::setUnproductiveRounds);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.NATIVE_FAILURES_BEFORE_SCHEMA.key(),
               settings::setNativeFailuresBeforeSchema);

        setEnum(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.COMPACTION_STRATEGY.key(),
                CompactionStrategyEnum.class, settings::setCompactionStrategy);
        setBoolean(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.AUTO_COMPACT.key(), settings::setAutoCompact);
        setBoolean(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.PRUNE.key(), settings::setPrune);
        setDouble(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.TEMPERATURE.key(), settings::setTemperature);
        setDouble(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.TOP_P.key(), settings::setTopP);
        setString(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.SUMMARY_MODEL.key(), settings::setSummaryModel);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.MAX_OUTPUT_TOKENS.key(),
               settings::setMaxOutputTokens);
        setString(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.REASONING_EFFORT.key(),
                  settings::setReasoningEffort);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.SUMMARISE_THRESHOLD.key(),
               settings::setSummariseThreshold);
        setBoolean(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.SHOW_REASONING.key(), settings::setShowReasoning);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.RESERVE_PERCENT.key(), settings::setReservePercent);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.RESERVE_MINIMUM.key(), settings::setReserveMinimum);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.RESERVE_MAX_SHARE_PERCENT.key(),
               settings::setReserveMaxSharePercent);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.RESERVE_ABSOLUTE.key(), settings::setReserveAbsolute);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.KEEP_RECENT_PERCENT.key(),
               settings::setKeepRecentPercent);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.KEEP_RECENT_MINIMUM.key(),
               settings::setKeepRecentMinimum);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.KEEP_RECENT_ABSOLUTE.key(),
               settings::setKeepRecentAbsolute);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.TOOL_RESULT_SHARE_PERCENT.key(),
               settings::setToolResultSharePercent);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.PRUNE_THRESHOLD_CHARS.key(),
               settings::setPruneThresholdChars);
        setInt(cfgObj, OpenAiCompatibleSessionSettingsKeyEnum.PRUNE_CHARS_KEPT.key(), settings::setPruneCharsKept);

        String namesKey = OpenAiCompatibleSessionSettingsKeyEnum.EXTRA_HEADER_NAMES.key();
        if (cfgObj.has(namesKey) && cfgObj.get(namesKey).isJsonArray()) {
            ArrayList<String> names = new ArrayList<>();
            for (JsonElement element : cfgObj.getAsJsonArray(namesKey)) {
                if (element.isJsonPrimitive()) {
                    names.add(element.getAsString());
                }
            }
            settings.setExtraHeaderNames(names);
        }
        String valuesKey = OpenAiCompatibleSessionSettingsKeyEnum.EXTRA_HEADER_PLAIN_VALUES.key();
        if (cfgObj.has(valuesKey) && cfgObj.get(valuesKey).isJsonObject()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : cfgObj.getAsJsonObject(valuesKey).entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    values.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            settings.setExtraHeaderPlainValues(values);
        }
        fillMissingServer(settings);
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
        if (settings instanceof OpenAiCompatibleSessionSettings compatible) {
            boolean takeDefaultUrl = compatible.baseUrl() == null || compatible.baseUrl().isBlank();
            OpenAiCompatiblePluginSettings.getDefaultServer().ifPresent(server -> {
                if (takeDefaultUrl) {
                    compatible.setBaseUrl(server.url());
                    if (compatible.serverType() == null) {
                        compatible.setServerType(server.serverType());
                    }
                }
            });
            if (compatible.model() == null) {
                compatible.setModel(OpenAiCompatiblePluginSettings.getModel());
            }
            if (compatible.reasoningEffort() == null) {
                compatible.setReasoningEffort(OpenAiCompatiblePluginSettings.getReasoningEffort());
            }
        }
    }

    public static void fillMissingServer(OpenAiCompatibleSessionSettings settings) {
        if (settings == null || (settings.baseUrl() != null && !settings.baseUrl().isBlank())) {
            return;
        }
        OpenAiCompatiblePluginSettings.getDefaultServer().ifPresent(server -> {
            settings.setBaseUrl(server.url());
            if (settings.serverType() == null) {
                settings.setServerType(server.serverType());
            }
        });
    }

    private static void setString(JsonObject object, String key, Consumer<String> setter) {
        if (object.has(key) && object.get(key).isJsonPrimitive()) {
            setter.accept(object.get(key).getAsString());
        }
    }

    private static void setInt(JsonObject object, String key, Consumer<Integer> setter) {
        if (object.has(key) && object.get(key).isJsonPrimitive()) {
            setter.accept(object.get(key).getAsInt());
        }
    }

    private static void setDouble(JsonObject object, String key, Consumer<Double> setter) {
        if (object.has(key) && object.get(key).isJsonPrimitive()) {
            try {
                setter.accept(object.get(key).getAsDouble());
            }
            catch (NumberFormatException ex) {
                // a hand-edited value that is not a number is ignored
            }
        }
    }

    private static void setBoolean(JsonObject object, String key, Consumer<Boolean> setter) {
        if (object.has(key) && object.get(key).isJsonPrimitive()) {
            setter.accept(object.get(key).getAsBoolean());
        }
    }

    private static <T extends Enum<T>> void setEnum(JsonObject object, String key, Class<T> type,
                                                    Consumer<T> setter) {
        if (object.has(key) && object.get(key).isJsonPrimitive()) {
            try {
                setter.accept(Enum.valueOf(type, object.get(key).getAsString()));
            }
            catch (IllegalArgumentException ex) {
                // A hand-edited settings value is ignored rather than preventing session loading.
            }
        }
    }
}
