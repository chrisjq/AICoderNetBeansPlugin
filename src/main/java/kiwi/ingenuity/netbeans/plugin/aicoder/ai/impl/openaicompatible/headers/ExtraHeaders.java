package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Rules for user-supplied extra request headers (spec §8.6). Names are matched case-insensitively.
 */
public final class ExtraHeaders {

    public record ExtraHeader(String name, String value) {

    }

    private static final Set<String> RESTRICTED = Set.of("host", "connection", "content-length", "expect", "upgrade");
    private static final Set<String> NOT_OVERRIDABLE = Set.of("content-type", "accept");
    private static final Set<String> SECRET_NAMES = Set.of("authorization", "proxy-authorization", "cookie", "api-key");
    private static final List<String> SECRET_WORDS = List.of("key", "token", "secret", "password", "session", "signature");

    private ExtraHeaders() {
    }

    /**
     * The error text for a header the user may not set, or empty when the header is allowed.
     */
    public static Optional<String> validate(String name, boolean apiKeySet) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (RESTRICTED.contains(lower) || NOT_OVERRIDABLE.contains(lower)) {
            return Optional.of(name + " is set by the client and cannot be overridden.");
        }
        if ("authorization".equals(lower) && apiKeySet) {
            return Optional.of("Use the API key field instead.");
        }
        return Optional.empty();
    }

    public static boolean isSecret(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (SECRET_NAMES.contains(lower)) {
            return true;
        }
        for (String word : SECRET_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The headers to send for one request. Refused headers are dropped with their error, and a secret header whose
     * value is missing on this computer is skipped with a warning; both go through {@code warn}.
     */
    public static List<ExtraHeader> forRequest(List<String> names, Map<String, String> plainValues,
                                               Function<String, Optional<char[]>> secretLookup, boolean apiKeySet,
                                               Consumer<String> warn) {
        List<ExtraHeader> out = new ArrayList<>();
        for (String name : names) {
            Optional<String> error = validate(name, apiKeySet);
            if (error.isPresent()) {
                warn.accept(error.get());
                continue;
            }
            if (isSecret(name)) {
                Optional<char[]> value = secretLookup.apply(name);
                if (value.isEmpty()) {
                    warn.accept("Header " + name + " needs its value on this computer");
                    continue;
                }
                out.add(new ExtraHeader(name, new String(value.get())));
            }
            else {
                String value = plainValues.get(name);
                if (value != null) {
                    out.add(new ExtraHeader(name, value));
                }
            }
        }
        return out;
    }
}
