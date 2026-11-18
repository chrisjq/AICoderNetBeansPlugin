package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.util.Map;

/**
 * Carries the permission details that the backend policy needs to evaluate.
 */
public record CopilotPermissionRequest(String kind, String toolCallId,
                                       Map<String, Object> extensionData) {

}
