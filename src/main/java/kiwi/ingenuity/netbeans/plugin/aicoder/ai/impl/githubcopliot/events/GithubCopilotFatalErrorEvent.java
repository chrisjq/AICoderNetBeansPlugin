package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

public class GithubCopilotFatalErrorEvent implements AiProcessImplEvent {

    private final String errorMessage;
    private final String errorType;

    public GithubCopilotFatalErrorEvent(String errorType, String errorMessage) {
        this.errorType = errorType;
        this.errorMessage = errorMessage;
    }

    public String errorType() {
        return errorType;
    }

    public String errorMessage() {
        return errorMessage;
    }
}
