package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;

/**
 * Per-session Claude settings: the model (inherited from {@link AiModelSessionSettings}) plus the reasoning effort
 * level. An effort of {@code null} means "use Claude's own default" — the launch command then simply omits
 * {@code --effort}.
 */
public class ClaudeSessionSettings extends AiModelSessionSettings {

    private volatile String effort;

    public ClaudeSessionSettings() {
        super();
    }

    public ClaudeSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles, Boolean allowInterAiComms, Boolean autoNotifyInbox, Boolean allowImportantMessages, String sessionInstructions, String model, Boolean autoAccept, Boolean allowWebRequests) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox, allowImportantMessages, sessionInstructions, model, autoAccept, allowWebRequests);
    }

    public String effort() {
        return effort;
    }

    public void setEffort(String effort) {
        this.effort = effort;
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (effort != null) {
            cfgObj.addProperty(ClaudeSessionSettingsKeyEnum.EFFORT.key(), effort);
        }
    }
}
