package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SummariseThresholdSettingsTest {

    private Preferences preferences;

    @BeforeEach
    void setUp() {
        preferences = Preferences.userRoot().node("openai-compatible-summarise-" + UUID.randomUUID());
        OpenAiCompatiblePluginSettings.useForTest(preferences);
    }

    @AfterEach
    void tearDown() throws Exception {
        OpenAiCompatiblePluginSettings.useForTest(null);
        preferences.removeNode();
    }

    @Test
    @Timeout(10)
    void pluginDefaultIs16384() {
        assertEquals(16384, OpenAiCompatiblePluginSettings.getSummariseThreshold());
    }

    @Test
    @Timeout(10)
    void pluginValueRoundTrips() {
        OpenAiCompatiblePluginSettings.setSummariseThreshold(20000);

        assertEquals(20000, OpenAiCompatiblePluginSettings.getSummariseThreshold());
    }

    @Test
    @Timeout(10)
    void pluginValueIsClampedToTheFloor() {
        OpenAiCompatiblePluginSettings.setSummariseThreshold(1000);

        assertEquals(OpenAiCompatiblePluginSettings.MIN_SUMMARISE_THRESHOLD,
                     OpenAiCompatiblePluginSettings.getSummariseThreshold());
        assertEquals(4096, OpenAiCompatiblePluginSettings.getSummariseThreshold());
    }

    @Test
    @Timeout(10)
    void sessionWithoutOverrideFollowsThePluginValue() {
        OpenAiCompatibleSessionSettings session = new OpenAiCompatibleSessionSettings();
        assertNull(session.summariseThreshold());
        assertEquals(16384, session.effectiveSummariseThreshold());

        OpenAiCompatiblePluginSettings.setSummariseThreshold(20000);

        assertEquals(20000, session.effectiveSummariseThreshold());
    }

    @Test
    @Timeout(10)
    void sessionOverrideWinsAndIsClampedToTheFloor() {
        OpenAiCompatibleSessionSettings session = new OpenAiCompatibleSessionSettings();
        session.setSummariseThreshold(30000);
        assertEquals(30000, session.effectiveSummariseThreshold());

        session.setSummariseThreshold(100);
        assertEquals(4096, session.effectiveSummariseThreshold());
    }
}
