package kiwi.ingenuity.netbeans.plugin.aicoder.events;

public interface SessionLifecycleListener {

    default void onSessionStarted() {
    }

    default void onTurnComplete() {
    }

    default void onChatEnabled() {
    }

    default void onStopRequested() {
    }

    default void onStopped() {
    }
}
