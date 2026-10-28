package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Maps HTTP error bodies and transport failures onto {@link MappedError} (spec §4.1).
 */
public final class ErrorMapper {

    private ErrorMapper() {
    }

    /**
     * Whether an API key was attached to the request that failed.
     */
    public enum KeySituation {
        /**
         * A key was sent.
         */
        SENT,
        /**
         * No key was sent because the keyring was unavailable or the entry is missing on this computer.
         */
        NOT_SENT_UNAVAILABLE,
        /**
         * No key is configured for this request.
         */
        NO_KEY_CONFIGURED,
        /**
         * No key was sent because the configured default belongs to another server.
         */
        DEFAULT_KEY_OTHER_SERVER,
        /**
         * No key is configured.
         */
        NONE
    }

    /**
     * Maps an HTTP error. Auth status wins, then model-not-found, quota, rate limit, context overflow, an unsupported
     * tools phrase, a 404 that does not mention the model, a named or unnamed 400/422, then anything else as a server
     * error. The rate-limit message keeps the literal placeholder {@code N} for the client to fill.
     */
    public static MappedError map(int status, String body, KeySituation keySituation, String baseUrl, String model) {
        Parsed parsed = Parsed.read(body);
        String text = parsed.raw;
        if (status == 401 || status == 403) {
            return fixed(keyItem(keySituation), baseUrl, model);
        }
        if (isModelNotFound(status, text, parsed, model)) {
            return fixed(OpenAiServerErrorEnum.MODEL_NOT_FOUND, baseUrl, modelName(model, text));
        }
        if (status == 402 || parsed.hasCode(OpenAiServerErrorEnum.QUOTA.errorCode())) {
            return quota(parsed);
        }
        if (status == 429) {
            return fixed(OpenAiServerErrorEnum.RATE_LIMITED, baseUrl, model);
        }
        if (isOverflow(status, text, parsed)) {
            return overflow(parsed, text);
        }
        if (OpenAiServerErrorEnum.TOOLS_UNSUPPORTED.matchesText(text)) {
            return fromServer(OpenAiServerErrorEnum.TOOLS_UNSUPPORTED, parsed, null, null);
        }
        if (status == 404) {
            return fixed(OpenAiServerErrorEnum.PATH_NOT_FOUND, baseUrl, model);
        }
        if (status == 400 || status == 422) {
            String field = OpenAiServerErrorEnum.REJECTED_FIELD.earliestWholeWord(text);
            if (field != null) {
                return fromServer(OpenAiServerErrorEnum.REJECTED_FIELD, parsed, field, null);
            }
            return fromServer(OpenAiServerErrorEnum.UNNAMED_REJECTION, parsed, null, null);
        }
        return fromServer(OpenAiServerErrorEnum.SERVER_ERROR, parsed, null, null);
    }

    /**
     * Maps a transport failure. Walks the cause chain. {@code SSLHandshakeException} and {@code SSLException} become
     * TLS; {@code ConnectException}, {@code UnknownHostException} and {@code HttpConnectTimeoutException} become
     * connection failed. Anything else is a server error carrying the exception message.
     */
    public static MappedError mapException(Throwable failure, String baseUrl) {
        if (failure != null) {
            for (OpenAiServerErrorEnum item : OpenAiServerErrorEnum.values()) {
                for (Class<? extends Throwable> type : item.exceptionTypes()) {
                    if (causedBy(failure, type)) {
                        return fixed(item, baseUrl, null);
                    }
                }
            }
        }
        String detail = failure == null || failure.getMessage() == null ? "" : failure.getMessage().trim();
        return new MappedError(MappedError.Kind.SERVER_ERROR, cap(detail), null, null);
    }

    private static OpenAiServerErrorEnum keyItem(KeySituation keySituation) {
        if (keySituation == KeySituation.NOT_SENT_UNAVAILABLE) {
            return OpenAiServerErrorEnum.KEY_UNAVAILABLE;
        }
        if (keySituation == KeySituation.NO_KEY_CONFIGURED
                || keySituation == KeySituation.DEFAULT_KEY_OTHER_SERVER) {
            return OpenAiServerErrorEnum.NO_KEY_SENT;
        }
        if (keySituation == KeySituation.SENT) {
            return OpenAiServerErrorEnum.KEY_REJECTED;
        }
        return OpenAiServerErrorEnum.KEY_REQUIRED;
    }

    private static boolean isModelNotFound(int status, String text, Parsed parsed, String model) {
        OpenAiServerErrorEnum item = OpenAiServerErrorEnum.MODEL_NOT_FOUND;
        if (parsed.hasCode(item.errorCode()) || item.matchesText(text)) {
            return true;
        }
        return status == 404 && model != null && !model.isEmpty() && text.contains(model);
    }

