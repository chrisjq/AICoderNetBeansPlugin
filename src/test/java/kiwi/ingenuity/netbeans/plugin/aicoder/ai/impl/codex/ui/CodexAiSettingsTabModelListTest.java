package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Codex settings tab keeps a custom default model after the catalogue refreshes: the stored model stays selected
 * and selectable, and {@code auto} stays first. The tab listens to the catalogue only while it is displayed, so each
 * test shows it in a frame. Swing work runs on the EDT.
 */
class CodexAiSettingsTabModelListTest {

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
    void aTabWithAnEmptyCatalogIsSeededFromThePersistedList() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "the settings tab needs a display");
        List<String> before = CodexAiImplementation.modelCatalog().getCachedModels();
        JFrame[] frame = new JFrame[1];
        CodexAiSettingsTab[] tab = new CodexAiSettingsTab[1];
        try {
            CodexPluginSettings.setDiscoveredModels(List.of("persisted-x"));
            CodexAiImplementation.modelCatalog().publish(List.of());
            SwingUtilities.invokeAndWait(() -> {
                tab[0] = new CodexAiSettingsTab();
                tab[0].load();
                frame[0] = showInFrame(tab[0]);
            });
            JComboBox<?> model = modelComboOf(tab[0].getComponent());

            assertTrue(itemsOnEdt(model).contains("persisted-x"),
                       "with no live list the tab must offer the persisted one: " + itemsOnEdt(model));
        }
        finally {
            disposeOnEdt(frame[0]);
            CodexAiImplementation.modelCatalog().publish(before);
            CodexAiImplementation.modelCatalog().invalidate();
        }
    }

    @Test
    @Timeout(15)
    void aStoredCustomModelSurvivesACatalogRefreshAndAutoStaysFirst() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "the settings tab needs a display");
        String savedModel = CodexPluginSettings.getModel();
        List<String> before = CodexAiImplementation.modelCatalog().getCachedModels();
        JFrame[] frame = new JFrame[1];
        CodexAiSettingsTab[] tab = new CodexAiSettingsTab[1];
        try {
            CodexPluginSettings.setModel("gpt-6-sol");
            SwingUtilities.invokeAndWait(() -> {
                tab[0] = new CodexAiSettingsTab();
                tab[0].load();
                frame[0] = showInFrame(tab[0]);
            });
            JComboBox<?> model = modelComboOf(tab[0].getComponent());

            CodexAiImplementation.modelCatalog().publish(List.of("catalog-refresh-a", "catalog-refresh-b"));
            awaitItem(model, "catalog-refresh-a");

            List<Object> items = itemsOnEdt(model);
            assertEquals(CodexPluginSettings.AUTO_MODEL, items.get(0), "auto must stay first: " + items);
            assertTrue(items.contains("gpt-6-sol"), "the stored custom model must stay selectable: " + items);
            assertEquals("gpt-6-sol", selectedOnEdt(model));
        }
        finally {
            disposeOnEdt(frame[0]);
            CodexPluginSettings.setModel(savedModel);
            CodexAiImplementation.modelCatalog().publish(before);
            CodexAiImplementation.modelCatalog().invalidate();
        }
    }

    @Test
    @Timeout(15)
    void aCatalogRefreshAndALoadDoNotMarkTheTabDirty() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "the settings tab needs a display");
        List<String> before = CodexAiImplementation.modelCatalog().getCachedModels();
        JFrame[] frame = new JFrame[1];
        CodexAiSettingsTab[] tab = new CodexAiSettingsTab[1];
        AtomicInteger changes = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                tab[0] = new CodexAiSettingsTab();
                tab[0].addPropertyChangeListener(event -> changes.incrementAndGet());
                frame[0] = showInFrame(tab[0]);
            });
            JComboBox<?> model = modelComboOf(tab[0].getComponent());

            CodexAiImplementation.modelCatalog().publish(List.of("catalog-dirty-a", "catalog-dirty-b"));
            awaitItem(model, "catalog-dirty-a");
            SwingUtilities.invokeAndWait(tab[0]::load);
            SwingUtilities.invokeAndWait(() -> {
            });

            assertEquals(0, changes.get(), "a refresh or a load is not a user edit and must not mark the tab dirty");
        }
        finally {
            disposeOnEdt(frame[0]);
            CodexAiImplementation.modelCatalog().publish(before);
            CodexAiImplementation.modelCatalog().invalidate();
        }
    }

    /**
     * Puts the tab into a frame and packs it, which makes the tab displayable and so registers its catalogue listener.
     */
    private static JFrame showInFrame(CodexAiSettingsTab tab) {
        JFrame frame = new JFrame();
        frame.getContentPane().add(tab.getComponent());
        frame.pack();
        return frame;
    }

    private static void disposeOnEdt(JFrame frame) throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(frame::dispose);
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

    private static Object selectedOnEdt(JComboBox<?> combo) throws Exception {
        Object[] selected = new Object[1];
        SwingUtilities.invokeAndWait(() -> selected[0] = combo.getSelectedItem());
        return selected[0];
    }

    /**
     * The catalogue listener refreshes the combo on the EDT after the event, so poll until the new entry shows.
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
