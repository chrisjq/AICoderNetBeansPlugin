package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.limits.RateLimitManager;

public class AnthropicApiClient {

    private static final Logger LOG = Logger.getLogger(AnthropicApiClient.class.getName());
    private static final Gson GSON = new Gson();
    private static final String API_BASE = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024; // 1 MB
    static final RateLimitManager RATE_LIMIT_MANAGER = new RateLimitManager();

    /**
     * True on macOS, where the Claude CLI stores its OAuth credentials in the
     * login Keychain (a generic password, service "Claude Code-credentials")
     * rather than the ~/.claude/.credentials.json file used on Linux/Windows.
     */
    private static final boolean IS_MAC
            = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");

    /**
     * macOS Keychain service names to try, in order. The CLI writes the blob to
     * "Claude Code-credentials"; some versions have historically read it back
     * from "Claude Code" (no suffix), so both are attempted.
     */
    private static final String[] MAC_KEYCHAIN_SERVICES = {"Claude Code-credentials", "Claude Code"};

    /**
     * Last-seen fingerprint of the OAuth credentials — the file's last-modified
     * time on Linux/Windows, or a content hash of the Keychain blob on macOS
     * (there is no file to stat). A change means the user re-authenticated.
     * Seeded lazily on macOS (0) so plugin class-load never triggers a Keychain
     * access prompt; the first poll computes the real value.
     */
    private static volatile long lastCredsFingerprint = IS_MAC ? 0L : fileModifiedMs();

    /**
     * The shared rate-limit manager for all Claude API access. Used by
     * ClaudeAiImplementation to defer usage/model refreshes and by Installer to
     * shut the scheduler down on uninstall.
     */
    public static RateLimitManager rateLimitManager() {
        return RATE_LIMIT_MANAGER;
    }

    private static Path credentialsPath() {
        return Path.of(System.getProperty("user.home"), ".claude", ".credentials.json");
    }

    private static long fileModifiedMs() {
        try {
            Path creds = credentialsPath();
            return Files.exists(creds) ? Files.getLastModifiedTime(creds).toMillis() : 0L;
        }
        catch (Exception e) {
            return 0L;
        }
    }

    /**
     * A cheap value that changes whenever the stored credentials change. On
     * macOS this hashes the Keychain JSON; elsewhere it is the credentials
     * file's last-modified time. Falls back to the file on macOS when the
     * Keychain is unreadable (e.g. creds exported to disk for headless/SSH
     * use).
     */
    private static long credentialsFingerprint() {
        if (IS_MAC) {
            String json = readMacKeychainJson();
            if (json != null && !json.isBlank()) {
                return json.hashCode() & 0xffffffffL;
            }
        }
        return fileModifiedMs();
    }

    /**
     * Reads the raw Claude credentials JSON blob, or null if unavailable. On
     * macOS the login Keychain is tried first, then the file as a fallback; on
     * other platforms only the file is read.
     */
    private static String readCredentialsJson() {
        if (IS_MAC) {
            String keychain = readMacKeychainJson();
            if (keychain != null && !keychain.isBlank()) {
                return keychain;
            }
        }
        Path creds = credentialsPath();
        try {
            return Files.exists(creds) ? Files.readString(creds) : null;
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read credentials file " + creds, e);
            return null;
        }
    }

    /**
     * Returns the Claude OAuth JSON from the macOS login Keychain via the
     * {@code security} tool, trying each known service name, or null if not
     * found or access was denied (e.g. a headless / SSH session with no GUI
     * Keychain authorization). Never blocks longer than a few seconds.
     */
    private static String readMacKeychainJson() {
        for (String service : MAC_KEYCHAIN_SERVICES) {
            String value = runSecurityFindGenericPassword(service);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String runSecurityFindGenericPassword(String service) {
        Process p = null;
        try {
            p = new ProcessBuilder("security", "find-generic-password", "-s", service, "-w")
                    .redirectErrorStream(false)
                    .start();
            byte[] out;
            try (InputStream is = p.getInputStream()) {
                out = is.readNBytes(MAX_RESPONSE_BYTES);
            }
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                // Non-zero: no item for this service name, or access denied.
                return null;
            }
            return new String(out, StandardCharsets.UTF_8).trim();
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "Keychain read failed for service " + service, e);
            if (p != null) {
                p.destroyForcibly();
            }
            return null;
        }
    }

    /**
     * Clears any active rate limit if the OAuth credentials file has changed
     * since we last looked — i.e. the user re-authenticated (ran
     * {@code claude login}) after the plugin started. A rate limit incurred
     * with a missing or expired token must NOT keep blocking a freshly written,
     * valid one: {@link #get} short-circuits on {@code isRateLimited()} before
     * it ever reads the token, so without this a new key would never be picked
     * up until the IDE restarts. Call from usage/model refresh triggers, off
     * the deferred scheduler thread, so the clear happens before the next fetch
     * is submitted.
     *
     * @return true if the credentials file changed since the last check (the
     * caller may want to re-run a usage/model fetch), false otherwise.
     */
    public static boolean refreshCredentialsState() {
        long current = credentialsFingerprint();
        if (current != lastCredsFingerprint) {
            lastCredsFingerprint = current;
            RATE_LIMIT_MANAGER.clearRateLimit();
            return true;
        }
        return false;
    }

