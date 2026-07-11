package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;

public class ClaudeSessionSettings extends AiModelSessionSettings {

    public ClaudeSessionSettings() {
        super();
    }

    public ClaudeSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles, Boolean allowInterAiComms, Boolean autoNotifyInbox, Boolean allowImportantMessages, String sessionInstructions, String model, Boolean autoAccept, Boolean allowWebRequests) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox, allowImportantMessages, sessionInstructions, model, autoAccept, allowWebRequests);
    }

}
