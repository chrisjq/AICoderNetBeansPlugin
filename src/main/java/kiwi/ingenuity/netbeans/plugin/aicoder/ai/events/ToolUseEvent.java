package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

public record ToolUseEvent(
        String toolName,
        String filePath,
        String proposedContent,
        String originalContent,
        Kind kind
        ) implements AiProcessEvent {

    public boolean isFileModification() {
        return (kind == Kind.WRITE || kind == Kind.EDIT)
                && filePath != null && !filePath.isBlank();
    }

    public enum Kind {
        WRITE, EDIT, OTHER
    }
}
