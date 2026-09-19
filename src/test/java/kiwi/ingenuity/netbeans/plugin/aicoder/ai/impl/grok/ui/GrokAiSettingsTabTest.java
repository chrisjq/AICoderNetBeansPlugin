package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Review finding 2: a non-editable {@code JComboBox} can retain a stale, no-longer-listed value as its selection even
 * though the visible display already shows "(model default)" — {@code store()} previously trusted that selection
 * without re-validating it, so an unsupported global reasoning effort could be silently re-persisted forever, with no
 * way for the user to clear it from the UI. Locks in the fix: {@code store()} now validates against
 * {@code GrokReasoningEffortSupport.supportedFor(model)} before persisting.
 */
class GrokAiSettingsTabTest {

    @AfterEach
    void resetGlobalSettings() {
        GrokPluginSettings.setExecutable("");
        GrokPluginSettings.setModel("");
        GrokPluginSettings.setReasoningEffort("");
        GrokPluginSettings.setDiscoveredModels(new String[0]);
    }

    @Test
    void getTabTitleAndAiType() {
        GrokAiSettingsTab tab = new GrokAiSettingsTab();
        assertEquals(AiTypeEnum.GROK.displayName(), tab.getTabTitle());
        assertEquals(AiTypeEnum.GROK, tab.getAiType());
    }

    @Test
    void store_doesNotWriteBackAnEffortUnsupportedByTheStoredModel() {
        GrokPluginSettings.setModel("grok-4.5");
        GrokPluginSettings.setReasoningEffort("xhigh");
        GrokAiSettingsTab tab = new GrokAiSettingsTab();

        tab.load();
        tab.store();

        assertEquals("", GrokPluginSettings.getReasoningEffort(),
                     "store() must never re-persist a reasoning effort the currently stored model does not support");
    }

    @Test
    void store_keepsAnEffortSupportedByTheStoredModel() {
        GrokPluginSettings.setModel("grok-4.6");
        GrokPluginSettings.setReasoningEffort("xhigh");
        GrokAiSettingsTab tab = new GrokAiSettingsTab();

        tab.load();
        tab.store();

        assertEquals("xhigh", GrokPluginSettings.getReasoningEffort(),
                     "a genuinely supported effort must still round-trip through load()/store() unchanged");
    }

    @Test
    void store_defaultLabelStoresAsEmpty() {
        GrokAiSettingsTab tab = new GrokAiSettingsTab();
        tab.load();
        tab.store();

        assertEquals("", GrokPluginSettings.getReasoningEffort());
    }
}
