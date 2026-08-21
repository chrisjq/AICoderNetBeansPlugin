package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokTokenUsageEvent;

/**
 * Reads the real per-turn context usage the grok CLI writes to disk after every
 * turn, at: {@code ~/.grok/sessions/<url-encoded-cwd>/<sessionId>/signals.json}
 *
 * <p>
 * This was empirically confirmed against a live installed grok CLI (v0.2.93):
 * grok's {@code --output-format json} stdout never includes usage data
 * (verified with both {@code json} and {@code streaming-json} output formats),
 * and the only place per-turn token counts are exposed short of enabling full
 * {@code --debug} tracing (which is far too verbose/fragile to scrape per turn)
 * is this small, session-scoped {@code signals.json} file, which grok rewrites
 * after each turn with fields including:
 * <pre>
 * {"contextTokensUsed":5606,"contextWindowTokens":500000,"primaryModelId":"grok-4.5",...}
 * </pre>
 *
 * <p>
 * The directory name is the turn's working directory, percent-encoded the same
 * way {@code java.net.URLEncoder} encodes a path (confirmed by comparing real
 * session directories against their source cwds, e.g. {@code /tmp} to
 * {@code %2Ftmp} and {@code /tmp/test dir/sub-proj_1} to
 * {@code %2Ftmp%2Ftest%20dir%2Fsub-proj_1}), except that URLEncoder emits
 * {@code +} for spaces where grok emits {@code %20}, which is corrected for
 * below. Critically, grok resolves its process cwd through the OS (which
 * follows symlinks), so the directory name always reflects the *canonical* path
 * — verified by running grok from a symlinked NetBeans project path
 * ({@code /share/... -> /Users/chris/.SyncShare/...}) and observing the session
 * directory keyed on the resolved {@code /Users/...} form. This reader
 * therefore canonicalizes {@code workDir} the same way (see
 * {@link #resolveCanonicalPath(File)}) rather than just taking its absolute
 * path.
 */
final class GrokUsageSignalsReader {

    private static final Logger LOG = Logger.getLogger(GrokUsageSignalsReader.class.getName());
    private static final Gson GSON = new Gson();

    /**
     * Reads the {@code signals.json} written for the given session/working
     * directory and returns a {@link GrokTokenUsageEvent} built from its null     {@link GrokJsonKeyEnum#CONTEXT_TOKENS_USED}/{@link GrokJsonKeyEnum#CONTEXT_WINDOW_TOKENS}/
     * {@link GrokJsonKeyEnum#PRIMARY_MODEL_ID} fields, or {@code null} if the
     * file is missing, unreadable, or doesn't contain usable data (e.g. right
     * after the CLI has just started and hasn't written the file yet).
     */
    static GrokTokenUsageEvent read(File workDir, String sessionId, String fallbackModel) {
        if (workDir == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Path signalsFile = signalsFilePath(workDir, sessionId);
        if (!Files.isRegularFile(signalsFile)) {
            return null;
        }
        try {
            String json = Files.readString(signalsFile, StandardCharsets.UTF_8);
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) {
                return null;
            }
            int used = getInt(obj, GrokJsonKeyEnum.CONTEXT_TOKENS_USED.key(), -1);
            int max = getInt(obj, GrokJsonKeyEnum.CONTEXT_WINDOW_TOKENS.key(), -1);
            if (used < 0 || max <= 0) {
                return null;
            }
            String modelKey = GrokJsonKeyEnum.PRIMARY_MODEL_ID.key();
            String model = obj.has(modelKey) && !obj.get(modelKey).isJsonNull()
                    ? obj.get(modelKey).getAsString() : fallbackModel;
            return new GrokTokenUsageEvent(used, max, model);
        }
        catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not read grok signals.json at " + signalsFile, e);
            return null;
        }
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsInt() : fallback;
    }

    /**
     * Returns true if a grok session directory named {@code sessionId} exists
     * anywhere under {@code ~/.grok/sessions/} (grok sessions are scattered
     * across per-working-directory subdirectories, so this scans one level deep
     * rather than assuming a single fixed cwd). Used by
     * {@code GrokAiImplementation.isStoredSessionValid} to decide whether a
     * reopened plugin session should resume ({@code -r}) an existing grok
     * session or create ({@code -s}) a new one — reusing {@code -s} with an id
     * that already exists is rejected by the CLI (empirically confirmed:
     * {@code Error: Session ID <id> is already in use.}).
     */
    static boolean sessionExists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Path sessionsRoot = Path.of(System.getProperty("user.home"), ".grok", "sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return false;
        }
        try (Stream<Path> cwdDirs = Files.list(sessionsRoot)) {
            return cwdDirs.filter(Files::isDirectory)
                    .anyMatch(dir -> Files.isDirectory(dir.resolve(sessionId)));
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not scan grok sessions directory " + sessionsRoot, e);
            return false;
        }
    }

    private static Path signalsFilePath(File workDir, String sessionId) {
        String home = System.getProperty("user.home");
        String encodedCwd = encodeCwd(resolveCanonicalPath(workDir));
        return Path.of(home, ".grok", "sessions", encodedCwd, sessionId, "signals.json");
    }

    /**
     * grok resolves its own process cwd via the OS (which follows symlinks), so
     * it names session directories after the canonical path — not necessarily
     * the path the plugin was originally given. E.g. if a NetBeans project
     * lives under a symlinked mount
     * ({@code /share/... -> /Users/chris/.SyncShare/...}), grok's session
     * directory is keyed on the {@code /Users/...} form, not {@code /share/...}
     * (empirically confirmed). {@link File#getAbsolutePath()} does not resolve
     * symlinks, so {@link File#getCanonicalPath()} must be used here to match;
     * falling back to the absolute path only if canonicalization fails (e.g. a
     * since-deleted directory).
     */
    private static String resolveCanonicalPath(File workDir) {
        try {
            return workDir.getCanonicalPath();
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not canonicalize " + workDir + "; using absolute path", e);
            return workDir.getAbsolutePath();
        }
    }

    private static String encodeCwd(String cwd) {
        return URLEncoder.encode(cwd, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private GrokUsageSignalsReader() {
    }
}
