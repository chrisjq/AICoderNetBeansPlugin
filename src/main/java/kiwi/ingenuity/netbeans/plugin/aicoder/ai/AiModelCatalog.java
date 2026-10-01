package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Type-scoped cache of asynchronously discovered model lists. Providers retain ownership of their discovery
 * transport; a changed successful result is announced as an {@link AvailableModelsEvent} on the
 * {@link AiTypePropertyBus}, and consumers that appear later seed themselves from {@link #getCachedModels()}.
 */
public final class AiModelCatalog {

    private static final long DEFAULT_REFRESH_INTERVAL_MS = 10 * 60 * 1000L;
    private static final long REFRESH_TIMEOUT_MS = TimeoutEnum.AI_MODEL_CATALOG_REFRESH_TIMEOUT_MILLIS.millis();

    private final Object lock = new Object();
    private final AiTypeEnum aiType;
    private List<String> models = List.of();
    private long lastSuccessfulLoadMs;
    private long refreshStartedMs;
    private boolean refreshing;

    public AiModelCatalog(AiTypeEnum aiType) {
        this.aiType = Objects.requireNonNull(aiType, "aiType");
    }

    public List<String> getCachedModels() {
        synchronized (lock) {
            return models;
        }
    }

    public boolean beginRefresh() {
        return beginRefresh(DEFAULT_REFRESH_INTERVAL_MS);
    }

    public boolean beginRefresh(long refreshIntervalMs) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (refreshing && now - refreshStartedMs < REFRESH_TIMEOUT_MS) {
                return false;
            }
            if (lastSuccessfulLoadMs > 0 && now - lastSuccessfulLoadMs < refreshIntervalMs) {
                return false;
            }
            refreshing = true;
            refreshStartedMs = now;
            return true;
        }
    }

    /**
     * Completes a successful discovery and fires the event only when the discovered list differs from the
     * previously successful list.
     */
    public boolean publish(List<String> discoveredModels) {
        List<String> snapshot = List.copyOf(new ArrayList<>(discoveredModels));
        boolean changed;
        synchronized (lock) {
            refreshing = false;
            changed = !models.equals(snapshot);
            models = snapshot;
            lastSuccessfulLoadMs = System.currentTimeMillis();
        }
        if (changed) {
            AiTypePropertyBus.getInstance().fire(aiType, new AvailableModelsEvent(snapshot));
        }
        return changed;
    }

    public void refreshFailed() {
        synchronized (lock) {
            refreshing = false;
        }
    }

    public void invalidate() {
        synchronized (lock) {
            lastSuccessfulLoadMs = 0L;
        }
    }
}
