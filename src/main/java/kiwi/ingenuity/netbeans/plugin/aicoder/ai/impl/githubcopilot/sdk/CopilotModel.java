package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.util.List;

/**
 * Represents model metadata discovered from the active Copilot SDK.
 */
public record CopilotModel(String id, String name, List<String> supportedEfforts,
                           String defaultEffort) {

}
