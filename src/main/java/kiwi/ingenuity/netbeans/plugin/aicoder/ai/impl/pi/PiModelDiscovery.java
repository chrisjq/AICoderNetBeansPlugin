package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;

/**
 * Live discovery of the model list available to pi's configured providers, via {@code pi --list-models}
 * (https://pi.dev/docs/latest/usage). Confirmed against a real installed pi CLI (v0.85.1); actual output looks like:
 * <pre>
 * provider        model            context  max-out  thinking  images
 * github-copilot  claude-sonnet-5  1M       128K     yes       yes
 * </pre> — a header row followed by one whitespace-column-aligned row per model. Only the first two columns (provider,
 * model) are used; each row is published as {@code "provider/model"}. Any failure (pi not installed, not authenticated
 * with any provider, unexpected future format change) is swallowed and the caller keeps whatever was last stored in
 * {@link PiPluginSettings#getKnownModels()}. Mirrors {@code GrokModelDiscovery}'s discover-once-per-IDE-run / cache /
 * broadcast shape.
 *
 * <p>
 * {@code inProgress}/{@code retryCount} are per-class (static), not keyed by executable path or provider config —
 * accepted for now because the plugin supports exactly one pi CLI configuration per IDE run (a single
 * {@code ai.pi.executable}), so there is only ever one discovery to coordinate. Revisit if that ever changes (e.g.
 * per-session executable overrides).
 */
public final class PiModelDiscovery {

    private static final Logger LOG = Logger.getLogger(PiModelDiscovery.class.getName());
    // Real pi provider ids are lowercase letters/digits/hyphens starting with a letter (github-copilot,
    // anthropic, openai, google, ...) — see parseModels() for why this rejects banner/error text.
    private static final Pattern PROVIDER_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    private static final int MAX_RETRIES = 20;
    private static final AtomicBoolean inProgress = new AtomicBoolean(false);
    private static volatile int retryCount = 0;
    /**
     * Test seam: when non-null, replaces {@link PiTimeoutEnum#PI_MODEL_DISCOVERY_MILLIS} as the whole-attempt budget so
     * a hung fake CLI fails a test in milliseconds instead of seconds. Null in production.
     */
    static volatile Long discoveryBudgetMillisForTests = null;

    /**
     * Starts a fresh discovery cycle: resets the retry counter and submits a background fetch that, on success, stores
     * the result in {@link PiPluginSettings#setDiscoveredModels(String[])}, publishes it to
     * {@link PiAiImplementation#modelCatalog()} and broadcasts {@link PiModelsEvent}. On failure, retries up to
     * {@value #MAX_RETRIES} times within the cycle.
     *
     * @param executablePath the located pi CLI path, or null to use PATH
     */
    public static void discoverAsync(String executablePath) {
        discoverAsync(executablePath, PiModelDiscovery::publish);
    }

    /**
     * Same as {@link #discoverAsync(String)} but with a caller-supplied result handler instead of the standard
     * settings/catalog/event publish — used directly by {@code PiAiSettingsTab}'s Refresh button (which repopulates its
     * own combo from the result and re-enables itself from {@code onResult}) and by tests that want to observe the
     * discovered list in isolation.
     *
     * <p>
     * {@code onResult} is guaranteed exactly one terminal call per invocation of this method, on every outcome: newly
     * discovered models on success; {@link PiPluginSettings#getKnownModels()}'s cached list when a discovery was
     * already in progress (this call is dropped, but the caller still needs a completion signal) or when retries are
     * exhausted without success. A caller that disables a UI control before calling this and re-enables it only from
     * {@code onResult} can therefore rely on that control always coming back, never staying disabled forever.
     */
    public static void discoverAsync(String executablePath, Consumer<List<String>> onResult) {
        retryCount = 0;
        tryDiscover(executablePath, onResult);
    }

    private static void publish(List<String> models) {
        PiPluginSettings.setDiscoveredModels(models.toArray(new String[0]));
        PiAiImplementation.modelCatalog().publish(models);
        AiTypePropertyBus.getInstance().fire(AiTypeEnum.PI, new PiModelsEvent(models));
    }

