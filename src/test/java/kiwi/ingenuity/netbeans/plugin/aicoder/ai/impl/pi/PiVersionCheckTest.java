package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiVersionCheckTest {

    /**
     * Derived from {@link PiVersionCheck#TESTED_MAJOR_MINOR}, never spelled out: these fixtures only care
     * whether a version is one the plugin was tested with or not, so hard-coding the then-current number
     * meant each bump of the constant silently reclassified them and failed tests that had nothing to do with
     * it. The untested pair sits far above any plausible pi release so a future bump cannot collide with it
     * either.
     */
    private static final String TESTED = PiVersionCheck.TESTED_MAJOR_MINOR + ".1";
    private static final String UNTESTED = "9.99.0";
    private static final String OTHER_UNTESTED = "9.98.0";

    @AfterEach
    void resetVerifiedVersion() {
        PiPluginSettings.setVerifiedVersion("");
    }

    @Test
    void testedVersionNeverWarns() {
        PiVersionCheck check = new PiVersionCheck(TESTED);
        assertTrue(check.isTestedVersion());
        assertFalse(check.isWarningApplies());
    }

    @Test
    void untestedVersionWarnsWhenNotVerified() {
        PiPluginSettings.setVerifiedVersion("");
        PiVersionCheck check = new PiVersionCheck(UNTESTED);
        assertFalse(check.isTestedVersion());
        assertTrue(check.isWarningApplies());
    }

    @Test
    void verifiedVersionHidesTheWarningForThatVersionOnly() {
        PiPluginSettings.setVerifiedVersion(UNTESTED);
        PiVersionCheck verified = new PiVersionCheck(UNTESTED);
        assertFalse(verified.isWarningApplies(), "the verified version must not warn");

        PiVersionCheck differentUntested = new PiVersionCheck(OTHER_UNTESTED);
        assertTrue(differentUntested.isWarningApplies(), "a different untested version must still warn");
    }

    @Test
    void blankOrNullInstalledVersionNeverWarns() {
        assertFalse(new PiVersionCheck(null).isWarningApplies());
        assertFalse(new PiVersionCheck("").isWarningApplies());
        assertFalse(new PiVersionCheck("  ").isWarningApplies());
    }

    @Test
    void noAnswerIsMarkedForThisSessionButNeverPersisted() {
        PiVersionCheck check = new PiVersionCheck(UNTESTED);
        assertFalse(check.isMarkedNotWorkingThisSession());

        check.markNotWorkingThisSession();

        assertTrue(check.isMarkedNotWorkingThisSession());
        assertTrue(new PiVersionCheck(UNTESTED).isMarkedNotWorkingThisSession(),
                "the session mark is keyed by version string, not by instance");
        assertEquals("", PiPluginSettings.getVerifiedVersion(), "a No answer must never write ai.pi.verifiedVersion");
        assertTrue(check.isWarningApplies(), "the warning still applies after No — it is not verified, just marked");
    }

    @Test
    void majorMinorExtractsExactlyTwoSegments() {
        assertEquals("0.85", PiVersionCheck.majorMinor("0.85.1"));
        assertEquals("0.85", PiVersionCheck.majorMinor("0.85"));
        assertEquals("1.2", PiVersionCheck.majorMinor("1.2.3.4"));
        assertEquals("", PiVersionCheck.majorMinor(null));
    }
}
