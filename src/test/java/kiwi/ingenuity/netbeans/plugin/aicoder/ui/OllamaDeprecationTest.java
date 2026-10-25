package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JLabel;
import javax.swing.JList;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui.OllamaAiSettingsTab;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OllamaDeprecationTest {

    @Test
    @Timeout(10)
    void ollamaIsDisabledByDefault() {
        assertTrue(!AiTypeEnum.OLLAMA_LOCAL.isEnabledByDefault());
    }

    @Test
    @Timeout(10)
    void rendererMarksOnlyOllamaDeprecated() {
        AiTypeRenderer renderer = new AiTypeRenderer();
        Component ollama = renderer.getListCellRendererComponent(new JList<>(), AiTypeEnum.OLLAMA_LOCAL, 0, false, false);
        String ollamaLabel = ((JLabel) ollama).getText();
        Component codex = renderer.getListCellRendererComponent(new JList<>(), AiTypeEnum.CODEX, 0, false, false);
        String codexLabel = ((JLabel) codex).getText();
        assertEquals("Ollama (Local) — deprecated", ollamaLabel);
        assertEquals("Codex", codexLabel);
    }

    @Test
    @Timeout(10)
    void pickerFallsBackAndNotesOnlyDisabledOllama() {
        assertEquals(AiTypeEnum.OPENAI_COMPATIBLE,
                     SessionPickerDialog.fallbackType(AiTypeEnum.OLLAMA_LOCAL, false));
        assertEquals(AiTypeEnum.CODEX,
                     SessionPickerDialog.fallbackType(AiTypeEnum.CODEX, false));
        assertTrue(SessionPickerDialog.showOllamaDeprecationNote(AiTypeEnum.OLLAMA_LOCAL, false));
        assertFalse(SessionPickerDialog.showOllamaDeprecationNote(AiTypeEnum.CODEX, false));
        assertFalse(SessionPickerDialog.showOllamaDeprecationNote(AiTypeEnum.OLLAMA_LOCAL, true));
    }

    @Test
    @Timeout(10)
    void settingsTabContainsDeprecationText() {
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        OllamaAiSettingsTab tab = new OllamaAiSettingsTab();
        assertTrue(findText(tab.getComponent(), "deprecated"));
    }

    private static boolean findText(Component component, String needle) {
        if (component instanceof JLabel label && label.getText() != null
                && label.getText().toLowerCase().contains(needle)) {
            return true;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (findText(child, needle)) {
                    return true;
                }
            }
        }
        return false;
    }
}
