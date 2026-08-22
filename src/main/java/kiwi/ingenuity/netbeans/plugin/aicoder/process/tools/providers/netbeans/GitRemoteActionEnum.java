package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The action values {@code GitRemote} accepts, each with the description shown to the caller.
 * <p>
 * Keeping validation and provider dispatch on this enum prevents an action from being advertised by the tool but
 * falling through to a different provider operation.
 */
public enum GitRemoteActionEnum {
    ADD("add", "Add a remote with its URL"),
    REMOVE("remove", "Remove an existing remote"),
    LIST("list", "List all configured remotes");

    /**
     * Applied when the caller omits the action.
     */
    public static final GitRemoteActionEnum DEFAULT = LIST;

    /**
     * Resolves a caller-supplied action, ignoring case and surrounding space.
     *
     * @param raw the incoming argument; may be null or blank
     * @return the matching constant, {@link #DEFAULT} when raw is null or blank, or null when raw is a non-empty value
     * that is not an action
     */
    public static GitRemoteActionEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String needle = raw.strip().toLowerCase(Locale.ROOT);
        for (GitRemoteActionEnum a : values()) {
            if (a.action.equals(needle)) {
                return a;
            }
        }
        return null;
    }

    /**
     * Comma-separated actions, marking the default — for schema text and errors.
     */
    public static String actionList() {
        return Arrays.stream(values())
                .map(a -> a == DEFAULT ? a.action + " (default)" : a.action)
                .collect(Collectors.joining(", "));
    }

    private final String action;
    private final String description;

    GitRemoteActionEnum(String action, String description) {
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
