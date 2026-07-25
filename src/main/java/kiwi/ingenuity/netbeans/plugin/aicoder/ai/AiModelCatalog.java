package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Type-scoped cache and subscription point for asynchronously discovered model
 * lists. Providers retain ownership of their discovery transport while this
 * class ensures every panel sees the same successful result.
 */
public final class AiModelCatalog {

    private static final long DEFAULT_REFRESH_INTERVAL_MS = 10 * 60 * 1000L;
    private static final long REFRESH_TIMEOUT_MS = 2 * 60 * 1000L;

    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Consumer<List<String>>> listeners = new CopyOnWriteArrayList<>();
    private List<String> models = List.of();
    private long lastSuccessfulLoadMs;
    private long refreshStartedMs;
    private boolean refreshing;

    public List<String> getCachedModels() {
        synchronized (lock) {
            return models;
        }
    }

    public void addListener(Consumer<List<String>> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        List<String> cached = getCachedModels();
        if (!cached.isEmpty()) {
            listener.accept(cached);
        }
    }

    public void removeListener(Consumer<List<String>> listener) {
        listeners.remove(listener);
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
     * Completes a successful discovery and notifies listeners only when the
     * discovered list differs from the previously successful list.
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
            listeners.forEach(listener -> listener.accept(snapshot));
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
