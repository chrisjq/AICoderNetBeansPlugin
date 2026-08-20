package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

public final class ConfirmEvent implements AiProcessEvent {

    private final String toolName;
    private final String displayText;
    private final String filePath;
    private final String targetPath;
    private final CompletableFuture<PermissionDecision> response;
    private final boolean requireExplicitApproval;

    public ConfirmEvent(String toolName, String displayText, String filePath,
            String targetPath, CompletableFuture<PermissionDecision> response) {
        this(toolName, displayText, filePath, targetPath, response, false);
    }

    public ConfirmEvent(String toolName, String displayText, String filePath,
            String targetPath, CompletableFuture<PermissionDecision> response,
            boolean requireExplicitApproval) {
        this.toolName = toolName;
        this.displayText = displayText;
        this.filePath = filePath;
        this.targetPath = targetPath;
        this.response = response;
        this.requireExplicitApproval = requireExplicitApproval;
    }

    public String toolName() {
        return toolName;
    }

    public String displayText() {
        return displayText;
    }

    public String filePath() {
        return filePath;
    }

    public String targetPath() {
        return targetPath;
    }

    public CompletableFuture<PermissionDecision> response() {
        return response;
    }

    public boolean requireExplicitApproval() {
        return requireExplicitApproval;
    }
}
