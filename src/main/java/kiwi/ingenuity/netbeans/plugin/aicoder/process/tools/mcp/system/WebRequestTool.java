package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class WebRequestTool implements McpToolInterface {

    private static final Set<String> ALLOWED_METHODS = new LinkedHashSet<>(Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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

    private static JsonObject formatResponse(URI requestedUri, String method, HttpResponse<byte[]> response, int maxChars) {
        JsonObject out = new JsonObject();
        out.addProperty("requestedUrl", requestedUri.toString());
        out.addProperty("finalUrl", response.uri().toString());
        out.addProperty("method", method);
        out.addProperty("status", response.statusCode());

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
        out.add("headers", headers);

        String decoded = decodeBody(response);
        boolean truncated = decoded.length() > maxChars;
        out.addProperty("truncated", truncated);
        out.addProperty("body", truncated ? decoded.substring(0, maxChars) : decoded);
        return out;
    }

    private static String decodeBody(HttpResponse<byte[]> response) {
        Charset charset = StandardCharsets.UTF_8;
        String contentType = response.headers().firstValue("Content-Type").orElse("");
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
        return new String(response.body(), charset);
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SYSTEM;
    }

    @Override
    public String instruction() {
        return "WebRequest -> fetch an HTTP/HTTPS URL with optional method, headers, body, and timeout";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.WEB_REQUEST.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Fetches an HTTP or HTTPS URL with optional method, headers, request body, timeout, "
                + "and response truncation. Supports GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS.");
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
        timeout.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Request timeout in seconds. Default: 30.");
        props.add(WebRequestParamEnum.TIMEOUT_SECONDS.key(), timeout);

        JsonObject maxChars = new JsonObject();
        maxChars.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        maxChars.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Maximum number of response-body characters to return. Default: 20000.");
        props.add(WebRequestParamEnum.MAX_CHARS.key(), maxChars);

        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(WebRequestParamEnum.URL.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
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
        validateAccess(session, method, headers, body);
        int timeoutSeconds = args.intOr(WebRequestParamEnum.TIMEOUT_SECONDS.key(), 30, 1, 300);
        int maxChars = args.intOr(WebRequestParamEnum.MAX_CHARS.key(), 20000, 1, 200000);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds));
        applyHeaders(builder, headers);

        if (body != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        else if ("GET".equals(method)) {
            builder.GET();
        }
        else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        try {
            HttpResponse<byte[]> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return formatResponse(uri, method, response, maxChars).toString();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Request interrupted while fetching " + uri;
        }
        catch (IOException e) {
            return "Request failed for " + uri + ": " + e.getMessage();
        }
    }
}
