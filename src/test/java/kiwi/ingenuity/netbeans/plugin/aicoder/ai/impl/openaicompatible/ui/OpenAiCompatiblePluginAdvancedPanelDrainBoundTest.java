package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.GraphicsEnvironment;
import java.time.Duration;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.BackendDebugLogWriter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("aicoder-debuglog")
class OpenAiCompatiblePluginAdvancedPanelDrainBoundTest {

    @Test
    @Timeout(10)
    void savingSetsTheShutdownDrainBoundFromTheSavedSeconds() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        long savedSeconds = OpenAiCompatiblePluginSettings.getDebugLogDrainSeconds();
        Duration savedBound = BackendDebugLogWriter.shutdownDrainBound();
        try {
            OpenAiCompatiblePluginSettings.setDebugLogDrainSeconds(7);
            OpenAiCompatiblePluginAdvancedPanel panel = new OpenAiCompatiblePluginAdvancedPanel();
            panel.load();

            panel.store();

            assertEquals(Duration.ofSeconds(7), BackendDebugLogWriter.shutdownDrainBound());
        }
        finally {
            OpenAiCompatiblePluginSettings.setDebugLogDrainSeconds(savedSeconds);
            BackendDebugLogWriter.setShutdownDrainBound(savedBound);
        }
    }
}