    private static double getUtilization(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return -1;
        }
        JsonObject bucket = root.getAsJsonObject(key);
        String utilizationKey = AnthropicApiJsonKeyEnum.UTILIZATION.key();
        if (!bucket.has(utilizationKey) || bucket.get(utilizationKey).isJsonNull()) {
            return -1;
        }
        return bucket.get(utilizationKey).getAsDouble();
    }

    private long defaultRateLimit = 2L * 60L * 1000L; // 2 minutes — fallback when no usable Retry-After is supplied

    private String readOAuthToken() {
        String json = readCredentialsJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) {
                return null;
            }
            String oauthKey = ClaudeJsonKeyEnum.CLAUDE_AI_OAUTH.key();
            String accessTokenKey = ClaudeJsonKeyEnum.ACCESS_TOKEN.key();
            JsonObject oauth = root.has(oauthKey) ? root.getAsJsonObject(oauthKey) : null;
            if (oauth != null && oauth.has(accessTokenKey)) {
                JsonElement tok = oauth.get(accessTokenKey);
                if (tok.isJsonPrimitive()) {
                    return tok.getAsString();
                }
            }
            LOG.log(Level.WARNING, "OAuth token not found in Claude credentials — run ''claude login''");
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Could not parse Claude OAuth credentials", e);
        }
        return null;
    }

    private String get(String path) throws IOException {
        if (RATE_LIMIT_MANAGER.isRateLimited()) {
            throw new IOException("Rate limited — retry in " + RATE_LIMIT_MANAGER.getRetryAfterMs() + "ms");
        }
        String token = readOAuthToken();
        if (token == null) {
            throw new IOException(IS_MAC
                    ? "No Claude OAuth credentials (checked macOS Keychain and ~/.claude/.credentials.json)"
                    : "No OAuth credentials at ~/.claude/.credentials.json");
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("anthropic-version", API_VERSION);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code == 429) {
                long retryAfterMs = parseRetryAfter(conn);
                RATE_LIMIT_MANAGER.setRateLimit(retryAfterMs);
                throw new IOException("API " + path + " returned HTTP 429 - rate limited");
            }
            if (code != 200) {
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        es.readAllBytes();
                    }
                }
                catch (IOException ignored) {
                }
                throw new IOException("API " + path + " returned HTTP " + code);
            }
            try (InputStream is = conn.getInputStream()) {
                String body = new String(is.readNBytes(MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
                // A successful response means we are no longer rate limited.
                RATE_LIMIT_MANAGER.clearRateLimit();
                return body;
            }
        }
        finally {
            conn.disconnect();
        }
    }

    private long parseRetryAfter(HttpURLConnection conn) {
        String retryAfterHeader = conn.getHeaderField("Retry-After");
        if (retryAfterHeader == null || retryAfterHeader.isBlank()) {
            return defaultRateLimit;
        }
        LOG.log(Level.INFO, "Rate limit returned {0}", retryAfterHeader);

        String header = retryAfterHeader.trim();
        try {
            // Retry-After is either an integer number of seconds (the common
            // case) or an HTTP-date. The server's value is authoritative — use
            // it directly, with only a small 1s floor to avoid a busy-retry.
            if (header.matches("\\d+")) {
                long seconds = Long.parseLong(header);
                return Math.max(60000L, seconds * 1000L);
            }
            long retryTime = parseHttpDate(header);
            return Math.max(60000L, retryTime - System.currentTimeMillis());
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse Retry-After header: {0}", retryAfterHeader);
            return defaultRateLimit;
        }
    }

    private long parseHttpDate(String dateStr) {
        try {
            return java.time.ZonedDateTime
                    .parse(dateStr.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse HTTP date: {0}", dateStr);
            return System.currentTimeMillis() + defaultRateLimit;
        }
    }

    public List<String> fetchModels() throws IOException {
        String body = get("/v1/models");
        JsonObject root;
        try {
            root = GSON.fromJson(body, JsonObject.class);
        }
        catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("Failed to parse models response", e);
        }
        if (root == null) {
            return List.of();
        }
        String dataKey = AnthropicApiJsonKeyEnum.DATA.key();
        String modelIdKey = AnthropicApiJsonKeyEnum.MODEL_ID.key();
        JsonArray data = root.has(dataKey) ? root.getAsJsonArray(dataKey) : null;
        List<String> models = new ArrayList<>();
        if (data != null) {
            for (JsonElement el : data) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject m = el.getAsJsonObject();
                if (m.has(modelIdKey)) {
                    JsonElement id = m.get(modelIdKey);
                    if (id.isJsonPrimitive()) {
                        models.add(id.getAsString());
                    }
                }
            }
        }
        return models;
    }

    public void setDefaultRateLimit(long defaultRateLimit) {
        this.defaultRateLimit = defaultRateLimit;
    }

    public long getDefaultRateLimit() {
        return defaultRateLimit;
    }

    public UsageData fetchUsage() throws IOException {
        String body = get("/api/oauth/usage");
        JsonObject root;
        try {
            root = GSON.fromJson(body, JsonObject.class);
        }
        catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("Failed to parse usage response", e);
        }
        if (root == null) {
            return new UsageData(-1, -1);
        }
        double fiveHour = getUtilization(root, AnthropicApiJsonKeyEnum.FIVE_HOUR.key());
        double sevenDay = getUtilization(root, AnthropicApiJsonKeyEnum.SEVEN_DAY.key());
        return new UsageData(fiveHour, sevenDay);
    }

    /**
     * Rate limit utilization percentages (0–100, or -1 if unavailable).
     */
    public record UsageData(double fiveHourPct, double sevenDayPct) {

    }
}
