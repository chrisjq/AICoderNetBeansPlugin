package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;

/**
 * Compares an installed pi version against the version this plugin was tested with, and against any version the user
 * has explicitly verified.
 */
public final class PiVersionCheck {

    /**
     * The major.minor this plugin was tested against (pi 0.85.1).
     */
    public static final String TESTED_MAJOR_MINOR = "0.85";

    /**
     * Versions the user answered "No" to for this IDE session only — never persisted, so the warning returns on the
     * next IDE start. Session-scoped, not settings-backed, on purpose (spec: "'Marked as not working' lasts for the IDE
     * session only").
     */
    private static final Set<String> markedNotWorkingThisSession = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final String installedVersion;

    public PiVersionCheck(String installedVersion) {
        this.installedVersion = installedVersion;
    }

    public String installedVersion() {
        return installedVersion;
    }

    public String testedVersion() {
        return TESTED_MAJOR_MINOR;
    }

    /**
     * True when the installed version's major.minor matches {@link #TESTED_MAJOR_MINOR}.
     */
    public boolean isTestedVersion() {
        return TESTED_MAJOR_MINOR.equals(majorMinor(installedVersion));
    }

    /**
     * True when the installed version is neither a tested version nor one the user has explicitly verified. Never true
     * for a blank/unknown installed version — there is nothing to warn about yet.
     */
    public boolean isWarningApplies() {
        if (installedVersion == null || installedVersion.isBlank()) {
            return false;
        }
        if (isTestedVersion()) {
            return false;
        }
        String verified = PiPluginSettings.getVerifiedVersion();
        return !installedVersion.equals(verified);
    }

    /**
     * Whether this IDE session already recorded a "No" answer for {@link #installedVersion()}.
     */
    public boolean isMarkedNotWorkingThisSession() {
        return installedVersion != null && markedNotWorkingThisSession.contains(installedVersion);
    }

    /**
     * Records a "No" answer for {@link #installedVersion()}, for this IDE session only.
     */
    public void markNotWorkingThisSession() {
        if (installedVersion != null) {
            markedNotWorkingThisSession.add(installedVersion);
        }
    }

    /**
     * Extracts {@code "major.minor"} from a version string such as {@code "0.85.1"}. Tolerant of malformed/short input:
     * returns the input unchanged if it has fewer than two dot-separated segments.
     */
    static String majorMinor(String version) {
        if (version == null) {
            return "";
        }
        String trimmed = version.strip();
        int first = trimmed.indexOf('.');
        if (first < 0) {
            return trimmed;
        }
        int second = trimmed.indexOf('.', first + 1);
        return second < 0 ? trimmed : trimmed.substring(0, second);
    }
}
