package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Codex session-create panel keeps a custom model after its catalogue refreshes: the stored model stays selected
 * and selectable, and {@code auto} stays first. Swing work runs on the EDT.
 */
class CodexCreateSettingsPanelModelListTest {

    @BeforeEach
    void isolate() {
        CodexAiImplementation.discoveryDisabledForTests = true;
        CodexPluginSettings.useForTests(MemoryPreferences.create());
    }

    @AfterEach
    void restore() {
        CodexAiImplementation.discoveryDisabledForTests = false;
        CodexPluginSettings.useForTests(null);
    }

    @Test
    @Timeout(15)
    void aStoredCustomModelSurvivesACatalogRefreshAndAutoStaysFirst() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "the create panel needs a display");
        AiModelCatalog catalog = new AiModelCatalog(AiTypeEnum.CODEX);
        CodexCreateSettingsPanel[] holder = new CodexCreateSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new CodexCreateSettingsPanel(catalog);
            CodexSessionSettings stored = new CodexSessionSettings();
            stored.setModel("gpt-6-sol");
            holder[0].load(stored);
        });
        CodexCreateSettingsPanel panel = holder[0];
        try {
            JComboBox<?> model = modelComboOf(panel.component());

            catalog.publish(List.of("catalog-refresh-a", "catalog-refresh-b"));
            awaitItem(model, "catalog-refresh-a");

            List<Object> items = itemsOnEdt(model);
            assertEquals(CodexPluginSettings.AUTO_MODEL, items.get(0), "auto must stay first: " + items);
            assertTrue(items.contains("gpt-6-sol"), "the stored custom model must stay selectable: " + items);

            CodexSessionSettings applied = new CodexSessionSettings();
            SwingUtilities.invokeAndWait(() -> panel.applyTo(applied));
            assertEquals("gpt-6-sol", applied.model(), "the stored custom model must still be what applies");
        }
        finally {
            SwingUtilities.invokeAndWait(panel::dispose);
        }
    }

    /**
     * The model combo is the one whose first entry is {@code auto}; the effort combo starts with the default label.
     */
    private static JComboBox<?> modelComboOf(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComboBox<?> combo && combo.getItemCount() > 0
                    && CodexPluginSettings.AUTO_MODEL.equals(combo.getItemAt(0))) {
                return combo;
            }
            if (child instanceof Container nested) {
                JComboBox<?> found = modelComboOf(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<Object> itemsOnEdt(JComboBox<?> combo) throws Exception {
        List<Object> items = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < combo.getItemCount(); i++) {
                items.add(combo.getItemAt(i));
            }
        });
        return items;
    }

    /**
     * The panel applies a published list straight away when it is not on screen, otherwise on the EDT; polling covers
     * both.
     */
    private static void awaitItem(JComboBox<?> combo, String item) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (itemsOnEdt(combo).contains(item)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the catalogue refresh never reached the combo: " + itemsOnEdt(combo));
    }
}
