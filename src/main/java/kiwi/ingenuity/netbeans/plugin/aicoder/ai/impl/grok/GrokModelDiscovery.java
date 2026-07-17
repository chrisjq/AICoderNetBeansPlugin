package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Live discovery of the real model list available to the logged-in account, via
 * {@code grok models} (https://docs.x.ai/build/cli/reference). Confirmed
 * against a real installed grok CLI (v0.2.93); actual output looks like:
 * <pre>
 * You are logged in with grok.com.
 *
 * Default model: grok-4.5
 *
 * Available models:
 *   * grok-4.5 (default)
 *   - grok-composer-2.5-fast
 * </pre> The CLI has no {@code --json}/structured-output flag for this
 * subcommand (checked via {@code grok models --help}), so stdout is parsed
 * line-by-line: each {@code "  * "}/{@code "  - "} bullet's first token is taken
 * as the model id (dropping a trailing {@code " (default)"} marker or any other
 * trailing description text), everything else (banner lines, headers, blank
 * lines) is discarded. Any failure (grok not installed, not logged in,
 * unexpected future format change) is swallowed and the caller keeps using the
 * hardcoded {@link GrokPluginSettings#KNOWN_MODELS} fallback list. Mirrors
 * {@code GithubCopilotModelDiscovery}'s discover-once-per-IDE-run / cache /
 * broadcast shape.
 */
public final class GrokModelDiscovery {

    private static final Logger LOG = Logger.getLogger(GrokModelDiscovery.class.getName());
    private static final long TIMEOUT_SECONDS = 15;
    // A model id is a bare token like "grok-4.5" or "grok-composer-2.5-fast"
    // — alphanumerics, dots, underscores, hyphens — and always contains at
    // least one hyphen (distinguishing it from stray words in banner/header
    // lines like "Default" or "Available").
    private static final Pattern MODEL_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*$");

    private static final int MAX_RETRIES = 20;
    private static final AtomicBoolean inProgress = new AtomicBoolean(false);
    private static volatile int retryCount = 0;

    /**
     * Starts a fresh discovery cycle: resets the retry counter and submits a
     * background fetch. Does nothing if a discovery is already in progress.
     * On failure, retries up to {@value #MAX_RETRIES} times within the cycle.
     *
     * @param executablePath the located grok CLI path, or null to use PATH
     */
    public static void discoverAsync(String executablePath, Consumer<List<String>> onResult) {
        retryCount = 0;
        tryDiscover(executablePath, onResult);
    }

    private static void tryDiscover(String executablePath, Consumer<List<String>> onResult) {
        if (!inProgress.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            boolean shouldRetry = false;
            try {
                List<String> models = discover(executablePath);
                if (models != null && !models.isEmpty()) {
                    retryCount = 0;
                    LOG.log(Level.INFO, "Grok model discovery succeeded ({0} models)", models.size());
                    if (onResult != null) {
                        onResult.accept(models);
                    }
                }
                else {
                    int attempt = ++retryCount;
                    if (attempt < MAX_RETRIES) {
                        LOG.log(Level.INFO, "Grok model discovery: no models returned (attempt {0}/{1}), retrying",
                                new Object[]{attempt, MAX_RETRIES});
                        shouldRetry = true;
                    }
                    else {
                        LOG.log(Level.INFO, "Grok model discovery: using static fallback model list after {0} attempts", attempt);
                    }
                }
            }
            catch (Exception e) {
                int attempt = ++retryCount;
                if (attempt < MAX_RETRIES) {
                    LOG.log(Level.INFO, "Grok model discovery failed (attempt {0}/{1}), retrying: {2}",
                            new Object[]{attempt, MAX_RETRIES, e.getMessage()});
                    shouldRetry = true;
                }
                else {
                    LOG.log(Level.INFO, "Grok model discovery failed after {0} attempts; using static fallback model list", attempt);
                }
            }
            finally {
                inProgress.set(false);
            }
            if (shouldRetry) {
                tryDiscover(executablePath, onResult);
            }
        }, "grok-model-discovery");
        t.setDaemon(true);
        t.start();
    }

    private static List<String> discover(String executablePath) throws Exception {
        String path = (executablePath != null && !executablePath.isBlank()) ? executablePath : "grok";
        List<String> cmd = GrokExecutableLocator.buildHostCommand(path, "models");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return List.of();
        }
        if (p.exitValue() != 0) {
            return List.of();
        }
        return parseModelIds(lines);
    }

    /**
     * Extracts bare model ids from raw {@code grok models} stdout lines.
     * Package-private (not private) so it can be unit tested directly.
     */
    static List<String> parseModelIds(List<String> lines) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.strip();
            // Strip common list-item prefixes ("- ", "* ", "• ") before
            // validating the remainder as a bare id.
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("\u2022 ")) {
                trimmed = trimmed.substring(2).strip();
            }
            // Some CLIs print "id  description..." on one line; only take the
            // first whitespace-delimited token as the candidate id.
            int sp = trimmed.indexOf(' ');
            String candidate = sp >= 0 ? trimmed.substring(0, sp) : trimmed;
            if (!candidate.isEmpty() && MODEL_ID_PATTERN.matcher(candidate).matches() && candidate.contains("-")) {
                out.add(candidate);
            }
        }
        return new ArrayList<>(out);
    }

    private GrokModelDiscovery() {
    }
}
