package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shortens base64 {@code data:} URIs (the images in a request) to {@code [image <mime> <n> bytes]} before text is
 * written to the backend debug log, so a log line never carries megabytes of image data.
 */
public final class DataUriElider {

    /**
     * Gson writes {@code =} as an escaped code point by default, so base64 padding in serialised JSON may appear in
     * that six-character form; it is swallowed with the URI.
     */
    private static final String ESCAPED_EQUALS = "\\\\" + "u003d";
    private static final Pattern DATA_URI = Pattern.compile(
            "data:([A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=]+)(?:" + ESCAPED_EQUALS + ")*");

    private DataUriElider() {
    }

    /**
     * {@code text} with every base64 data URI replaced; unchanged (the same instance) when it has none.
     */
    public static String elide(String text) {
        if (text == null || !text.contains("data:")) {
            return text;
        }
        Matcher matcher = DATA_URI.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        while (matcher.find()) {
            if (out == null) {
                out = new StringBuilder();
            }
            out.append(text, copied, matcher.start());
            out.append("[image ").append(matcher.group(1)).append(' ')
                    .append(decodedBytes(matcher.end(2) - matcher.start(2), text, matcher.end(2)))
                    .append(" bytes]");
            copied = matcher.end();
        }
        if (out == null) {
            return text;
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    /**
     * The decoded size of a base64 run of {@code length} characters ending at {@code end}: three bytes per four
     * characters, less one per trailing {@code =}.
     */
    private static long decodedBytes(int length, String text, int end) {
        int padding = 0;
        for (int i = end - 1; i >= end - length && i >= end - 2 && text.charAt(i) == '='; i--) {
            padding++;
        }
        return Math.max(0L, (long) length * 3 / 4 - padding);
    }
}
