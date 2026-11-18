package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

/**
 * Carries the outcome and optional removal counts from a manual compaction.
 */
public record CopilotCompaction(Boolean success, Long tokensRemoved,
                                Long messagesRemoved) {

}
