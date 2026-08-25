package kiwi.ingenuity.netbeans.plugin.aicoder;

/**
 * Sub-options gated behind the "Allow git access" master toggle — mirrors {@link DatabaseAccessOptionEnum} and
 * {@link WebRequestAccessOptionEnum}.
 * <p>
 * The partition is derived from {@code McpToolInterface#isMutating()}, which every tool already declares, rather than
 * from a hand-maintained list of tool names. A git tool added later is classified by the method it must implement
 * anyway, so the two can never disagree.
 * <p>
 * This is deliberately a property of the TOOL, not of its arguments. GitBranch, GitTag, GitRemote and GitStash each
 * have a list mode and are still WRITE, because deciding per-call would mean re-deriving every tool's argument
 * semantics at the enforcement point.
 */
public enum GitAccessOptionEnum {
    READ(AccessControlLabelEnum.ALLOW_GIT_READ),
    WRITE(AccessControlLabelEnum.ALLOW_GIT_WRITE);

    public static GitAccessOptionEnum forMutating(boolean mutating) {
        return mutating ? WRITE : READ;
    }

    private final AccessControlLabelEnum label;

    GitAccessOptionEnum(AccessControlLabelEnum label) {
        this.label = label;
    }

    public AccessControlLabelEnum label() {
        return label;
    }
}