    private static boolean isOverflow(int status, String text, Parsed parsed) {
        OpenAiServerErrorEnum item = OpenAiServerErrorEnum.CONTEXT_OVERFLOW;
        return item.matchesStatus(status) || parsed.hasCode(item.errorCode()) || item.matchesText(text);
    }

    private static String modelName(String model, String text) {
        if (model != null && !model.isEmpty()) {
            return model;
        }
        return OpenAiServerErrorEnum.MODEL_NOT_FOUND.capturedName(text);
    }

    private static MappedError fixed(OpenAiServerErrorEnum item, String baseUrl, String model) {
        return new MappedError(item.kind(), fill(item, baseUrl, model), null, null);
    }

    private static MappedError quota(Parsed parsed) {
        String message = OpenAiServerErrorEnum.QUOTA.messageTemplate() + cap(parsed.serverMessage());
        return new MappedError(MappedError.Kind.QUOTA, message, null, null);
    }

    private static MappedError overflow(Parsed parsed, String text) {
        Integer limit = parsed.nCtx != null
                        ? parsed.nCtx
                        : OpenAiServerErrorEnum.CONTEXT_OVERFLOW.capturedNumber(text);
        return fromServer(OpenAiServerErrorEnum.CONTEXT_OVERFLOW, parsed, null, limit);
    }

    private static MappedError fromServer(OpenAiServerErrorEnum item, Parsed parsed, String field, Integer limit) {
        return new MappedError(item.kind(), cap(parsed.serverMessage()), field, limit);
    }

    private static String fill(OpenAiServerErrorEnum item, String baseUrl, String model) {
        String template = item.messageTemplate();
        if (template == null) {
            return "";
        }
        String url = baseUrl == null ? "" : baseUrl;
        String name = model == null ? "" : model;
        return template.replace("<URL>", url).replace("<name>", name).replace("<host>", hostOf(baseUrl));
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(baseUrl.trim());
            if (uri.getHost() != null) {
                return uri.getHost();
            }
        }
        catch (URISyntaxException ex) {
            return baseUrl;
        }
        return baseUrl;
    }

    private static String cap(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() <= OpenAiServerErrorEnum.SERVER_MESSAGE_LIMIT) {
            return message;
        }
        return message.substring(0, OpenAiServerErrorEnum.SERVER_MESSAGE_LIMIT);
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }

    private static final class Parsed {

        private final String raw;
        private final String message;
        private final String code;
        private final String type;
        private final Integer nCtx;

        private Parsed(String raw, String message, String code, String type, Integer nCtx) {
            this.raw = raw;
            this.message = message;
            this.code = code;
            this.type = type;
            this.nCtx = nCtx;
        }

        private static Parsed read(String body) {
            String trimmed = body == null ? "" : body.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
                return new Parsed(trimmed, null, null, null, null);
            }
            try {
                JsonElement parsed = JsonParser.parseString(trimmed);
                if (!parsed.isJsonObject()) {
                    return new Parsed(trimmed, null, null, null, null);
                }
                JsonObject root = parsed.getAsJsonObject();
                JsonElement error = root.get("error");
                if (error != null && error.isJsonObject()) {
                    JsonObject object = error.getAsJsonObject();
                    return new Parsed(trimmed, text(object, "message"), firstText(object, root, "code"),
                                      firstText(object, root, "type"), number(object, "n_ctx"));
                }
                if (error != null && error.isJsonPrimitive() && error.getAsJsonPrimitive().isString()) {
                    return new Parsed(trimmed, error.getAsString(), text(root, "code"), text(root, "type"), null);
                }
                return new Parsed(trimmed, text(root, "message"), text(root, "code"), text(root, "type"), null);
            }
            catch (RuntimeException ex) {
                return new Parsed(trimmed, null, null, null, null);
            }
        }

        private boolean hasCode(String expected) {
            return expected != null && (expected.equals(code) || expected.equals(type));
        }

        private String serverMessage() {
            if (message != null && !message.trim().isEmpty()) {
                return message.trim();
            }
            return raw;
        }
    }

    private static String firstText(JsonObject preferred, JsonObject fallback, String name) {
        String value = text(preferred, name);
        return value != null ? value : text(fallback, name);
    }

    private static String text(JsonObject object, String name) {
        if (object == null || !object.has(name)) {
            return null;
        }
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static Integer number(JsonObject object, String name) {
        if (object == null || !object.has(name)) {
            return null;
        }
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return element.getAsInt();
        }
        catch (RuntimeException ex) {
            return null;
        }
    }
}
