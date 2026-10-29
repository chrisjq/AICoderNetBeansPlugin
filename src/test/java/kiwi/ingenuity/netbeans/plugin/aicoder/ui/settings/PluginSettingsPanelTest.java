package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.beans.PropertyChangeListener;
import java.util.List;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class PluginSettingsPanelTest {

    @Test
    void orderAiTabsMovesOllamaToTheEndAndPreservesOtherOrder() {
        SettingsTab codex = tab(AiTypeEnum.CODEX);
        SettingsTab ollama = tab(AiTypeEnum.OLLAMA_LOCAL);
        SettingsTab grok = tab(AiTypeEnum.GROK);

        assertEquals(List.of(codex, grok, ollama),
                     PluginSettingsPanel.orderAiTabs(List.of(codex, ollama, grok)));
    }

    private static SettingsTab tab(AiTypeEnum type) {
        return new SettingsTab() {
            @Override
            public String getTabTitle() {
                return type.displayName();
            }

            @Override
            public JPanel getComponent() {
                return new JPanel();
            }

            @Override
            public void load() {
            }

            @Override
            public void store() {
            }

            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public void addPropertyChangeListener(PropertyChangeListener listener) {
            }

            @Override
            public void removePropertyChangeListener(PropertyChangeListener listener) {
            }

            @Override
            public AiTypeEnum getAiType() {
                return type;
            }
        };
    }
}
