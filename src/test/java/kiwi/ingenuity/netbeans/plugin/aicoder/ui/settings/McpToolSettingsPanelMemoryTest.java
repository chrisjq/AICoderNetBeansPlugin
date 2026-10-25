package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.Component;
import java.awt.Container;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolCategoryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpToolSettingsPanelMemoryTest {

    private static McpToolSettingsPanel createPanel() throws Exception {
        final McpToolSettingsPanel[] panel = new McpToolSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new McpToolSettingsPanel());
        return panel[0];
    }

    private static boolean hasTitledSection(Component root, String title) {
        if (root instanceof JComponent component && component.getBorder() instanceof TitledBorder border
                && title.equals(border.getTitle())) {
            return true;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasTitledSection(child, title)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasButton(Component root, String text) {
        if (root instanceof AbstractButton button && text.equals(button.getText())) {
            return true;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasButton(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @Timeout(20)
    void theMemorySectionIsShownOnlyForAnOpenAiCompatibleSession() throws Exception {
        McpToolSettingsPanel panel = createPanel();
        assertTrue(hasTitledSection(panel, "Memory (OpenAI Compatible only)"));
        assertTrue(hasButton(panel, McpToolGroupEnum.MEMORY.label()));

        SwingUtilities.invokeAndWait(() -> panel.load(new AiSessionSettings()));
        assertFalse(panel.isCategoryVisible(McpToolCategoryEnum.MEMORY), "other backends never see the section");
        assertTrue(panel.isCategoryVisible(McpToolCategoryEnum.FILES));

        SwingUtilities.invokeAndWait(() -> panel.load(new OpenAiCompatibleSessionSettings()));
        assertTrue(panel.isCategoryVisible(McpToolCategoryEnum.MEMORY));
        assertTrue(panel.isGroupEnabled(McpToolGroupEnum.MEMORY), "on by default");
    }

    @Test
    @Timeout(20)
    void theMemoryGroupCanBeSwitchedOffAndOnInThePanel() throws Exception {
        McpToolSettingsPanel panel = createPanel();
        SwingUtilities.invokeAndWait(() -> panel.load(new OpenAiCompatibleSessionSettings()));

        SwingUtilities.invokeAndWait(() -> panel.applyConfig(McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.MEMORY, true)));
        assertFalse(panel.isGroupEnabled(McpToolGroupEnum.MEMORY));

        SwingUtilities.invokeAndWait(() -> panel.applyConfig(McpToolConfig.ALL_ENABLED));
        assertTrue(panel.isGroupEnabled(McpToolGroupEnum.MEMORY));
    }
}
