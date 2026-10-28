package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.List;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

/**
 * Status codes, OpenAI error codes and message-text patterns for server errors (spec §4.1). Message templates use
 * {@code <URL>}, {@code <name>}, {@code <host>} and a literal {@code N} placeholder.
 */
public enum OpenAiServerErrorEnum {
    KEY_REJECTED(
            MappedError.Kind.KEY_REJECTED,
            List.of(401, 403),
            null,
            TextMatch.NONE,
            List.of(),
            "The server rejected the API key. Check the API key in session settings.",
            List.of()),
    NO_KEY_SENT(
            MappedError.Kind.NO_KEY_SENT,
            List.of(401, 403),
            null,
            TextMatch.NONE,
            List.of(),
            "No API key was sent — set a session key, or use a default key for this server.",
            List.of()),
    KEY_UNAVAILABLE(
            MappedError.Kind.NO_KEY_SENT,
            List.of(401, 403),
            null,
            TextMatch.NONE,
            List.of(),
            "The API key could not be read from the system keyring (it may be locked).",
            List.of()),
    KEY_REQUIRED(
            MappedError.Kind.KEY_REQUIRED,
            List.of(401, 403),
            null,
            TextMatch.NONE,
            List.of(),
            "This server requires an API key — enter it in session settings.",
            List.of()),
    PATH_NOT_FOUND(
            MappedError.Kind.PATH_NOT_FOUND,
            List.of(404),
            null,
            TextMatch.NONE,
            List.of(),
            "The server has no /v1/chat/completions endpoint at <URL> — check the base URL.",
            List.of()),
    MODEL_NOT_FOUND(
            MappedError.Kind.MODEL_NOT_FOUND,
            List.of(400, 404),
            "model_not_found",
            TextMatch.QUOTED_NAME,
            List.of("model '…' not found"),
            "Model <name> is not available on the server — for Ollama run `ollama pull <name>`, "
            + "or load the model in LM Studio.",
            List.of()),
    RATE_LIMITED(
            MappedError.Kind.RATE_LIMITED,
            List.of(429),
            null,
            TextMatch.NONE,
            List.of(),
            "The server is rate-limiting requests (retrying in N s).",
            List.of()),
    QUOTA(
            MappedError.Kind.QUOTA,
            List.of(402),
            "insufficient_quota",
            TextMatch.NONE,
            List.of(),
            "Quota or billing issue: ",
            List.of()),
    REJECTED_FIELD(
            MappedError.Kind.REJECTED_FIELD,
            List.of(400, 422),
            null,
            TextMatch.WHOLE_WORD,
            List.of("stream_options", "reasoning_effort", "response_format", "max_tokens"),
            null,
            List.of()),
    UNNAMED_REJECTION(
            MappedError.Kind.UNNAMED_REJECTION,
            List.of(400, 422),
            null,
            TextMatch.NONE,
            List.of(),
            null,
            List.of()),
    TOOLS_UNSUPPORTED(
            MappedError.Kind.TOOLS_UNSUPPORTED,
            List.of(),
            null,
            TextMatch.SUBSTRING,
            List.of(
                    "does not support tools",
                    "tools param requires --jinja flag",
                    "Unsupported param: tools"),
            null,
            List.of()),
    CONTEXT_OVERFLOW(
            MappedError.Kind.CONTEXT_OVERFLOW,
            List.of(413),
            "exceed_context_size_error",
            TextMatch.TRAILING_NUMBER,
            List.of("maximum context length is N"),
            null,
            List.of()),
    SERVER_ERROR(
            MappedError.Kind.SERVER_ERROR,
            List.of(),
            null,
            TextMatch.NONE,
            List.of(),
            null,
            List.of()),
    TLS(
            MappedError.Kind.TLS,
            List.of(),
            null,
            TextMatch.NONE,
            List.of(),
            "The server's certificate is not trusted (<host>). Check the URL, or add the "
            + "certificate to Java's trust store.",
            List.of(SSLHandshakeException.class, SSLException.class)),
    CONNECTION_FAILED(
            MappedError.Kind.CONNECTION_FAILED,
            List.of(),
            null,
            TextMatch.NONE,
            List.of(),
            "Cannot reach the server at <URL> — is it running?",
            List.of(ConnectException.class, UnknownHostException.class, HttpConnectTimeoutException.class));

    /**
     * Server-derived messages are trimmed to this many characters (spec §4.1, other 5xx).
     */
    public static final int SERVER_MESSAGE_LIMIT = 500;

