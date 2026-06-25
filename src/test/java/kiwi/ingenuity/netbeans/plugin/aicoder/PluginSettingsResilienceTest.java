package kiwi.ingenuity.netbeans.plugin.aicoder;

import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

/**
 * Settings access must never throw, even when NetBeans' preference storage is
 * unavailable (e.g. a run/dev harness without the required --add-opens, where
 * NbPreferences' lazy Repository init fails). The MCP server calls
 * isDebugJson() on its request threads, so a throw there breaks request
 * handling and spams the IDE log.
 */
class PluginSettingsResilienceTest {

    @Test
    void isDebugJson_neverThrows_andDefaultsFalse() {
        // In the unit-test JVM there is no NetBeans runtime, so NbPreferences
        // cannot initialise — exercising the resilient fallback path.
        assertDoesNotThrow(() -> {
            boolean v = PluginSettings.isDebugJson();
            assertFalse(v, "should default to false when prefs unavailable");
        });
    }

    @Test
    void getHookServerPort_neverThrows_andReturnsDefault() {
        assertDoesNotThrow(() -> {
            int port = PluginSettings.getHookServerPort();
            // Default when prefs unavailable.
            assertFalse(port <= 0, "port should be a sane default");
        });
    }
}
