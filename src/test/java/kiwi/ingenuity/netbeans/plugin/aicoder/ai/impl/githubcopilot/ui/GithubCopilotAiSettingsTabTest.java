package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui;

import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Review finding: a non-editable {@code JComboBox} can retain a stale, no-longer-listed value as its selection even
 * though the visible display already shows "(model default)" — {@code store()} must not trust that selection without
 * re-validating it against the live per-model list, or an unsupported reasoning effort could be silently re-persisted
 * forever with no way for the user to clear it from the UI (the same class of bug hit on pi and Grok). Mirrors
 * {@code GrokAiSettingsTabTest}.
 */
class GithubCopilotAiSettingsTabTest {

    @BeforeEach
    @AfterEach
    void resetGlobalSettings() {
        GithubCopilotPluginSettings.setExecutable("");
        GithubCopilotPluginSettings.setModel("");
        GithubCopilotPluginSettings.setReasoningEffort("");
        GithubCopilotPluginSettings.setDiscoveredModels(null);
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
    }

    @Test
    void getTabTitleAndAiType() {
        GithubCopilotAiSettingsTab tab = new GithubCopilotAiSettingsTab();
        assertEquals(AiTypeEnum.GitHubCoPilot.displayName(), tab.getTabTitle());
        assertEquals(AiTypeEnum.GitHubCoPilot, tab.getAiType());
    }

    @Test
    void store_doesNotWriteBackAnEffortUnsupportedByTheStoredModel() {
        GithubCopilotPluginSettings.setModel("auto");
        GithubCopilotPluginSettings.setReasoningEffort("high");
        // No discovery has populated the per-model cache for "auto" — an unknown/no-data model means "no support".
        GithubCopilotAiSettingsTab tab = new GithubCopilotAiSettingsTab();

        tab.load();
        tab.store();

        assertEquals("", GithubCopilotPluginSettings.getReasoningEffort(),
                     "store() must never re-persist a reasoning effort the currently stored model does not support");
    }

    @Test
    void store_keepsAnEffortSupportedByTheStoredModel() {
        GithubCopilotPluginSettings.setModel("auto");
        GithubCopilotPluginSettings.setReasoningEffort("high");
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of("auto", List.of("low", "high")), Map.of());
        GithubCopilotAiSettingsTab tab = new GithubCopilotAiSettingsTab();

        tab.load();
        tab.store();

        assertEquals("high", GithubCopilotPluginSettings.getReasoningEffort(),
                     "a genuinely supported effort must still round-trip through load()/store() unchanged");
    }

    @Test
    void store_defaultLabelStoresAsEmpty() {
        GithubCopilotAiSettingsTab tab = new GithubCopilotAiSettingsTab();
        tab.load();
        tab.store();

        assertEquals("", GithubCopilotPluginSettings.getReasoningEffort());
    }
}
