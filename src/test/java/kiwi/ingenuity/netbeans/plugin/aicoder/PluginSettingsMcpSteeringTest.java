package kiwi.ingenuity.netbeans.plugin.aicoder;

import java.util.prefs.Preferences;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;
import org.openide.util.NbPreferences;

class PluginSettingsMcpSteeringTest {

    @Test
    void explicitStoredFalseOverridesTheEnabledDefault() {
        Preferences prefs = NbPreferences.forModule(PluginSettings.class);
        String key = PluginSettingsKeyEnum.MCP_STEERING.key();
        String previous = prefs.get(key, null);
        try {
            prefs.putBoolean(key, false);
            assertFalse(PluginSettings.isMcpSteering());
        } finally {
            restore(prefs, key, previous);
        }
    }

    private static void restore(Preferences prefs, String key, String previous) {
        if (previous == null) {
            prefs.remove(key);
        } else {
            prefs.put(key, previous);
        }
    }
}
