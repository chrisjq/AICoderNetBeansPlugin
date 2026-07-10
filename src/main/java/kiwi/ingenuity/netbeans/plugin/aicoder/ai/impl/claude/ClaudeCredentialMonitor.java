package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches the Claude OAuth credentials file and, when the user
 * (re-)authenticates after the plugin has started, proactively refreshes the
 * usage/model data.
 *
 * <p>
 * The usage/model fetches are otherwise only triggered by UI events (tab
 * switch, session start, turn complete), so a {@code claude login} performed
 * while the panel simply sits idle would go unnoticed until the next such
 * event. This monitor polls the credentials file's modification time on a cheap
 * fixed interval and, on a change, drops any stale rate limit and re-fetches —
 * so a late login recovers on its own rather than staying blank until an IDE
 * restart.
 *
 * <p>
 * Lifecycle is owned by {@code Installer}: {@link #start()} on plugin
 * activation, {@link #stop()} on uninstall.
 */
public final class ClaudeCredentialMonitor {

    private static final Logger LOG = Logger.getLogger(ClaudeCredentialMonitor.class.getName());
    private static final long POLL_INTERVAL_SECONDS = 30;

    private static final ClaudeCredentialMonitor INSTANCE = new ClaudeCredentialMonitor();

    public static ClaudeCredentialMonitor getInstance() {
        return INSTANCE;
    }

    private ScheduledExecutorService scheduler;

    private ClaudeCredentialMonitor() {
    }

    /**
     * Starts polling. Idempotent — a second call while already running is a
     * no-op.
     */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-credential-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll,
                POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops polling and releases the daemon thread.
     */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void poll() {
        try {
            if (AnthropicApiClient.refreshCredentialsState()) {
                LOG.info("Claude credentials changed — refreshing usage/model data");
                ClaudeAiImplementation.onCredentialsChanged();
            }
        }
        catch (Exception e) {
            // Never let a single poll failure kill the scheduled task.
            LOG.log(Level.FINE, "Credential monitor poll failed", e);
        }
    }
}
