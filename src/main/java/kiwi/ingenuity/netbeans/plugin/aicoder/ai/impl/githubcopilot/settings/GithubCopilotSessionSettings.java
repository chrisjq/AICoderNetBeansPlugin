package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;

/**
 * Per-session GitHub Copilot settings: model (inherited from {@link AiModelSessionSettings}) plus the reasoning
 * effort. {@code null} means "not set" — omit {@code SessionConfig#setReasoningEffort}/
 * {@code ResumeSessionConfig#setReasoningEffort} entirely rather than sending a value, mirroring pi's
 * {@code PiSessionSettings.thinkingLevel}.
 */
public class GithubCopilotSessionSettings extends AiModelSessionSettings {

    private volatile String reasoningEffort;

    public GithubCopilotSessionSettings() {
        super();
    }

    public GithubCopilotSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles, Boolean allowInterAiComms, Boolean autoNotifyInbox, Boolean allowImportantMessages, String sessionInstructions, String model, Boolean autoAccept, Boolean allowWebRequests) {
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
            cfgObj.addProperty(GithubCopilotSessionSettingsKeyEnum.REASONING_EFFORT.key(), reasoningEffort);
        }
    }
}
