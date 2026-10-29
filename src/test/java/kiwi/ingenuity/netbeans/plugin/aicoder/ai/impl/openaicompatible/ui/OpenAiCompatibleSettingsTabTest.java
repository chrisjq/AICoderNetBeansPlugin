package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.util.List;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SavedServerKeys;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.SavedServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openide.util.Lookup;

@Timeout(5)
class OpenAiCompatibleSettingsTabTest {

    @AfterEach
    void cleanup() {
        OpenAiCompatiblePluginSettings.useForTest(null);
    }

    @Test
    void reportsTheOpenAiCompatibleType() {
        assertEquals(AiTypeEnum.OPENAI_COMPATIBLE, new OpenAiCompatibleSettingsTab().getAiType());
    }

    @Test
    void isRegisteredAsASettingsTab() {
        assertTrue(Lookup.getDefault().lookupAll(SettingsTab.class).stream()
                .anyMatch(tab -> tab instanceof OpenAiCompatibleSettingsTab));
    }

    @Test
    void keylessOptionsStorePersistsBeforeReturning() {
        OpenAiCompatiblePluginSettings.useForTest(
                Preferences.userRoot().node("/aicoder-settings-tab-" + System.nanoTime()));
        SecretStore store = new SecretStore() {
            @Override
            public char[] read(String name) {
                return null;
            }

            @Override
            public void save(String name, char[] value, String description) {
            }

            @Override
            public void delete(String name) {
            }
        };
        OpenAiCompatibleSettingsTab tab = new OpenAiCompatibleSettingsTab(new SavedServerKeys(store, Runnable::run));
        tab.serversEditor().load();
        SavedServer added = tab.serversEditor().addForTest("A", "http://a:1", ServerTypeEnum.AUTO, null);

        tab.store();

        assertEquals(List.of(added), OpenAiCompatiblePluginSettings.getSavedServers());
    }
}
