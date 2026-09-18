package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;

/**
 * Per-session pi settings: the pi session id used for {@code --session-id} (generated once at session creation and
 * reused on resume), the model and the thinking level. Model is inherited from {@link AiModelSessionSettings}; pi
 * session id and thinking level are added here.
 */
public class PiSessionSettings extends AiModelSessionSettings {

    private volatile String piSessionId;
    private volatile String thinkingLevel;

    public PiSessionSettings() {
        super();
    }

    public PiSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles, Boolean allowInterAiComms, Boolean autoNotifyInbox, Boolean allowImportantMessages, String sessionInstructions, String model, Boolean autoAccept, Boolean allowWebRequests) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox, allowImportantMessages, sessionInstructions, model, autoAccept, allowWebRequests);
    }

    public String piSessionId() {
        return piSessionId;
    }

    public void setPiSessionId(String piSessionId) {
        this.piSessionId = piSessionId;
    }

    public String thinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (piSessionId != null) {
            cfgObj.addProperty(PiSessionSettingsKeyEnum.PI_SESSION_ID.key(), piSessionId);
        }
        if (thinkingLevel != null) {
            cfgObj.addProperty(PiSessionSettingsKeyEnum.THINKING_LEVEL.key(), thinkingLevel);
        }
    }
}
