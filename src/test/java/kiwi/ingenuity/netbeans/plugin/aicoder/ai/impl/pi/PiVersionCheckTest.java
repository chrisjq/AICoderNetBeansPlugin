package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiVersionCheckTest {

    @AfterEach
    void resetVerifiedVersion() {
        PiPluginSettings.setVerifiedVersion("");
    }

    @Test
    void testedVersionNeverWarns() {
        PiVersionCheck check = new PiVersionCheck("0.85.1");
        assertTrue(check.isTestedVersion());
        assertFalse(check.isWarningApplies());
    }

    @Test
    void untestedVersionWarnsWhenNotVerified() {
        PiPluginSettings.setVerifiedVersion("");
        PiVersionCheck check = new PiVersionCheck("0.86.0");
        assertFalse(check.isTestedVersion());
        assertTrue(check.isWarningApplies());
    }

    @Test
    void verifiedVersionHidesTheWarningForThatVersionOnly() {
        PiPluginSettings.setVerifiedVersion("0.86.0");
        PiVersionCheck verified = new PiVersionCheck("0.86.0");
        assertFalse(verified.isWarningApplies(), "the verified version must not warn");

        PiVersionCheck differentUntested = new PiVersionCheck("0.87.0");
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
        PiVersionCheck check = new PiVersionCheck("0.86.0");
        assertFalse(check.isMarkedNotWorkingThisSession());

        check.markNotWorkingThisSession();

        assertTrue(check.isMarkedNotWorkingThisSession());
        assertTrue(new PiVersionCheck("0.86.0").isMarkedNotWorkingThisSession(),
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
