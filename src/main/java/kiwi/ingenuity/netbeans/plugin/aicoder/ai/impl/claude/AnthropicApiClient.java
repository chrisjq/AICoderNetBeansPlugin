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
     * The shared rate-limit manager for all Claude API access. Used by
     * ClaudeAiImplementation to defer usage/model refreshes and by Installer to
     * shut the scheduler down on uninstall.
     */
    public static RateLimitManager rateLimitManager() {
        return RATE_LIMIT_MANAGER;
    }

    private static double getUtilization(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return -1;
        }
        JsonObject bucket = root.getAsJsonObject(key);
        if (!bucket.has("utilization") || bucket.get("utilization").isJsonNull()) {
            return -1;
        }
        return bucket.get("utilization").getAsDouble();
    }

    private long defaultRateLimit = 2L * 60L * 1000L; // 2 minutes — fallback when no usable Retry-After is supplied

    private String readOAuthToken() {
        Path creds = Path.of(System.getProperty("user.home"), ".claude", ".credentials.json");
        if (!Files.exists(creds)) {
            return null;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(creds), JsonObject.class);
            if (root == null) {
                return null;
            }
            JsonObject oauth = root.has("claudeAiOauth") ? root.getAsJsonObject("claudeAiOauth") : null;
            if (oauth != null && oauth.has("accessToken")) {
                JsonElement tok = oauth.get("accessToken");
                if (tok.isJsonPrimitive()) {
                    return tok.getAsString();
                }
            }
            LOG.log(Level.WARNING, "OAuth token not found in {0} — run ''claude auth login''", creds);
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read OAuth credentials from " + creds, e);
        }
        return null;
    }

    private String get(String path) throws IOException {
        if (RATE_LIMIT_MANAGER.isRateLimited()) {
            throw new IOException("Rate limited — retry in " + RATE_LIMIT_MANAGER.getRetryAfterMs() + "ms");
        }
        String token = readOAuthToken();
        if (token == null) {
            throw new IOException("No OAuth credentials at ~/.claude/.credentials.json");
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
                return new String(is.readNBytes(MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
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
                return Math.max(1000L, seconds * 1000L);
            }
            long retryTime = parseHttpDate(header);
            return Math.max(1000L, retryTime - System.currentTimeMillis());
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
        JsonArray data = root.has("data") ? root.getAsJsonArray("data") : null;
        List<String> models = new ArrayList<>();
        if (data != null) {
            for (JsonElement el : data) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject m = el.getAsJsonObject();
                if (m.has("id")) {
                    JsonElement id = m.get("id");
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
        double fiveHour = getUtilization(root, "five_hour");
        double sevenDay = getUtilization(root, "seven_day");
        return new UsageData(fiveHour, sevenDay);
    }

    /**
     * Rate limit utilization percentages (0–100, or -1 if unavailable).
     */
    public record UsageData(double fiveHourPct, double sevenDayPct) {

    }
}
