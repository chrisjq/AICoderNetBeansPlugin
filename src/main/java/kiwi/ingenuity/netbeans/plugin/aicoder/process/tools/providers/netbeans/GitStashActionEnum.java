package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The action values {@code GitStash} accepts, each with the description shown
 * to the caller.
 * <p>
 * These five strings were previously written out five times over: the tool
 * description, the action property description, the validation list, the "must
 * be one of" error, and the default applied when the argument is omitted — then
 * a sixth and seventh time in {@link GitProvider#gitStash}'s switch and its
 * default branch. Adding an action to the validation list but not the switch
 * produces the worst outcome available: the tool accepts the argument and the
 * provider silently performs a stash push instead.
 * <p>
 * Lives beside {@code GitProvider} rather than with the tool because tools
 * depend on providers and never the reverse.
 */
public enum GitStashActionEnum {
    PUSH("push", "Save the working tree to a new stash entry"),
    LIST("list", "List the existing stash entries"),
    POP("pop", "Apply the entry at the given index and remove it from the stash"),
    APPLY("apply", "Apply the entry at the given index but leave it in the stash"),
    DROP("drop", "Delete the entry at the given index without applying it");

    /**
     * Applied when the caller omits the action.
     */
    public static final GitStashActionEnum DEFAULT = PUSH;

    /**
     * Resolves a caller-supplied action, ignoring case and surrounding space.
     *
     * @param raw the incoming argument; may be null or blank
     * @return the matching constant, {@link #DEFAULT} when raw is null or
     * blank, or null when raw is a non-empty value that is not an action —
     * callers must reject that rather than falling back to the default, or an
     * unrecognised action would silently stash the working tree.
     */
    public static GitStashActionEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String needle = raw.strip().toLowerCase(java.util.Locale.ROOT);
        for (GitStashActionEnum a : values()) {
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

    GitStashActionEnum(String action, String description) {
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
