package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class OpenCodeCreateSettingsPanelTest {

    @Test
    void globalDefaultModePropagatesWhenNothingRememberedOrSet() {
        OpenCodePluginSettings.setMode("plan");
        OpenCodeCreateSettingsPanel.lastSelectedMode = null;
        try {
            OpenCodeCreateSettingsPanel panel = new OpenCodeCreateSettingsPanel(new AiModelCatalog());
            OpenCodeSessionSettings empty = new OpenCodeSessionSettings(); // mode() == null

            panel.load(empty);

            OpenCodeSessionSettings result = new OpenCodeSessionSettings();
            panel.applyTo(result);

            assertEquals("plan", result.mode(),
                    "load must fall back to OpenCodePluginSettings.getMode() when session mode and remembered mode are both absent");
        }
        finally {
            OpenCodePluginSettings.setMode(OpenCodePluginSettings.DEFAULT_MODE);
            OpenCodeCreateSettingsPanel.lastSelectedMode = null;
        }
    }
}
