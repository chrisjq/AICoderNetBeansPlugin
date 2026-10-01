package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrokCreateSettingsPanelTest {

    @BeforeEach
    @AfterEach
    void resetGlobalState() {
        GrokPluginSettings.setReasoningEffort("");
    }

    @Test
    void defaultLabelMapsToNullStoredReasoningEffort() {
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(new AiModelCatalog(AiTypeEnum.GROK));
        GrokSessionSettings empty = new GrokSessionSettings();
        panel.load(empty);

        GrokSessionSettings result = new GrokSessionSettings();
        panel.applyTo(result);

        assertNull(result.reasoningEffort(), "\"(model default)\" must store null, not an empty/placeholder string");
    }

    @Test
    void globalDefaultReasoningEffortPropagatesWhenSessionHasNone() {
        GrokPluginSettings.setReasoningEffort("medium");
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(new AiModelCatalog(AiTypeEnum.GROK));
        GrokSessionSettings empty = new GrokSessionSettings();
        panel.load(empty);

        GrokSessionSettings result = new GrokSessionSettings();
        panel.applyTo(result);

        assertEquals("medium", result.reasoningEffort());
    }

    @Test
    void storedReasoningEffortRoundTripsThroughLoadAndApplyTo() {
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(new AiModelCatalog(AiTypeEnum.GROK));
        GrokSessionSettings stored = new GrokSessionSettings();
        stored.setReasoningEffort("xhigh");
        panel.load(stored);

        GrokSessionSettings result = new GrokSessionSettings();
        panel.applyTo(result);

        assertEquals("xhigh", result.reasoningEffort());
    }

    private static JComboBox<?> modelComboOf(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComboBox<?> combo) {
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

    private static List<Object> itemsOf(JComboBox<?> combo) throws Exception {
        List<Object> items = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < combo.getItemCount(); i++) {
                items.add(combo.getItemAt(i));
            }
        });
        return items;
    }

    private static void awaitItems(JComboBox<?> combo, List<String> expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !itemsOf(combo).containsAll(expected)) {
            Thread.sleep(20);
        }
    }

    @Test
    void modelsPublishedOnTheCatalogReachThePanelThroughTheBus() throws Exception {
        AiModelCatalog catalog = new AiModelCatalog(AiTypeEnum.GROK);
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(catalog);
        try {
            JComboBox<?> combo = modelComboOf((Container) panel.component());
            List<String> published = List.of("bus-model-1", "bus-model-2");

            catalog.publish(published);
            awaitItems(combo, published);

            assertTrue(itemsOf(combo).containsAll(published),
                    "a published catalog list must reach a panel of that AI type, but the combo held " + itemsOf(combo));
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    void disposedPanelNoLongerFollowsTheCatalog() throws Exception {
        AiModelCatalog catalog = new AiModelCatalog(AiTypeEnum.GROK);
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(catalog);
        JComboBox<?> combo = modelComboOf((Container) panel.component());
        List<Object> beforePublish = itemsOf(combo);
        CountDownLatch sentinelSawEvent = new CountDownLatch(1);
        AiPropertyListener sentinel = event -> sentinelSawEvent.countDown();
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.GROK, sentinel);
        try {
            panel.dispose();

            catalog.publish(List.of("after-dispose-1", "after-dispose-2"));

            assertTrue(sentinelSawEvent.await(5, TimeUnit.SECONDS), "the event never reached the bus");
            assertEquals(beforePublish, itemsOf(combo), "a disposed panel must unregister from the bus");
        }
        finally {
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.GROK, sentinel);
        }
    }

    @Test
    void modelSelectionRoundTripsThroughLoadAndApplyTo() {
        GrokCreateSettingsPanel panel = new GrokCreateSettingsPanel(new AiModelCatalog(AiTypeEnum.GROK));
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.6");
        panel.load(settings);

        GrokSessionSettings result = new GrokSessionSettings();
        panel.applyTo(result);

        assertEquals("grok-4.6", result.model());
    }
}
