package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;

public class CodexSessionSettings extends AiModelSessionSettings {

    private volatile String threadId;

    public CodexSessionSettings() {
        super();
    }

    public CodexSessionSettings(Integer maxHistory, Boolean restrictToProjectFiles, Boolean allowInterAiComms,
            Boolean autoNotifyInbox, Boolean allowImportantMessages, String sessionInstructions, String model,
            Boolean autoAccept, Boolean allowWebRequests) {
        super(maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
                allowImportantMessages, sessionInstructions, model, autoAccept, allowWebRequests);
    }

    /**
     * Codex-generated {@code app-server} thread id, captured from
     * thread/start's response.
     */
    public String threadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    @Override
    public void populateJsonObject(JsonObject cfgObj) {
        super.populateJsonObject(cfgObj);
        if (threadId != null) {
            cfgObj.addProperty("threadId", threadId);
        }
    }
}
