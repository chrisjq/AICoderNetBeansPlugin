package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiModelSessionSettings;

public class GithubCopilotSessionSettings extends AbstractAiModelSessionSettings {

    public GithubCopilotSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles,
            Boolean allowInterAiComms, Boolean autoNotifyInbox,
            Boolean allowImportantMessages, String sessionInstructions, String model) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
                allowImportantMessages, sessionInstructions, model,
                GithubCopilotPluginSettings.DEFAULT_MODEL);
    }
}
