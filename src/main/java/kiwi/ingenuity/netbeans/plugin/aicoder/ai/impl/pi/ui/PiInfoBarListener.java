package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

/**
 * Notified when the user changes a combo in {@link PiAiInfoBarExtension}, mirroring {@code ClaudeInfoBarListener}.
 * {@link PiAiInfoBarExtension} already sends the change to the running session itself via {@link PiSessionControl};
 * this is an additional hook for callers that also want to observe the choice (e.g. status logging).
 */
public interface PiInfoBarListener {

    void onModelChanged(String providerSlashId);

    void onThinkingLevelChanged(String level);

    void onCompactRequested();
}
