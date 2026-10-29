package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SavedServersSettingsTest {

    @AfterEach
    void cleanup() {
        OpenAiCompatiblePluginSettings.useForTest(null);
    }

    @Test
    void savedServersRoundTripInOrderWithDefault() {
        useTestPreferences();
        SavedServer a = SavedServer.create("Local llama", "http://localhost:8080/v1", ServerTypeEnum.LLAMA_CPP);
        SavedServer b = SavedServer.create("", "http://10.0.0.45:1234", null);
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(a, b));
        OpenAiCompatiblePluginSettings.setDefaultServerId(b.id());

        assertEquals(List.of(a, b), OpenAiCompatiblePluginSettings.getSavedServers());
        assertEquals("http://localhost:8080", a.url());
        assertEquals(ServerTypeEnum.AUTO, b.serverType());
        assertEquals("Local llama — http://localhost:8080", a.label());
        assertEquals("http://10.0.0.45:1234", b.label());
        assertEquals(Optional.of(b), OpenAiCompatiblePluginSettings.getDefaultServer());
    }

    @Test
    void defaultFallsBackToFirstEntryThenEmpty() {
        useTestPreferences();
        assertEquals(Optional.empty(), OpenAiCompatiblePluginSettings.getDefaultServer());
        SavedServer a = SavedServer.create("A", "http://a:1", ServerTypeEnum.AUTO);
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(a));
        OpenAiCompatiblePluginSettings.setDefaultServerId("missing");

        assertEquals(Optional.of(a), OpenAiCompatiblePluginSettings.getDefaultServer());
    }

    @Test
    void corruptJsonReadsAsEmptyList() {
        Preferences preferences = Preferences.userRoot().node("/aicoder-saved-bad-" + System.nanoTime());
        OpenAiCompatiblePluginSettings.useForTest(preferences);
        preferences.put(OpenAiCompatiblePluginSettingsKeyEnum.SAVED_SERVERS.key(), "{not json");

        assertEquals(List.of(), OpenAiCompatiblePluginSettings.getSavedServers());
    }

    private static void useTestPreferences() {
        OpenAiCompatiblePluginSettings.useForTest(
                Preferences.userRoot().node("/aicoder-saved-" + System.nanoTime()));
    }
}
