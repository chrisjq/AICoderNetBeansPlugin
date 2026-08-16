package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;

public class OpenCodeSessionSettings extends AiModelSessionSettings {

    private volatile String mode;
    private volatile String acpSessionId;

    public OpenCodeSessionSettings() {
        super();
    }

    public OpenCodeSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles, Boolean allowInterAiComms, Boolean autoNotifyInbox, Boolean allowImportantMessages, String sessionInstructions, String model, Boolean autoAccept, Boolean allowWebRequests) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox, allowImportantMessages, sessionInstructions, model, autoAccept, allowWebRequests);
    }

    public String mode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String acpSessionId() {
        return acpSessionId;
    }

    public void setAcpSessionId(String acpSessionId) {
        this.acpSessionId = acpSessionId;
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (mode != null) {
            cfgObj.addProperty("mode", mode);
        }
        if (acpSessionId != null) {
            cfgObj.addProperty("acpSessionId", acpSessionId);
        }
    }
}
