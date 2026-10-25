package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.context;

/**
 * Snapshot of the context configuration, read once when the broker is built. The broker never re-reads preferences:
 * changing a setting takes effect on the next session start, not the next turn.
 */
public class OllamaContextBrokerSettings {

    public static OllamaContextBrokerSettings defaults() {
        return new OllamaContextBrokerSettings();
    }

    private OllamaContextTriggerEnum trigger = OllamaContextTriggerEnum.ESTIMATED_TOKENS;
    private OllamaContextTrimStrategyEnum strategy = OllamaContextTrimStrategyEnum.DROP_MARKED;
    /**
     * Zero means derive the trim threshold from the provider's discovered window. Positive values remain explicit user
     * overrides.
     */
    private int tokenThreshold = 0;
    private int trimTargetPercent = 70;
    private int maxMessages = 0;
    private boolean persistOnClose = false;

    public OllamaContextTriggerEnum trigger() {
        return trigger;
    }

    public void setTrigger(OllamaContextTriggerEnum trigger) {
        this.trigger = trigger;
    }

    public OllamaContextTrimStrategyEnum strategy() {
        return strategy;
    }

    public void setStrategy(OllamaContextTrimStrategyEnum strategy) {
        this.strategy = strategy;
    }

    public int tokenThreshold() {
        return tokenThreshold;
    }

    public void setTokenThreshold(int tokenThreshold) {
        this.tokenThreshold = tokenThreshold;
    }

    public int trimTargetPercent() {
        return trimTargetPercent;
    }

    public void setTrimTargetPercent(int trimTargetPercent) {
        this.trimTargetPercent = trimTargetPercent;
    }

    public int maxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public boolean persistOnClose() {
        return persistOnClose;
    }

    public void setPersistOnClose(boolean persistOnClose) {
        this.persistOnClose = persistOnClose;
    }
}
