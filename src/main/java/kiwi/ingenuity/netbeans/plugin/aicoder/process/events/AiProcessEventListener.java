package kiwi.ingenuity.netbeans.plugin.aicoder.process.events;

@FunctionalInterface
public interface AiProcessEventListener {

    void onAiProcessEvent(AiProcessEvent event);
}