    private static void tryDiscover(String executablePath, Consumer<List<String>> onResult) {
        if (!inProgress.compareAndSet(false, true)) {
            // Another discovery is already running (e.g. the IDE-startup sweep overlapping a Refresh click) —
            // nothing new to report, but the caller must still get a completion signal or a Refresh button that
            // disables itself before calling this would stay disabled forever (it only re-enables from onResult).
            if (onResult != null) {
                onResult.accept(cachedModels());
            }
            return;
        }
        Thread t = new Thread(() -> {
            boolean shouldRetry = false;
            try {
                List<String> models = discover(executablePath);
                if (models != null && !models.isEmpty()) {
                    retryCount = 0;
                    LOG.log(Level.INFO, "Pi model discovery succeeded ({0} models)", models.size());
                    if (onResult != null) {
                        onResult.accept(models);
                    }
                }
                else {
                    int attempt = ++retryCount;
                    if (attempt < MAX_RETRIES) {
                        LOG.log(Level.INFO, "Pi model discovery: no models returned (attempt {0}/{1}), retrying",
                                new Object[]{attempt, MAX_RETRIES});
                        shouldRetry = true;
                    }
                    else {
                        // Retries exhausted — still a terminal outcome the caller needs to hear about (same
                        // reasoning as the busy-skip branch above), so report the cache rather than nothing.
                        LOG.log(Level.INFO, "Pi model discovery: giving up after {0} attempts", attempt);
                        if (onResult != null) {
                            onResult.accept(cachedModels());
                        }
                    }
                }
            }
            catch (Exception e) {
                int attempt = ++retryCount;
                if (attempt < MAX_RETRIES) {
                    LOG.log(Level.INFO, "Pi model discovery failed (attempt {0}/{1}), retrying: {2}",
                            new Object[]{attempt, MAX_RETRIES, e.getMessage()});
                    shouldRetry = true;
                }
                else {
                    LOG.log(Level.INFO, "Pi model discovery failed after {0} attempts; giving up", attempt);
                    if (onResult != null) {
                        onResult.accept(cachedModels());
                    }
                }
            }
            finally {
                inProgress.set(false);
            }
            if (shouldRetry) {
                tryDiscover(executablePath, onResult);
            }
        }, "pi-model-discovery");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The last successfully discovered list (or empty if none yet), used as the completion payload for outcomes that
     * found nothing new to report (busy-skip, exhausted retries) — see {@link #discoverAsync(String, Consumer)}'s doc
     * comment: every call must reach a terminal {@code onResult} invocation, never silence.
     */
    private static List<String> cachedModels() {
        String[] known = PiPluginSettings.getKnownModels();
        return known != null ? Arrays.asList(known) : List.of();
    }

    static List<String> discover(String executablePath) throws Exception {
        String path = (executablePath != null && !executablePath.isBlank()) ? executablePath : "pi";
        List<String> cmd = PiExecutableLocator.buildHostCommand(path, "--list-models");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        // readLine() only returns at EOF, so draining on THIS thread would block forever on a
        // CLI that hangs with the pipe open — wedging the in-progress latch in tryDiscover for
        // the rest of the IDE run. Drain on a helper daemon thread and bound the WHOLE attempt
        // (drain + process exit) against one deadline, mirroring GrokModelDiscovery's watchdog shape.
        List<String> lines = new ArrayList<>();
        Thread drainer = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            }
            catch (IOException e) {
                LOG.log(Level.FINE, "pi model discovery stdout drain ended", e);
            }
        }, "pi-model-discovery-drain");
        drainer.setDaemon(true);
        drainer.start();
        long budget = discoveryBudgetMillisForTests != null
                      ? discoveryBudgetMillisForTests
                      : PiTimeoutEnum.PI_MODEL_DISCOVERY_MILLIS.millis();
        long deadline = System.nanoTime() + budget * 1_000_000L;
        try {
            drainer.join(budget);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw e;
        }
        if (drainer.isAlive()) {
            // Stdout never reached EOF within the budget: hung CLI. Killing the process closes
            // the pipe so the daemon drain thread exits; report no models (the caller retries,
            // then gives up) so tryDiscover's finally can release the in-progress latch on every path.
            p.destroyForcibly();
            return List.of();
        }
        long remainingMs = Math.max(0L, (deadline - System.nanoTime()) / 1_000_000L);
        if (!p.waitFor(remainingMs, TimeUnit.MILLISECONDS)) {
            // Output fully drained but the process would not exit — same treatment as above.
            p.destroyForcibly();
            return List.of();
        }
        if (p.exitValue() != 0) {
            return List.of();
        }
        return parseModels(lines);
    }

    /**
     * Extracts {@code "provider/model"} entries from raw {@code pi --list-models} stdout lines. Package-private (not
     * private) so it can be unit tested directly. Skips the header row (first column literally {@code "provider"},
     * case-insensitive), blank lines and any row with fewer than two whitespace-delimited columns.
     */
    static List<String> parseModels(List<String> lines) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] columns = trimmed.split("\\s+");
            if (columns.length < 2) {
                continue;
            }
            String provider = columns[0];
            String model = columns[1];
            if ("provider".equalsIgnoreCase(provider)) {
                continue;
            }
            // Every real provider id (github-copilot, anthropic, openai, google, ...) is lowercase
            // letters/digits/hyphens starting with a letter. This rejects banner/error lines ("No models
            // available.", "Run `pi auth login` first.") without needing to special-case their wording.
            if (!PROVIDER_ID_PATTERN.matcher(provider).matches()) {
                continue;
            }
            out.add(provider + "/" + model);
        }
        return new ArrayList<>(out);
    }

    private PiModelDiscovery() {
    }
}
