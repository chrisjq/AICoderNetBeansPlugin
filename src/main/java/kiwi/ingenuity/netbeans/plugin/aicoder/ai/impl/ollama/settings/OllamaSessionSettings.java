package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.OpenAiClientSessionSettings;

public class OllamaSessionSettings extends OpenAiClientSessionSettings {

    private volatile String baseUrl;
    private volatile String reasoningEffort;
    /**
     * Null means inherit the AiTypeEnum schema-workaround default for existing sessions.
     */
    private volatile Boolean useNativeToolCalling;

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

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public void setUseNativeToolCalling(Boolean useNativeToolCalling) {
        this.useNativeToolCalling = useNativeToolCalling;
    }

    public Boolean useNativeToolCalling() {
        return useNativeToolCalling;
    }

    /**
     * Ollama keeps model-facing context by default because its local sessions have no remote transcript to
     * reconstruct after restart. An explicit session value still wins; the nullable distinction also makes
     * existing sessions with an unset value adopt the Ollama default.
     */
    @Override
    public boolean effectiveContextPersistOnClose() {
        return contextPersistOnClose() != null ? contextPersistOnClose() : true;
    }

    @Override
    public String getAdditionalInfo() {
        String extra = super.getAdditionalInfo();
        if (baseUrl != null) {
            extra += ", baseUrl: " + baseUrl;
        }
        if (reasoningEffort != null) {
            extra += ", reasoningEffort: " + reasoningEffort;
        }
        if (useNativeToolCalling != null) {
            extra += ", useNativeToolCalling: " + useNativeToolCalling;
        }
        return extra;
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (baseUrl != null) {
            cfgObj.addProperty(OllamaSessionSettingsKeyEnum.BASE_URL.key(), baseUrl);
        }
        if (reasoningEffort != null) {
            cfgObj.addProperty(OllamaSessionSettingsKeyEnum.REASONING_EFFORT.key(), reasoningEffort);
        }
        if (useNativeToolCalling != null) {
            cfgObj.addProperty(OllamaSessionSettingsKeyEnum.USE_NATIVE_TOOL_CALLING.key(), useNativeToolCalling);
        }
    }
}
