package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class CodexAiInfoBarExtensionTest {

    @Test
    void createComponentsHasModelEffortCompactContextAndRateLimit() {
        List<JComponent> components = new CodexAiInfoBarExtension("gpt-5.5").createComponents();

        assertEquals(5, components.size());
        assertTrue(components.get(0) instanceof JComboBox, "model combo first");
        assertTrue(components.get(1) instanceof JComboBox, "effort combo second");
        assertTrue(components.get(2) instanceof JButton, "Compact third");
        assertTrue(((JButton) components.get(2)).getText().contains("Compact"));
    }

    @Test
    void busyContractDisablesEveryActionControlAndReadyReEnablesThem() throws Exception {
        CodexAiInfoBarExtension ext = new CodexAiInfoBarExtension("gpt-5.5");
        List<JComponent> components = ext.createComponents();

        SwingUtilities.invokeAndWait(() -> ext.onBusyChanged(true));
        assertFalse(components.get(0).isEnabled(), "busy must disable the model combo");
        assertFalse(components.get(1).isEnabled(), "busy must disable the effort combo");
        assertFalse(components.get(2).isEnabled(), "busy must disable Compact");

        SwingUtilities.invokeAndWait(() -> ext.onBusyChanged(false));
        assertTrue(components.get(0).isEnabled(), "ready must re-enable the model combo");
        assertTrue(components.get(1).isEnabled(), "ready must re-enable the effort combo");
        assertTrue(components.get(2).isEnabled(), "ready must re-enable Compact");
    }

    @Test
    void pressingCompactRunsTheCompactListener() throws Exception {
        CodexAiInfoBarExtension ext = new CodexAiInfoBarExtension("gpt-5.5");
        AtomicInteger runs = new AtomicInteger();
        ext.addCompactListener(runs::incrementAndGet);
        JButton compact = (JButton) ext.createComponents().get(2);

        SwingUtilities.invokeAndWait(compact::doClick);

        assertEquals(1, runs.get());
    }

    @Test
    void aBusyBarCannotRunTheCompactListener() throws Exception {
        CodexAiInfoBarExtension ext = new CodexAiInfoBarExtension("gpt-5.5");
        AtomicInteger runs = new AtomicInteger();
        ext.addCompactListener(runs::incrementAndGet);
        JButton compact = (JButton) ext.createComponents().get(2);

        SwingUtilities.invokeAndWait(() -> {
            ext.onBusyChanged(true);
            compact.doClick();
        });

        assertEquals(0, runs.get(), "a press while busy must be impossible");
    }
}
