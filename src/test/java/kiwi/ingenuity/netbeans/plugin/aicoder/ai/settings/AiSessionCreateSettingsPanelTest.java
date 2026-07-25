package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSettingsCreator;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AiSessionCreateSettingsPanelTest {

    @Test
    void modelPanelCopiesEditedModelIntoTypedSettings() {
        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        AiSessionCreateSettingsPanel<ClaudeSessionSettings> panel = creator.createSettingsPanel();
        ClaudeSessionSettings source = new ClaudeSessionSettings();
        source.setModel("claude-test");
        panel.load(source);
        ClaudeSessionSettings target = new ClaudeSessionSettings();
        panel.applyTo(target);
        assertEquals("claude-test", target.model());
        assertNotNull(panel.component());
        panel.dispose();
    }

    @Test
    void userSelectedModelIsReusedByFreshPanel() {
        ClaudeSettingsCreator creator = new ClaudeSettingsCreator();
        AiSessionCreateSettingsPanel<ClaudeSessionSettings> first = creator.createSettingsPanel();
        JComboBox<Object> models = (JComboBox<Object>) ((JPanel) first.component()).getComponent(1);
        models.addItem("remembered-model");
        models.setSelectedItem("remembered-model");
        first.dispose();

        AiSessionCreateSettingsPanel<ClaudeSessionSettings> second = creator.createSettingsPanel();
        ClaudeSessionSettings target = new ClaudeSessionSettings();
        second.load(target);
        second.applyTo(target);
        assertEquals("remembered-model", target.model());
        second.dispose();
    }

    @Test
    void ollamaPanelCopiesModelAndBaseUrl() {
        OllamaSettingsCreator creator = new OllamaSettingsCreator();
        AiSessionCreateSettingsPanel<OllamaSessionSettings> panel = creator.createSettingsPanel();
        OllamaSessionSettings source = new OllamaSessionSettings();
        source.setModel("llama-test");
        source.setBaseUrl("http://localhost:11434");
        panel.load(source);
        OllamaSessionSettings target = new OllamaSessionSettings();
        panel.applyTo(target);
        assertEquals("llama-test", target.model());
        assertEquals("http://localhost:11434", target.baseUrl());
        panel.dispose();
    }
}
