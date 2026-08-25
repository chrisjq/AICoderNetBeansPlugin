package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Session-level resolution for the git access gate.
 * <p>
 * The persist/load half is covered by {@code AiSessionSettingsCreatorTest}. What lives here is the part nothing else
 * asserts: that {@code applyDefaultSettingsFromGlobal} MATERIALISES the master flag. The per-option state rides a
 * generic {@code values()} loop and is carried automatically, but the master flag is hand-listed there — omit its two
 * lines and nothing fails to compile, sessions silently stop inheriting it, and templates built from a session snapshot
 * quietly capture null.
 */
class AiSessionSettingsGitAccessTest {

    @Test
    void unsetMeansInheritThePluginDefault() {
        AiSessionSettings cfg = new AiSessionSettings();

        assertNull(cfg.allowGitAccess());
        assertEquals(PluginSettings.isAllowGitAccess(), cfg.effectiveAllowGitAccess());
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            assertNull(cfg.allowGitAccessOption(option), option.name());
            assertEquals(PluginSettings.isAllowGitAccessOption(option),
                    cfg.effectiveAllowGitAccessOption(option), option.name());
        }
    }

    @Test
    void sessionOverrideBeatsThePluginDefaultInBothDirections() {
        AiSessionSettings cfg = new AiSessionSettings();

        cfg.setAllowGitAccess(Boolean.FALSE);
        assertFalse(cfg.effectiveAllowGitAccess());

        cfg.setAllowGitAccess(Boolean.TRUE);
        assertTrue(cfg.effectiveAllowGitAccess());

        cfg.setAllowGitAccess(null);
        assertEquals(PluginSettings.isAllowGitAccess(), cfg.effectiveAllowGitAccess(),
                "null must fall back to the plugin default, not to false");
    }

    @Test
    void theTwoOptionsAreIndependentOfEachOther() {
        AiSessionSettings cfg = new AiSessionSettings();
        cfg.setAllowGitAccessOption(GitAccessOptionEnum.READ, Boolean.TRUE);
        cfg.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, Boolean.FALSE);

        assertTrue(cfg.effectiveAllowGitAccessOption(GitAccessOptionEnum.READ));
        assertFalse(cfg.effectiveAllowGitAccessOption(GitAccessOptionEnum.WRITE),
                "READ and WRITE must be separate fields, not one flag read twice");
    }

    @Test
    void applyDefaultsMaterialisesTheMasterFlagAndBothOptions() {
        AiSessionSettings cfg = new AiSessionSettings();
        cfg.applyDefaultSettingsFromGlobal();

        // Deliberately the NULLABLE getter. effectiveAllowGitAccess() returns the plugin default
        // whether or not the value was materialised, so asserting on it would pass with the
        // production lines deleted and prove nothing.
        assertEquals(PluginSettings.isAllowGitAccess(), cfg.allowGitAccess(),
                "master flag left null — its lines are missing from applyDefaultSettingsFromGlobal");
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            assertEquals(PluginSettings.isAllowGitAccessOption(option),
                    cfg.allowGitAccessOption(option), option.name());
        }
    }

    @Test
    void applyDefaultsDoesNotOverwriteAnExplicitSessionOverride() {
        AiSessionSettings cfg = new AiSessionSettings();
        cfg.setAllowGitAccess(Boolean.FALSE);
        cfg.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, Boolean.FALSE);

        cfg.applyDefaultSettingsFromGlobal();

        assertEquals(Boolean.FALSE, cfg.allowGitAccess(),
                "an explicit override must survive inheriting the rest of the defaults");
        assertEquals(Boolean.FALSE, cfg.allowGitAccessOption(GitAccessOptionEnum.WRITE));
    }
}
