package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

/**
 * Pure + impure helper for steering a running OpenCode process via its local HTTP API.
 * <p>
 * The {@code static} methods ({@link #docDeclaresSteerRoute}, {@link #buildSteerBody},
 * {@link #steerUrl}) are side-effect-free and can be unit-tested without a network. The instance methods
 * ({@link #probeSteerCapability}, {@link #sendSteer}) issue real HTTP requests against {@code 127.0.0.1} and must be
 * called from a background thread — never from the EDT.
 * <p>
 * All HTTP requests use a bounded timeout from {@link OpenCodeTimeoutEnum#STEER_REQUEST_MILLIS}.
 */
public final class OpenCodeSteerClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Regex matching a single path segment that looks like a brace-enclosed OpenAPI parameter, e.g.
     * {@code {sessionID}}, {@code {session_id}}, {@code {id}}. We require the first and last characters to be braces;
     * everything inside is accepted to avoid chasing every possible OpenAPI parameter naming convention.
     */
    private static final Pattern PATH_TEMPLATE_SEGMENT
            = Pattern.compile("\\{[^}]+\\}");

    /**
     * Session ids we are willing to put into a URL path. Real ACP ids look like {@code ses_fcd33f985ffe1EItKyU10dnjaf};
     * anything else is refused rather than escaped. See {@link #steerUrl}.
     */
    private static final Pattern SAFE_SESSION_ID
            = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Username opencode's basic auth expects by default. Left at the default rather than overriding it via
     * OPENCODE_SERVER_USERNAME: the password is what carries the entropy, and one fewer environment variable is one
     * fewer thing to keep in step between the spawn and the client.
     */
    private static final String SERVER_USERNAME = "opencode";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String JSON_CONTENT_TYPE = "application/json";
    /**
     * 32 bytes — the secret only has to resist guessing by another local process within one session's lifetime, and
     * this is well past that while staying short enough to pass comfortably as an environment variable.
     */
    private static final int SECRET_BYTES = 32;
    /**
     * Cap on how much of a refused steer's response body reaches the log — enough for a JSON error object without
     * letting an unexpected page flood a log shared by every session.
     */
    private static final int LOG_BODY_MAX_CHARS = 500;
    private static final Logger LOG = Logger.getLogger(OpenCodeSteerClient.class.getName());
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── pure helpers ──────────────────────────────────────────────────
    /**
     * Pick a free TCP port by binding to port {@code 0} and reading the OS-assigned port.
     *
     * <p>
     * <b>TOCTOU caveat:</b> The returned port may be claimed by another process between this call and the caller's
     * subsequent bind (e.g. spawning the OpenCode process). The caller must handle a spawn failure gracefully; this
     * method is a best-effort hint, not a guarantee.
     *
     * @return a free port number in the ephemeral range
     * @throws IOException if no port could be allocated
     */
    public static int pickFreePort() throws IOException {
        // No setReuseAddress here: it only has any effect when called BEFORE bind, and
        // new ServerSocket(0) has already bound by the time we hold the reference.
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    /**
     * Check whether a server's OpenAPI {@code /doc} JSON declares the steer route.
     * <p>
     * The path key is templated (e.g. {@code /api/session/{sessionID}/prompt}). This method does not assume the exact
     * brace spelling — it accepts any path whose segments are {@code api}, {@code session}, {@code {…}}, {@code prompt}
     * in order.
     *
     * @param docJson the raw JSON body from {@code GET /doc}
     * @return {@code true} if the route is declared, {@code false} otherwise or on error
     */
    public static boolean docDeclaresSteerRoute(String docJson) {
        if (docJson == null || docJson.isBlank()) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(docJson).getAsJsonObject();
            JsonObject paths = root.getAsJsonObject("paths");
            if (paths == null) {
                return false;
            }
            for (String pathKey : paths.keySet()) {
                if (matchesSteerPathTemplate(pathKey)) {
                    return true;
                }
            }
        }
        catch (Exception ignored) {
            return false;
        }
        return false;
    }

    /**
     * Build the JSON body for a {@code POST /api/session/{sessionID}/prompt} steer request.
     * <p>
     * Uses Gson for correct escaping — the mail body contains arbitrary user/AI text with quotes, newlines,
     * backslashes, and non-ASCII characters.
     *
     * @param text the steering text
     * @return the JSON body string
     */
    public static String buildSteerBody(String text) {
        JsonObject prompt = new JsonObject();
        prompt.addProperty(OpenCodeSteerJsonKeyEnum.TEXT.key(), text);

        JsonObject body = new JsonObject();
        body.add(OpenCodeSteerJsonKeyEnum.PROMPT.key(), prompt);
        body.addProperty(OpenCodeSteerJsonKeyEnum.DELIVERY.key(),
                OpenCodeSteerJsonKeyEnum.STEER.key());

        return body.toString();
    }

    /**
     * Build the URL for a steer POST.
     *
     * <p>
     * Validates rather than escapes. {@code URLEncoder} is FORM encoding, not path encoding — it turns a space into
     * {@code +}, which in a path segment is a literal plus, so escaping here would quietly send the wrong id. Real ACP
     * ids are {@code ses_} followed by alphanumerics, so anything outside that character set means the caller has a
     * bug, and this endpoint is unauthenticated: failing closed beats guessing at an encoding. Rejecting also removes
     * any question of a crafted id walking out of the path with {@code ../}.
     *
     * @param port the local HTTP server port
     * @param acpSessionId the ACP session id (already known to the plugin)
     * @return the full URL string
     * @throws IllegalArgumentException if the session id is not a plain identifier
     */
    public static String steerUrl(int port, String acpSessionId) {
        if (acpSessionId == null || !SAFE_SESSION_ID.matcher(acpSessionId).matches()) {
            throw new IllegalArgumentException(
                    "Refusing to build a steer URL for a session id outside [A-Za-z0-9._-]: " + acpSessionId);
        }
        return "http://127.0.0.1:" + port
                + "/api/session/" + acpSessionId + "/prompt";
    }

    /**
     * Generates the per-process shared secret handed to one spawned agent as {@code OPENCODE_SERVER_PASSWORD}.
     *
     * <p>
     * A distinct value per process is the point. opencode keeps sessions in a SHARED SQLite store, so any agent's HTTP
     * server will happily resolve a session id created by a different one — verified live: a second process loaded and
     * served the first's session. Without credentials, a request that reached the wrong server would be honoured, which
     * for a steer means inter-AI mail landing in another agent's running turn. Per-process credentials make that a 401
     * before the store is ever consulted.
     *
     * <p>
     * It also closes a hole that exists regardless of this feature: the agent's API is otherwise unauthenticated on
     * loopback, so any local process could prompt, steer or abort any of the user's sessions.
     */
    public static String generateServerPassword() {
        byte[] secret = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /**
     * Builds the {@code Basic} credentials for a spawned agent. Username is opencode's documented default; only the
     * password varies per process.
     */
    private static String basicAuth(String password) {
        String raw = SERVER_USERNAME + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // ── internal ──────────────────────────────────────────────────────
    /**
     * Match a path like {@code /api/session/{sessionID}/prompt} by checking that:
     * <ul>
     * <li>it has exactly four non-empty segments after splitting on {@code /}</li>
     * <li>segments 0 and 1 are literal {@code api} and {@code session}</li>
     * <li>segment 2 is a brace-enclosed template parameter</li>
     * <li>segment 3 is literal {@code prompt}</li>
     * </ul>
     */
    static boolean matchesSteerPathTemplate(String path) {
        if (path == null) {
            return false;
        }
        String[] segments = path.split("/", -1);
        // split produces ["", "api", "session", "{…}", "prompt"] for the leading slash
        if (segments.length != 5) {
            return false;
        }
        return "api".equals(segments[1])
                && "session".equals(segments[2])
                && PATH_TEMPLATE_SEGMENT.matcher(segments[3]).matches()
                && "prompt".equals(segments[4]);
    }

    /**
     * Caps a server body before it reaches the log. An error body is normally a short JSON object, but nothing
     * guarantees that and this log is shared with every session's traffic.
     */
    private static String truncateForLog(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= LOG_BODY_MAX_CHARS ? body : body.substring(0, LOG_BODY_MAX_CHARS) + "…";
    }

    OpenCodeSteerClient() {
    }

    // ── impure helpers (must be called from a background thread) ──────
    /**
     * Probe whether the local OpenCode process supports steering.
     * <p>
     * GETs {@code /doc} from {@code 127.0.0.1:{port}} and checks whether the steer route is declared. Returns
     * {@code false} on any exception, non-200 status, or timeout. Never throws.
     *
     * @param port the OpenCode HTTP server port
     * @return {@code true} if the steer route is declared
     */
    public boolean probeSteerCapability(int port, String password) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/doc"))
                    .timeout(Duration.ofMillis(
                            OpenCodeTimeoutEnum.STEER_REQUEST_MILLIS.millis()))
                    .header(AUTHORIZATION_HEADER, basicAuth(password))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return false;
            }
            return docDeclaresSteerRoute(response.body());
        }
        catch (Exception e) {
            return false;
        }
    }

    /**
     * Send a steer message into a running OpenCode turn.
     * <p>
     * POSTs to {@code 127.0.0.1:{port}/api/session/{id}/prompt} with a steer body, asynchronously.
     *
     * <p>
     * <b>The result means DISPATCHED, not delivered.</b> This endpoint does not respond while a turn is in flight, so
     * at the moment a steer matters there is no answer to wait for; whether the agent acted on it is observable only in
     * that session's transcript. Never throws.
     *
     * @param port the OpenCode HTTP server port
     * @param acpSessionId the ACP session id
     * @param text the steering text
     * @param password the per-process server password
     * @return {@code true} if the request was handed to the HTTP client successfully
     */
    public boolean sendSteer(int port, String acpSessionId, String text, String password) {
        try {
            String url = steerUrl(port, acpSessionId);
            String body = buildSteerBody(text);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(
                            OpenCodeTimeoutEnum.STEER_DELIVERY_MILLIS.millis()))
                    .header(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE)
                    .header(AUTHORIZATION_HEADER, basicAuth(password))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            // Dispatch and walk away. This endpoint does not answer while a turn is running — the runner makes it
            // await the run's completion — so waiting would block for the whole turn and then report a "success"
            // long after the moment it mattered. Worse, bounding the wait tightly and giving up CLOSES the
            // connection, which may discard a steer the agent had already taken: the earlier 5 s bound produced
            // exactly that, an HttpTimeoutException logged as a refusal with no way to tell the two apart.
            //
            // The return value therefore means DISPATCHED, not delivered — see the javadoc. The eventual outcome is
            // logged if it ever arrives, purely for diagnosis.
            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .whenComplete((response, error) -> {
                        if (!PluginSettings.isDebugJson()) {
                            return;
                        }
                        if (error != null) {
                            LOG.log(Level.INFO, "OpenCode steer: no response ({0})", error.toString());
                        }
                        else {
                            LOG.log(Level.INFO, "OpenCode steer: agent answered HTTP {0} body={1}",
                                    new Object[]{response.statusCode(), truncateForLog(response.body())});
                        }
                    });
            return true;
        }
        catch (Exception e) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode steer could not be dispatched: {0}", e.toString());
            }
            return false;
        }
    }

}
