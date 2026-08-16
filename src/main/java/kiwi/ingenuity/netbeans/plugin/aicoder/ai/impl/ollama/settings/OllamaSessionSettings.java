package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.OpenAiClientSessionSettings;

public class OllamaSessionSettings extends OpenAiClientSessionSettings {

    private volatile String baseUrl;

    public OllamaSessionSettings() {
        super();
    }

    public OllamaSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions,
            String model, String baseUrl, Boolean autoAccept,
            Boolean allowWebRequests) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms,
                autoNotifyInbox, allowImportantMessages, sessionInstructions,
                model, autoAccept, allowWebRequests);
        this.baseUrl = baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public String getAdditionalInfo() {
        String extra = super.getAdditionalInfo();
        return baseUrl != null ? extra + ", baseUrl: " + baseUrl : extra;
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (baseUrl != null) {
            cfgObj.addProperty(OllamaSessionSettingsKeyEnum.BASE_URL.key(), baseUrl);
        }
    }
}
