package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolResponseKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class WebRequestTool implements McpToolInterface {

    private static final Set<String> ALLOWED_METHODS = new LinkedHashSet<>(Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
    /**
     * Redirects are followed manually so every hop — not just the first URL — passes {@link #validateDestination}.
     * Auto-follow would hit loopback / private / link-local targets before any check could run.
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final int MAX_REDIRECTS = 5;

    private static URI parseUri(String url) throws McpArgumentException {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                throw new McpArgumentException(-32602, "url must include http:// or https://");
            }
            String normalised = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(normalised) && !"https".equals(normalised)) {
                throw new McpArgumentException(-32602, "Only http:// and https:// URLs are supported");
            }
            return uri;
        }
        catch (URISyntaxException e) {
            throw new McpArgumentException(-32602, "Invalid URL: " + e.getMessage());
        }
    }

    /**
     * Default policy: nothing local or private is permitted. Retained so callers and tests that have no session still
     * assert the shipped default.
     */
    static void validateDestination(URI uri) throws McpArgumentException {
        validateDestination(uri, EnumSet.noneOf(WebRequestAccessOptionEnum.class));
    }

    /**
     * Refuses {@code uri} when any address it resolves to falls in a blocked category that {@code permitted} does not
     * unlock. Checked against EVERY resolved address, so a name with several A records cannot hide a blocked address
     * behind a public one.
     */
    static void validateDestination(URI uri, Set<WebRequestAccessOptionEnum> permitted)
            throws McpArgumentException {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new McpArgumentException(-32602, "URL must include a host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        }
        catch (UnknownHostException e) {
            throw new McpArgumentException(-32602, "Cannot resolve host: " + host);
        }
        if (addresses.length == 0) {
            throw new McpArgumentException(-32602, "Cannot resolve host: " + host);
        }
        for (InetAddress address : addresses) {
            BlockedAddressCategoryEnum category = BlockedAddressCategoryEnum.classify(address);
            if (category == null) {
                continue;
            }
            WebRequestAccessOptionEnum option = category.governingOption();
            if (option != null && permitted.contains(option)) {
                continue;
            }
            throw new McpArgumentException(-32602, refusalMessage(category, host, address));
        }
    }

    private static String refusalMessage(BlockedAddressCategoryEnum category, String host,
            InetAddress address) {
        StringBuilder message = new StringBuilder(category.label())
                .append(" refused: ").append(host).append(" resolves to ")
                .append(address.getHostAddress()).append(".");
        WebRequestAccessOptionEnum option = category.governingOption();
        if (option == null) {
            message.append(" No setting permits this address class.");
        }
        else {
            message.append(" Enable \"").append(option.label().displayLabel())
                    .append("\" in this session's settings to permit it.");
        }
        return message.toString();
    }

    static boolean isBlockedAddress(InetAddress address) {
        return BlockedAddressCategoryEnum.classify(address) != null;
    }

    private static String normaliseMethod(String method) throws McpArgumentException {
        String value = method == null || method.isBlank()
                ? "GET"
                : method.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(value)) {
            throw new McpArgumentException(-32602,
                    "Unsupported method '" + value + "'. Allowed: " + String.join(", ", ALLOWED_METHODS));
        }
        return value;
    }

    private static void validateAccess(AbstractAiSession session, String method,
            JsonObject headers, String body) throws McpArgumentException {
        requireAccess(session, WebRequestAccessOptionEnum.forMethod(method));
        if (headers != null && !headers.entrySet().isEmpty()) {
            requireAccess(session, WebRequestAccessOptionEnum.HEADERS);
        }
        if (body != null) {
            requireAccess(session, WebRequestAccessOptionEnum.BODY);
        }
    }

    private static void requireAccess(AbstractAiSession session,
            WebRequestAccessOptionEnum option) throws McpArgumentException {
        if (!session.getSettings().effectiveAllowWebRequestAccess(option)) {
            throw new McpArgumentException(-32602,
                    "'" + option.label().displayLabel() + "' is disabled for this session. "
                    + "Ask the user to enable it in this session's settings or in the "
                    + "plugin's global settings (Tools > Options > AI Coder Code) before "
                    + "retrying.");
        }
    }

    private static void applyHeaders(HttpRequest.Builder builder, JsonObject headers) throws McpArgumentException {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new McpArgumentException(-32602, "Header names must not be blank");
            }
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
                throw new McpArgumentException(-32602,
                        "Header '" + name + "' must have a string value");
            }
            try {
                builder.header(name, value.getAsString());
            }
            catch (IllegalArgumentException e) {
                throw new McpArgumentException(-32602,
                        "Invalid header '" + name + "': " + e.getMessage());
            }
        }
    }

    private static JsonObject formatResponse(URI requestedUri, String method,
            HttpResponse<BoundedBody> response, int maxChars) {
        JsonObject out = new JsonObject();
        out.addProperty(ToolResponseKeyEnum.REQUESTED_URL.key(), requestedUri.toString());
        out.addProperty(ToolResponseKeyEnum.FINAL_URL.key(), response.uri().toString());
        out.addProperty(ToolResponseKeyEnum.METHOD.key(), method);
        out.addProperty(ToolResponseKeyEnum.STATUS.key(), response.statusCode());

        JsonObject headers = new JsonObject();
        response.headers().map().forEach((name, values) -> {
            if (values == null || values.isEmpty()) {
                return;
            }
            if (values.size() == 1) {
                headers.addProperty(name, values.get(0));
                return;
            }
            JsonArray arr = new JsonArray();
            for (String value : values) {
                arr.add(value);
            }
            headers.add(name, arr);
        });
        out.add(ToolResponseKeyEnum.HEADERS.key(), headers);

        BoundedBody bounded = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String decoded = decodeBody(bounded.bytes(), contentType);
        boolean truncated = bounded.truncatedByBytes() || decoded.length() > maxChars;
        out.addProperty(ToolResponseKeyEnum.TRUNCATED.key(), truncated);
        out.addProperty(ToolResponseKeyEnum.BODY.key(),
                decoded.length() > maxChars ? decoded.substring(0, maxChars) : decoded);
        return out;
    }

    private static String decodeBody(byte[] body, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx >= 0) {
            String raw = contentType.substring(idx + 8).trim();
            int semi = raw.indexOf(';');
            if (semi >= 0) {
                raw = raw.substring(0, semi).trim();
            }
            raw = raw.replace("\"", "");
            try {
                charset = Charset.forName(raw);
            }
            catch (Exception ignored) {
                charset = StandardCharsets.UTF_8;
            }
        }
        return new String(body, charset);
    }

    /**
     * Caps bytes read from the response stream so a multi-GB body cannot exhaust the IDE heap before the character cap
     * is applied. Reads at most {@code maxBytes + 1} to detect truncation, then discards the remainder by closing the
     * stream.
     */
    static BoundedBody readBoundedBody(InputStream in, int maxBytes) throws IOException {
        byte[] buf = in.readNBytes(maxBytes + 1);
        if (buf.length > maxBytes) {
            return new BoundedBody(Arrays.copyOf(buf, maxBytes), true);
        }
        return new BoundedBody(buf, false);
    }

    private static HttpResponse.BodyHandler<BoundedBody> boundedBodyHandler(int maxBytes) {
        return responseInfo -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofInputStream(),
                stream -> {
                    try (InputStream in = stream) {
                        return readBoundedBody(in, maxBytes);
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /**
     * Worst-case UTF-8 bytes per character for the byte-read ceiling.
     */
    static int maxBytesForChars(int maxChars) {
        long bytes = (long) maxChars * 4L;
        return bytes > Integer.MAX_VALUE - 1 ? Integer.MAX_VALUE - 1 : (int) bytes;
    }

    /**
     * Sends the request and follows redirects manually, re-validating each destination before the next hop. Mirrors
     * {@link HttpClient.Redirect#NORMAL}: never downgrade https→http; 301/302/303 switch to GET; 307/308 keep method.
     */
    private static HttpResponse<BoundedBody> sendWithRedirects(URI initialUri, String initialMethod,
            JsonObject headers, String body, int timeoutSeconds, int maxBytes,
            Set<WebRequestAccessOptionEnum> permitted)
            throws IOException, InterruptedException, McpArgumentException {
        URI currentUri = initialUri;
        String currentMethod = initialMethod;
        String currentBody = body;
        HttpResponse<BoundedBody> response = null;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validateDestination(currentUri, permitted);
            HttpRequest.Builder builder = HttpRequest.newBuilder(currentUri)
                    .timeout(Duration.ofSeconds(timeoutSeconds));
            applyHeaders(builder, headers);
            applyMethod(builder, currentMethod, currentBody);
            response = HTTP_CLIENT.send(builder.build(), boundedBodyHandler(maxBytes));
            int status = response.statusCode();
            // Only these five are redirects. A 300/304/305/306 can legitimately carry a Location that is NOT a
            // "go here instead" instruction — following one issues a request the caller never asked for, and with
            // a destination option granted that request can reach somewhere the caller never named.
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                return response;
            }
            Optional<String> location = response.headers().firstValue("Location");
            if (location.isEmpty() || location.get().isBlank()) {
                return response;
            }
            URI next;
            try {
                next = currentUri.resolve(location.get().trim());
                next = parseUri(next.toString());
            }
            catch (IllegalArgumentException e) {
                // URI.resolve throws unchecked on a malformed Location, which handle() does not catch — so a
                // remote server could otherwise crash the tool with a header the caller never chose. Report it
                // as a normal failure the assistant can relay instead.
                throw new IOException("Server returned an unusable redirect for " + currentUri
                        + ": " + location.get().trim(), e);
            }
            if ("https".equalsIgnoreCase(currentUri.getScheme())
                    && "http".equalsIgnoreCase(next.getScheme())) {
                return response;
            }
            if (hop == MAX_REDIRECTS) {
                throw new IOException("Too many redirects (max " + MAX_REDIRECTS + ") for " + initialUri);
            }
            if (status == 301 || status == 302 || status == 303) {
                currentMethod = "GET";
                currentBody = null;
            }
            currentUri = next;
        }
        return response;
    }

    private static void applyMethod(HttpRequest.Builder builder, String method, String body) {
        if (body != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        else if ("GET".equals(method)) {
            builder.GET();
        }
        else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
    }

    /**
     * A non-empty description of {@code failure} for a user-facing message.
     * <p>
     * Several of the exceptions this tool sees carry no message at all — a refused TCP connection surfaces as a
     * {@code ConnectException} with a null message, so the naive {@code getMessage()} renders the failure as the bare
     * word "null". That is worse than useless: it tells the user nothing and reads like a bug in the tool rather than a
     * fact about the destination. Falling back to the exception's simple name at least names the failure class. The
     * cause is consulted first because wrappers (an IOException around a ConnectException) are common here and the
     * inner one usually carries the detail.
     */
    private static String describe(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return failure.getClass().getSimpleName();
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SYSTEM;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        return McpToolEnum.WEB_REQUEST.toolName() + " -> fetch an HTTP/HTTPS URL with optional method, headers, body, and timeout";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.WEB_REQUEST.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Fetch HTTP/HTTPS URL with optional method, headers, body, timeout. Blocks loopback, link-local, "
                + "private, CGN (100.64.0.0/10), IPv6 unique-local (fc00::/7), multicast, and any-local addresses "
                + "unless settings allow (refusal names the enabling setting). Follows up to 5 redirects; every "
                + "redirect target is re-checked against the address policy. Response body capped at " + WebRequestParamEnum.MAX_CHARS.key()
                + " (default 20000, max 200000).");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();

        JsonObject url = new JsonObject();
        url.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        url.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The HTTP or HTTPS URL to fetch.");
        props.add(WebRequestParamEnum.URL.key(), url);

        JsonObject method = new JsonObject();
        method.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        method.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "HTTP method. Default: GET.");
        props.add(WebRequestParamEnum.METHOD.key(), method);

        JsonObject headers = new JsonObject();
        headers.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        headers.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Optional request headers as a JSON object of headerName -> value.");
        props.add(WebRequestParamEnum.HEADERS.key(), headers);

        JsonObject body = new JsonObject();
        body.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        body.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Optional request body. Primarily useful with POST, PUT, PATCH, or DELETE.");
        props.add(WebRequestParamEnum.BODY.key(), body);

        JsonObject timeout = new JsonObject();
        timeout.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        timeout.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Request timeout in seconds. Default: "
                + TimeUnit.MILLISECONDS.toSeconds(TimeoutEnum.WEB_REQUEST_DEFAULT_MILLIS.millis()) + ".");
        props.add(WebRequestParamEnum.TIMEOUT_SECONDS.key(), timeout);

        JsonObject maxChars = new JsonObject();
        maxChars.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        maxChars.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Max response characters. Default: 20000, clamped to 1-200000.");
        props.add(WebRequestParamEnum.MAX_CHARS.key(), maxChars);

        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(WebRequestParamEnum.URL.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        if (!session.getSettings().effectiveAllowWebRequests()) {
            throw new McpArgumentException(-32602,
                    "Web requests are disabled for this session. Ask the user to enable "
                    + "'Allow Web Requests' in this session's settings or in the plugin's "
                    + "global settings (Tools > Options > AI Coder Code) before retrying.");
        }
        URI uri = parseUri(args.require(WebRequestParamEnum.URL.key()));
        String method = normaliseMethod(args.str(WebRequestParamEnum.METHOD.key()));
        JsonObject headers = args.object(WebRequestParamEnum.HEADERS.key());
        String body = args.str(WebRequestParamEnum.BODY.key());
        Set<WebRequestAccessOptionEnum> permitted = EnumSet.noneOf(WebRequestAccessOptionEnum.class);
        for (WebRequestAccessOptionEnum option
                : new WebRequestAccessOptionEnum[]{WebRequestAccessOptionEnum.LOCALHOST,
                    WebRequestAccessOptionEnum.PRIVATE_NETWORKS}) {
            if (session.getSettings().effectiveAllowWebRequestAccess(option)) {
                permitted.add(option);
            }
        }
        validateAccess(session, method, headers, body);
        validateDestination(uri, permitted);
        int timeoutSeconds = args.intOr(WebRequestParamEnum.TIMEOUT_SECONDS.key(),
                Math.toIntExact(TimeUnit.MILLISECONDS.toSeconds(TimeoutEnum.WEB_REQUEST_DEFAULT_MILLIS.millis())),
                1, Math.toIntExact(TimeUnit.MILLISECONDS.toSeconds(TimeoutEnum.WEB_REQUEST_MAX_MILLIS.millis())));
        int maxChars = args.intOr(WebRequestParamEnum.MAX_CHARS.key(), 20000, 1, 200000);
        int maxBytes = maxBytesForChars(maxChars);

        try {
            HttpResponse<BoundedBody> response = sendWithRedirects(
                    uri, method, headers, body, timeoutSeconds, maxBytes, permitted);
            return formatResponse(uri, method, response, maxChars).toString();
        }
        catch (McpArgumentException e) {
            throw e;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Request interrupted while fetching " + uri;
        }
        catch (IOException e) {
            return "Request failed for " + uri + ": " + describe(e);
        }
        catch (UncheckedIOException e) {
            return "Request failed for " + uri + ": " + describe(e);
        }
    }

    static final class BoundedBody {

        private final byte[] bytes;
        private final boolean truncatedByBytes;

        BoundedBody(byte[] bytes, boolean truncatedByBytes) {
            this.bytes = bytes;
            this.truncatedByBytes = truncatedByBytes;
        }

        byte[] bytes() {
            return bytes;
        }

        boolean truncatedByBytes() {
            return truncatedByBytes;
        }
    }
}
