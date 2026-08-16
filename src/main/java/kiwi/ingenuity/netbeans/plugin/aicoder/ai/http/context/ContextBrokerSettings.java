package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

/**
 * Snapshot of the context configuration, read once when the broker is built.
 * The broker never re-reads preferences: changing a setting takes effect on the
 * next session start, not the next turn.
 */
public class ContextBrokerSettings {

    public static ContextBrokerSettings defaults() {
        return new ContextBrokerSettings();
    }

    private ContextTriggerEnum trigger = ContextTriggerEnum.ESTIMATED_TOKENS;
    private ContextTrimStrategyEnum strategy = ContextTrimStrategyEnum.DROP_MARKED;
    private int tokenThreshold = 12000;
    private int trimTargetPercent = 70;
    private int maxMessages = 0;
    private boolean persistOnClose = false;

    public ContextTriggerEnum trigger() {
        return trigger;
    }

    public void setTrigger(ContextTriggerEnum trigger) {
        this.trigger = trigger;
    }

    public ContextTrimStrategyEnum strategy() {
        return strategy;
    }

    public void setStrategy(ContextTrimStrategyEnum strategy) {
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
