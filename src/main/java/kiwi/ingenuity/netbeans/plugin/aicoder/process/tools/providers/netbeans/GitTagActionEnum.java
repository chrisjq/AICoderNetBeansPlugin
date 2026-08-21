package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The action values {@code GitTag} accepts, each with the description shown to
 * the caller.
 * <p>
 * Keeping validation and provider dispatch on this enum prevents an action from
 * being advertised by the tool but falling through to a different provider
 * operation.
 */
public enum GitTagActionEnum {
    CREATE("create", "Create a new tag"),
    DELETE("delete", "Delete an existing tag"),
    LIST("list", "List all tags");

    /**
     * Applied when the caller omits the action.
     */
    public static final GitTagActionEnum DEFAULT = LIST;

    /**
     * Resolves a caller-supplied action, ignoring case and surrounding space.
     *
     * @param raw the incoming argument; may be null or blank
     * @return the matching constant, {@link #DEFAULT} when raw is null or
     * blank, or null when raw is a non-empty value that is not an action
     */
    public static GitTagActionEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String needle = raw.strip().toLowerCase(java.util.Locale.ROOT);
        for (GitTagActionEnum a : values()) {
            if (a.action.equals(needle)) {
                return a;
            }
        }
        return null;
    }

    /**
     * Comma-separated actions, marking the default — for schema text and
     * errors.
     */
    public static String actionList() {
        return Arrays.stream(values())
                .map(a -> a == DEFAULT ? a.action + " (default)" : a.action)
                .collect(Collectors.joining(", "));
    }

    private final String action;
    private final String description;

    GitTagActionEnum(String action, String description) {
        this.action = action;
        this.description = description;
    }

    public String action() {
        return action;
    }

    public String description() {
        return description;
    }

}
