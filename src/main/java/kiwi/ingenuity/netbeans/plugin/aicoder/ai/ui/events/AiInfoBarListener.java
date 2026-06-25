package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.events;

public interface AiInfoBarListener {

    void onStopRequested();

    default void onSettingsRequested() {
    }

    default void onAutoAcceptChanged(boolean autoAccept) {
    }
}
