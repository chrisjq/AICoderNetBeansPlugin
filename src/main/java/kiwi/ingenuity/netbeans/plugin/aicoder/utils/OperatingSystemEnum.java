package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.util.Locale;

/**
 * The operating system the IDE is running on, detected once at class-init.
 * <p>
 * {@code System.getProperty("os.name")} is parsed ad hoc in several providers and executable locators, each with its
 * own {@code contains("win")} test. Every such copy is a chance for the tests to disagree — and a platform question
 * answered two different ways in one process is the kind of defect that only shows up on somebody else's machine.
 * Detection belongs in one place.
 * <p>
 * Capabilities are exposed as named questions rather than by comparing the constant. A caller asking
 * {@link #providesFileCreationTime()} states WHY it cares; a caller writing {@code == LINUX} states only what it
 * checked, and the reason is lost the moment someone adds a platform.
 */
public enum OperatingSystemEnum {
    WINDOWS("Windows"),
    MAC("macOS"),
    LINUX("Linux"),
    AIX("AIX"),
    /**
     * Anything not named above. The enumerated platforms are the ones that need distinct behaviour; this is
     * deliberately the end of the list rather than the start of a taxonomy, so resist adding constants for platforms
     * nothing actually branches on.
     */
    OTHER("Unknown");

    private static final String RAW_NAME = System.getProperty("os.name", "").trim();

    private static final OperatingSystemEnum CURRENT = detect(RAW_NAME);

    /**
     * The unmodified {@code os.name}, for reporting rather than branching.
     * <p>
     * Anything shown to a person or an AI should use this, not {@link #displayName()}. The enum deliberately collapses
     * every platform it does not branch on into {@link #OTHER}, so its label would tell an AIX or Solaris user
     * "Unknown" when the JVM knew perfectly well what they were running. Keep the lossy value for decisions and the
     * exact one for reporting.
     */
    public static String rawName() {
        return rawNameOrFallback(RAW_NAME);
    }

    /**
     * Package-visible for tests, which need an {@code os.name} whose enum LABEL differs from the raw string.
     * <p>
     * On Linux the two coincide — {@code LINUX.displayName()} is literally "Linux" — so a test asserting only against
     * the live property cannot tell a genuine pass-through from a regression to {@code current().displayName()}. Feed
     * this something like "SunOS", which maps to {@link #OTHER} and would come back as "Unknown" if the label were ever
     * wired in by mistake.
     */
    static String rawNameOrFallback(String osName) {
        String trimmed = osName == null ? "" : osName.trim();
        return trimmed.isBlank() ? OTHER.displayName() : trimmed;
    }

    /**
     * The OS this JVM is running on. Resolved once — {@code os.name} cannot change during a run.
     */
    public static OperatingSystemEnum current() {
        return CURRENT;
    }

    /**
     * Package-visible for tests, which need to exercise the mapping for platforms they are not running on.
     */
    static OperatingSystemEnum detect(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        // macOS is tested BEFORE Windows, and the order is load-bearing: "Darwin" CONTAINS "win", so the usual
        // contains("win") test claims it as Windows. That would report macOS as Windows to every AI session and take
        // the Windows branch of every capability question. Caught by a test asserting the mapping for a platform this
        // machine is not — which is the only way this class of bug shows up before a user hits it.
        if (name.contains("mac") || name.contains("darwin")) {
            return MAC;
        }
        if (name.contains("win")) {
            return WINDOWS;
        }
        // AIX is tested BEFORE the Linux family and has its own constant. The common
        // {@code nux|nix|aix} idiom folds it into Linux, which misreports the platform to every AI session through
        // GetInstructions. It happened to reach the right answer for created time — AIX has no reliable birth time
        // either — but that was a correct outcome from a false premise, and identity has to be right independently of
        // the capability that currently rides on it.
        if (name.contains("aix")) {
            return AIX;
        }
        if (name.contains("nux") || name.contains("nix")) {
            return LINUX;
        }
        return OTHER;
    }

    private final String displayName;

    OperatingSystemEnum(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Whether a real file BIRTH time is available here.
     * <p>
     * Windows and macOS record one and the JVM surfaces it. Linux filesystems frequently do not, and where birth time
     * is missing the runtime substitutes the inode CHANGE time — a real timestamp, but not the creation date, and
     * indistinguishable from one through {@code BasicFileAttributes}. Reporting that as "created" invites a caller to
     * compare it against the modified time and conclude something false, so the field is omitted there instead.
     * <p>
     * {@link #OTHER} is treated as not providing it: guessing wrong in the direction of silence costs a field, while
     * guessing wrong the other way emits a plausible falsehood.
     */
    public boolean providesFileCreationTime() {
        return this == WINDOWS || this == MAC;
    }

    /**
     * Human-readable name for output shown to an AI or the user.
     */
    public String displayName() {
        return displayName;
    }
}
