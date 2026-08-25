package kiwi.ingenuity.netbeans.plugin.aicoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PluginSettingsGitAccessTest {

    @Test
    void gitAccessDefaultsToOnSoExistingBehaviourIsUnchanged() {
        assertEquals(Boolean.TRUE, PluginSettingsKeyEnum.ALLOW_GIT_ACCESS.defaultValue());
        assertEquals(Boolean.TRUE, PluginSettingsKeyEnum.ALLOW_GIT_READ.defaultValue());
        assertEquals(Boolean.TRUE, PluginSettingsKeyEnum.ALLOW_GIT_WRITE.defaultValue());
    }

    @Test
    void everyOptionResolvesToItsOwnKey() {
        assertEquals(PluginSettingsKeyEnum.ALLOW_GIT_READ,
                PluginSettingsKeyEnum.forGitAccessOption(GitAccessOptionEnum.READ));
        assertEquals(PluginSettingsKeyEnum.ALLOW_GIT_WRITE,
                PluginSettingsKeyEnum.forGitAccessOption(GitAccessOptionEnum.WRITE));
    }

    @Test
    void keysAreNamespacedUnderTheMasterFlag() {
        assertEquals("ai.session.allowGitAccess", PluginSettingsKeyEnum.ALLOW_GIT_ACCESS.key());
        assertTrue(PluginSettingsKeyEnum.ALLOW_GIT_READ.key().startsWith("ai.session.allowGitAccess."));
        assertTrue(PluginSettingsKeyEnum.ALLOW_GIT_WRITE.key().startsWith("ai.session.allowGitAccess."));
    }
}
