package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiVersionCheck;

/**
 * Receives user actions from {@link PiAiInfoBarExtension}.
 */
public interface PiInfoBarListener {

    void onModelChanged(String providerSlashId);

    void onThinkingLevelChanged(String level);

    void onCompactRequested();

    default void onVersionVerified(PiVersionCheck check) {
    }

    default void onVersionMarkedNotWorking(PiVersionCheck check) {
    }
}
