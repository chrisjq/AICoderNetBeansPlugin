package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;

/**
 * Shares one {@link ServerPoller} per normalised root and API-key hash (spec §3.4). A session whose URL or key changes
 * leaves its old poller before joining the new one. The old poller stops when its last session leaves.
 */
public final class PollerRegistry {

    private final OpenAiCompatibleLimits limits;
    private final ScheduledExecutorService scheduler;
    private final int modelCap;
    private final ProberFactory probers;
    private final Object lock = new Object();
    private final ConcurrentHashMap<String, ServerPoller> pollers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionKeys = new ConcurrentHashMap<>();

    public PollerRegistry(OpenAiCompatibleLimits limits) {
        this.limits = limits == null ? OpenAiCompatibleLimits.defaults() : limits;
        this.scheduler = BackendExecutors.scheduler();
        this.modelCap = OpenAiCompatiblePluginSettings.getPerModelProbes();
        this.probers = (root, key) -> new ProbeClientProber(root, key, this.limits);
    }

    PollerRegistry(OpenAiCompatibleLimits limits, ScheduledExecutorService scheduler, int modelCap,
                   ProberFactory probers) {
        this.limits = limits == null ? OpenAiCompatibleLimits.defaults() : limits;
        this.scheduler = scheduler;
        this.modelCap = modelCap;
        this.probers = probers;
    }

    /**
     * Joins {@code sessionId}, leaving any poller it already belongs to first.
     */
    public void join(String sessionId, String url, String keyHash, char[] keyOrNull, ServerTypeEnum type,
                     String model, ServerPoller.FactsListener listener) {
        String root = BaseUrls.root(url);
        String hash = keyHash == null ? "" : keyHash;
        String key = root + "\n" + hash;
        char[] copy = copyKey(keyOrNull);
        ServerPoller poller;
        synchronized (lock) {
            leaveLocked(sessionId);
            poller = pollers.computeIfAbsent(key, ignored -> new ServerPoller(root, hash, keySet(copy),
                                                                              probers.create(root, copy), scheduler, limits, modelCap));
            sessionKeys.put(sessionId, key);
        }
        poller.add(sessionId, type, model, url, listener);
    }

    /**
     * Leaves the current poller, then joins the new root and key. Leave finishes before the new poller is asked to
     * probe.
     */
    public void retarget(String sessionId, String url, String keyHash, char[] keyOrNull, ServerTypeEnum type,
                         String model, ServerPoller.FactsListener listener) {
        join(sessionId, url, keyHash, keyOrNull, type, model, listener);
    }

    public void leave(String sessionId) {
        synchronized (lock) {
            leaveLocked(sessionId);
        }
    }

    public void postReply(String sessionId) {
        ServerPoller poller = pollerOf(sessionId);
        if (poller != null) {
            poller.postReply(sessionId);
        }
    }

    /**
     * Same root and key; the server type or model changed. Not a periodic tick, so a running cycle is followed by
     * another.
     */
    public void settingsChanged(String sessionId, ServerTypeEnum type, String model) {
        ServerPoller poller = pollerOf(sessionId);
        if (poller != null) {
            poller.settingsChanged(sessionId, type, model);
        }
    }

    void periodicTick(String sessionId) {
        ServerPoller poller = pollerOf(sessionId);
        if (poller != null) {
            poller.periodicTick();
        }
    }

    public int size() {
        synchronized (lock) {
            return pollers.size();
        }
    }

    private ServerPoller pollerOf(String sessionId) {
        synchronized (lock) {
            String key = sessionKeys.get(sessionId);
            return key == null ? null : pollers.get(key);
        }
    }

    private void leaveLocked(String sessionId) {
        String key = sessionKeys.remove(sessionId);
        if (key == null) {
            return;
        }
        ServerPoller poller = pollers.get(key);
        if (poller != null && poller.remove(sessionId)) {
            pollers.remove(key, poller);
        }
    }

    private static boolean keySet(char[] key) {
        return key != null && key.length > 0;
    }

    private static char[] copyKey(char[] key) {
        return key == null ? null : Arrays.copyOf(key, key.length);
    }

    interface ProberFactory {

        ServerPoller.Prober create(String root, char[] keyOrNull);
    }

    /**
     * Production prober. GETs and POSTs go through {@link ProbeClient}, which runs on
     * {@code BackendExecutors.probePool()}.
     */
    private static final class ProbeClientProber implements ServerPoller.Prober {

        private final ProbeClient client = new ProbeClient();
        private final OpenAiCompatibleLimits limits;
        private final String root;
        private final char[] key;

        private ProbeClientProber(String root, char[] key, OpenAiCompatibleLimits limits) {
            this.root = root;
            this.key = key;
            this.limits = limits;
        }

        @Override
        public CompletableFuture<ProbeClient.ProbeResult> probe(String path) {
            return client.get(url(path), key, limits);
        }

        @Override
        public CompletableFuture<ProbeClient.ProbeResult> probe(String path, String jsonBody) {
            return client.post(url(path), jsonBody, key, limits);
        }

        @Override
        public void close() {
            if (key != null) {
                Arrays.fill(key, '\0');
            }
        }

        private String url(String path) {
            if (path == null) {
                return root;
            }
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path;
            }
            String base = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
            return base + (path.startsWith("/") ? path : "/" + path);
        }
    }
}
