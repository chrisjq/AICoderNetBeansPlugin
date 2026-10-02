package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui;

import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ClaudeAiInfoBarExtensionTest {

    @Test
    void busyDisablesEveryActionAndReadyRestoresIt() throws Exception {
        ClaudeAiInfoBarExtension extension = new ClaudeAiInfoBarExtension();
        List<JComponent> components = extension.createComponents();
        // model (0), effort (1) and Compact (last, after the three usage bars)
        List<JComponent> actions = List.of(components.get(0), components.get(1), components.get(5));
        assertTrue(components.get(5) instanceof JButton, "Compact is the last component");

        SwingUtilities.invokeAndWait(() -> extension.onBusyChanged(true));
        assertTrue(actions.stream().noneMatch(JComponent::isEnabled),
                "busy must disable Claude's model, effort, and Compact controls");

        SwingUtilities.invokeAndWait(() -> extension.onBusyChanged(false));
        assertTrue(actions.stream().allMatch(JComponent::isEnabled),
                "ready must re-enable Claude's model, effort, and Compact controls");
    }
}
