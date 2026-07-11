package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.events;

public interface DiffDecisionListener {

    void onAccepted(String message);

    void onRejected(String message);
}
