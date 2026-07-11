package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;

public class AiModelSessionSettings extends AiSessionSettings {

    private volatile String model;

    public AiModelSessionSettings() {
        super();
    }

    public AiModelSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions, String model,
            Boolean autoAccept, Boolean allowWebRequests) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
                allowImportantMessages, sessionInstructions, autoAccept, allowWebRequests);
        this.model = model;
    }

    public void setModel(String newModel) {
        this.model = newModel;
    }

    /**
     * The model actually chosen for this session, or null if none has been
     * picked yet. Callers needing a usable value fall back to the AI type's
     * plugin-level default (e.g. ClaudePluginSettings.getModel()) themselves —
     * this class stores only what was saved, never a redundant copy of the
     * default.
     */
    public String model() {
        return model;
    }

    @Override
    public String getAdditionalInfo() {
        String m = model();
        return m != null ? ", model: " + m : "";
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (model != null) {
            cfgObj.addProperty(AiModelSessionSettingsKeyEnum.MODEL.key(), model);
        }
    }
}
