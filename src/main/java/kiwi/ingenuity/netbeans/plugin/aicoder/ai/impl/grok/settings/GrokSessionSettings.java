package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;

public class GrokSessionSettings extends AiModelSessionSettings {

    private volatile String reasoningEffort;

    public GrokSessionSettings() {
        super();
    }

    public GrokSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles, Boolean allowInterAiComms, Boolean autoNotifyInbox, Boolean allowImportantMessages, String sessionInstructions, String model, Boolean autoAccept, Boolean allowWebRequests) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox, allowImportantMessages, sessionInstructions, model, autoAccept, allowWebRequests);
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (reasoningEffort != null) {
            cfgObj.addProperty(GrokSessionSettingsKeyEnum.REASONING_EFFORT.key(), reasoningEffort);
        }
    }
}
