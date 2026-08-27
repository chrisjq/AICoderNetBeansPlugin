package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

/**
 * Fired by McpHookServer when the AI requests permission to execute Edit or Write. The UI must complete the response
 * with an allow/deny decision. The AI process is blocked on the PreToolUse hook call until the future is resolved.
 */
public record PermissionEvent(
        String toolName, // "Edit" or "Write"
        String filePath,
        String oldString, // Edit: text being replaced
        String newString, // Edit: replacement text
        String writeContent, // Write: full file content
        boolean replaceAll, // Edit: replace every occurrence, not just the first
        CompletableFuture<PermissionDecision> response
        ) implements AiProcessEvent {

    /**
     * Single-replacement Edit or a Write — the shape every caller other than Claude's Edit hook produces.
     */
    public PermissionEvent(String toolName, String filePath, String oldString, String newString,
            String writeContent, CompletableFuture<PermissionDecision> response) {
        this(toolName, filePath, oldString, newString, writeContent, false, response);
    }
}
