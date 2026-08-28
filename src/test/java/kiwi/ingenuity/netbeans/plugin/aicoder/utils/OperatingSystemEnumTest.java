package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Platform detection, and the capability that now rides on it.
 * <p>
 * The mapping is tested through the package-private {@code detect(String)} seam precisely because the interesting cases
 * are platforms this machine is not — asserting only {@code current()} would test the CI box rather than the rule.
 */
class OperatingSystemEnumTest {

    /**
     * The bug a reviewer found: the common {@code nux|nix|aix} idiom folds AIX into Linux. That misreports the platform
     * to every AI session through GetInstructions. It reached the right ANSWER for created time — AIX has no reliable
     * birth time either — but from a false premise, so identity is now decided separately from capability.
     */
    @Test
    void aixIsItsOwnPlatformAndNotLinux() {
        assertEquals(OperatingSystemEnum.AIX, OperatingSystemEnum.detect("AIX"));
        assertEquals(OperatingSystemEnum.AIX, OperatingSystemEnum.detect("aix"));
    }

    @Test
    void windowsVariantsAreDetected() {
        assertEquals(OperatingSystemEnum.WINDOWS, OperatingSystemEnum.detect("Windows 10"));
        assertEquals(OperatingSystemEnum.WINDOWS, OperatingSystemEnum.detect("Windows Server 2019"));
    }

    /**
     * "Darwin" CONTAINS the substring "win", so a plain {@code contains("win")} test claims macOS as Windows. This test
     * found exactly that, and it is why the mac check now runs first. Keep both spellings here — dropping "Darwin"
     * would let the ordering silently regress.
     */
    @Test
    void macVariantsAreDetectedAndDarwinIsNotMistakenForWindows() {
        assertEquals(OperatingSystemEnum.MAC, OperatingSystemEnum.detect("Mac OS X"));
        assertEquals(OperatingSystemEnum.MAC, OperatingSystemEnum.detect("Darwin"));
    }

    @Test
    void linuxVariantsAreDetected() {
        assertEquals(OperatingSystemEnum.LINUX, OperatingSystemEnum.detect("Linux"));
        // Exercises the "nix" alternative specifically. "Linux" matches only via "nux", so without this a regression
        // dropping the "nix" branch would go unnoticed.
        assertEquals(OperatingSystemEnum.LINUX, OperatingSystemEnum.detect("MINIX"));
        // Not a Linux alias — HP-UX is its own Unix and correctly lands on OTHER. Asserted here so the boundary of
        // the nux/nix family test is explicit rather than incidental.
        assertEquals(OperatingSystemEnum.OTHER, OperatingSystemEnum.detect("HP-UX"));
    }

    /**
     * Anything unrecognised must land on OTHER rather than being guessed into a family, and a null or empty property
     * must not throw — {@code os.name} is read at class-init, so a failure there would take the whole plugin down.
     */
    @Test
    void unknownAndAbsentNamesFallToOther() {
        assertEquals(OperatingSystemEnum.OTHER, OperatingSystemEnum.detect("Solaris"));
        assertEquals(OperatingSystemEnum.OTHER, OperatingSystemEnum.detect("z/OS"));
        assertEquals(OperatingSystemEnum.OTHER, OperatingSystemEnum.detect(""));
        assertEquals(OperatingSystemEnum.OTHER, OperatingSystemEnum.detect(null));
    }

    /**
     * Birth time is claimed only where the OS records one. AIX and OTHER fail closed: guessing wrong toward silence
     * costs a field, while guessing wrong the other way emits the inode change time dressed up as a creation date.
     */
    @Test
    void onlyWindowsAndMacClaimARealFileBirthTime() {
        assertTrue(OperatingSystemEnum.WINDOWS.providesFileCreationTime());
        assertTrue(OperatingSystemEnum.MAC.providesFileCreationTime());
        assertFalse(OperatingSystemEnum.LINUX.providesFileCreationTime());
        assertFalse(OperatingSystemEnum.AIX.providesFileCreationTime(),
                "AIX has no reliable birth time — splitting it from LINUX must not change that answer");
        assertFalse(OperatingSystemEnum.OTHER.providesFileCreationTime());
    }

    @Test
    void everyPlatformHasADisplayNameForTheInstructionsLine() {
        for (OperatingSystemEnum os : OperatingSystemEnum.values()) {
            assertNotNull(os.displayName(), os + " must be nameable; GetInstructions states it to every session");
            assertFalse(os.displayName().isBlank(), os + " display name must not be blank");
        }
    }

    @Test
    void currentResolvesToSomethingWithoutThrowing() {
        assertNotNull(OperatingSystemEnum.current());
    }

    /**
     * What an AI is told is the RAW os.name, not the enum's label. The enum collapses everything it does not branch on
     * into OTHER, so reporting its label would tell a Solaris user "Unknown" when the JVM knew exactly what they were
     * on. Lossy value for decisions, exact value for reporting.
     * <p>
     * Fed a platform whose label DIFFERS from its name, because on Linux the two coincide — {@code LINUX.displayName()}
     * is literally "Linux" — so asserting against the live property could not tell a genuine pass-through from a
     * regression to {@code current().displayName()}. "SunOS" maps to OTHER, so a label-based implementation would
     * return "Unknown" here and fail.
     */
    @Test
    void rawNameReportsTheOsNameNotTheEnumLabel() {
        assertEquals("SunOS", OperatingSystemEnum.rawNameOrFallback("SunOS"),
                "an unrecognised platform must still be reported by its real name, not as Unknown");
        assertEquals(OperatingSystemEnum.OTHER, OperatingSystemEnum.detect("SunOS"),
                "fixture check: SunOS must be a platform whose label differs from its name, or this proves nothing");
    }

    @Test
    void rawNameFallsBackOnlyWhenTheePropertyIsUnusable() {
        assertEquals(OperatingSystemEnum.OTHER.displayName(), OperatingSystemEnum.rawNameOrFallback(null));
        assertEquals(OperatingSystemEnum.OTHER.displayName(), OperatingSystemEnum.rawNameOrFallback("   "));
        assertEquals("Linux", OperatingSystemEnum.rawNameOrFallback("  Linux  "),
                "surrounding whitespace is trimmed, but the name itself is untouched");
    }

    @Test
    void liveRawNameIsUsableForTheInstructionsLine() {
        String raw = OperatingSystemEnum.rawName();

        assertNotNull(raw);
        assertFalse(raw.isBlank(), "an empty OS line in the instructions would be worse than none");
    }
}
