package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui;

import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ClaudeAiInfoBarExtensionTest {

    @Test
    void busyDisablesEveryActionAndReadyRestoresIt() throws Exception {
        ClaudeAiInfoBarExtension extension = new ClaudeAiInfoBarExtension();
        List<JComponent> components = extension.createComponents();

        SwingUtilities.invokeAndWait(() -> extension.onBusyChanged(true));
        assertTrue(components.subList(0, 3).stream().noneMatch(JComponent::isEnabled),
                "busy must disable Claude's model, effort, and Compact controls");

        SwingUtilities.invokeAndWait(() -> extension.onBusyChanged(false));
        assertTrue(components.subList(0, 3).stream().allMatch(JComponent::isEnabled),
                "ready must re-enable Claude's model, effort, and Compact controls");
    }
}
