package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Fired when Ollama context broker estimates or reports token usage. Used to
 * update the context usage progress bar in the info bar.
 */
public class OllamaTokenUsageEvent implements AiProcessImplEvent {

    private final int used;
    private final int total;

    public OllamaTokenUsageEvent(int used, int total) {
        this.used = used;
        this.total = total;
    }

    public int used() {
        return used;
    }

    public int total() {
        return total;
    }
}
