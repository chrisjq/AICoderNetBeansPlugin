package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui;

/**
 *
 * @author chris
 */
public interface GithubCopilotInfoBarListener {

    void onCompactRequested();

    void onModelChanged(String model);

    /**
     * The user picked a reasoning-effort level in the info bar — {@code null} means the "(model default)" entry, i.e.
     * omit the setting. Mirrors {@code PiInfoBarListener.onThinkingLevelChanged}.
     */
    default void onReasoningEffortChanged(String reasoningEffort) {
    }

}
