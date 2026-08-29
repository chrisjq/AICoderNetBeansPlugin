package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

/**
 * One proposed file change within an ordered change set. Backend-neutral: it carries only the file path and the
 * proposed content — no backend-specific reply vocabulary, no diff representation. A second backend must be able to
 * construct these unmodified.
 */
public record MultiPermissionItem(
        String filePath,
        String proposedContent) {

}
