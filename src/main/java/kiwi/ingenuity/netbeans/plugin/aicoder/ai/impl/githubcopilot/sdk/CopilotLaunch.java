package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Contains the process and timing inputs needed to start a Copilot SDK.
 */
public record CopilotLaunch(String executablePath, List<String> extraArgs,
                            Executor executor, Duration startTimeout) {

}