    private final MappedError.Kind kind;
    private final List<Integer> statuses;
    private final String errorCode;
    private final TextMatch textMatch;
    private final List<String> textPatterns;
    private final String messageTemplate;
    private final List<Class<? extends Throwable>> exceptionTypes;

    OpenAiServerErrorEnum(MappedError.Kind kind, List<Integer> statuses, String errorCode, TextMatch textMatch,
                          List<String> textPatterns, String messageTemplate, List<Class<? extends Throwable>> exceptionTypes) {
        this.kind = kind;
        this.statuses = statuses;
        this.errorCode = errorCode;
        this.textMatch = textMatch;
        this.textPatterns = textPatterns;
        this.messageTemplate = messageTemplate;
        this.exceptionTypes = exceptionTypes;
    }

    public MappedError.Kind kind() {
        return kind;
    }

    public String errorCode() {
        return errorCode;
    }

    public String messageTemplate() {
        return messageTemplate;
    }

    public List<Class<? extends Throwable>> exceptionTypes() {
        return exceptionTypes;
    }

    public boolean matchesStatus(int status) {
        return statuses.contains(status);
    }

    public boolean matchesText(String body) {
        if (body == null || textMatch == TextMatch.NONE) {
            return false;
        }
        for (String pattern : textPatterns) {
            if (textMatch == TextMatch.SUBSTRING && body.contains(pattern)) {
                return true;
            }
            if (textMatch == TextMatch.WHOLE_WORD && indexOfWholeWord(body, pattern) >= 0) {
                return true;
            }
            if (textMatch == TextMatch.QUOTED_NAME && quotedName(body, pattern) != null) {
                return true;
            }
            if (textMatch == TextMatch.TRAILING_NUMBER && trailingNumber(body, pattern) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the named field that occurs first in {@code body}, or null
     */
    public String earliestWholeWord(String body) {
        if (body == null || textMatch != TextMatch.WHOLE_WORD) {
            return null;
        }
        String found = null;
        int at = Integer.MAX_VALUE;
        for (String pattern : textPatterns) {
            int index = indexOfWholeWord(body, pattern);
            if (index >= 0 && index < at) {
                at = index;
                found = pattern;
            }
        }
        return found;
    }

    /**
     * @return the name inside {@code model '…' not found}, or an empty string
     */
    public String capturedName(String body) {
        if (body == null || textMatch != TextMatch.QUOTED_NAME) {
            return "";
        }
        for (String pattern : textPatterns) {
            String name = quotedName(body, pattern);
            if (name != null) {
                return name;
            }
        }
        return "";
    }

    /**
     * @return the number in {@code maximum context length is N}, or null
     */
    public Integer capturedNumber(String body) {
        if (body == null || textMatch != TextMatch.TRAILING_NUMBER) {
            return null;
        }
        for (String pattern : textPatterns) {
            Integer number = trailingNumber(body, pattern);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    private static int indexOfWholeWord(String body, String word) {
        int from = 0;
        while (from <= body.length() - word.length()) {
            int at = body.indexOf(word, from);
            if (at < 0) {
                return -1;
            }
            boolean left = at == 0 || !isWord(body.charAt(at - 1));
            int after = at + word.length();
            boolean right = after == body.length() || !isWord(body.charAt(after));
            if (left && right) {
                return at;
            }
            from = at + 1;
        }
        return -1;
    }

    private static boolean isWord(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static String quotedName(String body, String pattern) {
        int wild = pattern.indexOf('…');
        if (wild < 0) {
            return body.contains(pattern) ? "" : null;
        }
        String prefix = pattern.substring(0, wild);
        String suffix = pattern.substring(wild + 1);
        int start = 0;
        while (start < body.length()) {
            int at = body.indexOf(prefix, start);
            if (at < 0) {
                return null;
            }
            int after = at + prefix.length();
            int end = body.indexOf(suffix, after);
            if (end >= 0 && body.substring(after, end).indexOf('\'') < 0) {
                return body.substring(after, end);
            }
            start = at + 1;
        }
        return null;
    }

    private static Integer trailingNumber(String body, String pattern) {
        int marker = pattern.lastIndexOf('N');
        if (marker < 0) {
            return null;
        }
        String prefix = pattern.substring(0, marker);
        int at = body.indexOf(prefix);
        if (at < 0) {
            return null;
        }
        int i = at + prefix.length();
        if (i >= body.length() || !Character.isDigit(body.charAt(i))) {
            return null;
        }
        int end = i;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        try {
            return Integer.valueOf(body.substring(i, end));
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private enum TextMatch {
        NONE,
        SUBSTRING,
        WHOLE_WORD,
        QUOTED_NAME,
        TRAILING_NUMBER
    }
}
