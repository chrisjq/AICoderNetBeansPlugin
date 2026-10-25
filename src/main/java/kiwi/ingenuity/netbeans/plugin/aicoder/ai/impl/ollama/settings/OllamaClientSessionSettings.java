package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTriggerEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context.OllamaContextTrimStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettingsKeyEnum;

/**
 * Context-broker configuration shared by every OpenAI-compatible backend.
 *
 * A new backend extends this rather than AiModelSessionSettings and inherits the whole surface. All fields are
 * nullable: null means "use the global default", matching how the rest of the settings tree behaves.
 *
 * The constructor deliberately matches AiModelSessionSettings' 9-argument form and passes straight through. Context
 * values arrive via setters, so no existing subclass constructor changes — OllamaSessionSettings keeps its 10-argument
 * constructor and OllamaSessionSettingsTest keeps compiling.
 */
public class OllamaClientSessionSettings extends AiModelSessionSettings {

    private volatile OllamaContextTriggerEnum contextTrimTrigger;
    private volatile OllamaContextTrimStrategyEnum contextTrimStrategy;
    private volatile Integer contextTokenThreshold;
    private volatile Integer contextTrimTargetPercent;
    private volatile Integer contextMaxMessages;
    private volatile Boolean contextPersistOnClose;

    public OllamaClientSessionSettings() {
        super();
    }

    public OllamaClientSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
                                       Boolean allowInterAiComms, Boolean autoNotifyInbox, Boolean allowImportantMessages,
                                       String sessionInstructions, String model, Boolean autoAccept,
                                       Boolean allowWebRequests) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
              allowImportantMessages, sessionInstructions, model, autoAccept,
              allowWebRequests);
    }

    public OllamaContextTriggerEnum contextTrimTrigger() {
        return contextTrimTrigger;
    }

    public void setContextTrimTrigger(OllamaContextTriggerEnum v) {
        this.contextTrimTrigger = v;
    }

    public OllamaContextTrimStrategyEnum contextTrimStrategy() {
        return contextTrimStrategy;
    }

    public void setContextTrimStrategy(OllamaContextTrimStrategyEnum v) {
        this.contextTrimStrategy = v;
    }

    public Integer contextTokenThreshold() {
        return contextTokenThreshold;
    }

    public void setContextTokenThreshold(Integer v) {
        this.contextTokenThreshold = v;
    }

    public Integer contextTrimTargetPercent() {
        return contextTrimTargetPercent;
    }

    public void setContextTrimTargetPercent(Integer v) {
        this.contextTrimTargetPercent = v;
    }

    public Integer contextMaxMessages() {
        return contextMaxMessages;
    }

    public void setContextMaxMessages(Integer v) {
        this.contextMaxMessages = v;
    }

    public Boolean contextPersistOnClose() {
        return contextPersistOnClose;
    }

    public void setContextPersistOnClose(Boolean v) {
        this.contextPersistOnClose = v;
    }

    public OllamaContextTriggerEnum effectiveContextTrimTrigger() {
        if (contextTrimTrigger != null) {
            return contextTrimTrigger;
        }
        try {
            return OllamaContextTriggerEnum.valueOf(PluginSettings.getContextTrimTrigger());
        }
        catch (IllegalArgumentException ex) {
            return OllamaContextTriggerEnum.ESTIMATED_TOKENS;
        }
    }

    public OllamaContextTrimStrategyEnum effectiveContextTrimStrategy() {
        if (contextTrimStrategy != null) {
            return contextTrimStrategy;
        }
        try {
            return OllamaContextTrimStrategyEnum.valueOf(PluginSettings.getContextTrimStrategy());
        }
        catch (IllegalArgumentException ex) {
            return OllamaContextTrimStrategyEnum.DROP_MARKED;
        }
    }

    public int effectiveContextTokenThreshold() {
        return contextTokenThreshold != null ? contextTokenThreshold : PluginSettings.getContextTokenThreshold();
    }

    public int effectiveContextTrimTargetPercent() {
        return contextTrimTargetPercent != null ? contextTrimTargetPercent : PluginSettings.getContextTrimTargetPercent();
    }

    public int effectiveContextMaxMessages() {
        return contextMaxMessages != null ? contextMaxMessages : PluginSettings.getContextMaxMessages();
    }

    public boolean effectiveContextPersistOnClose() {
        return contextPersistOnClose != null ? contextPersistOnClose : PluginSettings.isContextPersistOnClose();
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (contextTrimTrigger != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.CONTEXT_TRIM_TRIGGER.key(),
                               contextTrimTrigger.name());
        }
        if (contextTrimStrategy != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.CONTEXT_TRIM_STRATEGY.key(),
                               contextTrimStrategy.name());
        }
        if (contextTokenThreshold != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.CONTEXT_TOKEN_THRESHOLD.key(),
                               contextTokenThreshold);
        }
        if (contextTrimTargetPercent != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.CONTEXT_TRIM_TARGET_PERCENT.key(),
                               contextTrimTargetPercent);
        }
        if (contextMaxMessages != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.CONTEXT_MAX_MESSAGES.key(),
                               contextMaxMessages);
        }
        if (contextPersistOnClose != null) {
            cfgObj.addProperty(AiSessionSettingsKeyEnum.CONTEXT_PERSIST_ON_CLOSE.key(),
                               contextPersistOnClose);
        }
    }
}
