package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.ui;

import java.awt.GraphicsEnvironment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CodexInfoBarPersistedModelsTest {

    private List<String> catalogBefore;

    @BeforeEach
    void isolate() {
        CodexAiImplementation.discoveryDisabledForTests = true;
        CodexPluginSettings.useForTests(MemoryPreferences.create());
        catalogBefore = CodexAiImplementation.modelCatalog().getCachedModels();
    }

    @AfterEach
    void restore() throws Exception {
        CodexAiImplementation.modelCatalog().publish(catalogBefore);
        CodexAiImplementation.modelCatalog().invalidate();
        CodexPluginSettings.useForTests(null);
        CodexAiImplementation.discoveryDisabledForTests = false;
    }

    @Test
    @Timeout(15)
    void anInfoBarWithAnEmptyCatalogOffersThePersistedList() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "the info bar needs a display");
        CodexPluginSettings.setDiscoveredModels(List.of("persisted-x"));
        CodexAiImplementation.modelCatalog().publish(List.of());
        AiSession session = new AiSession("codex-bar", "Test", null, AiTypeEnum.CODEX, null,
                                          new CodexSessionSettings(), Instant.now(), Instant.now());
        CodexAiImplementation impl = new CodexAiImplementation(event -> {
        }, null);
        List<Object> items = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> {
            var ext = impl.createInfoBarExtension(session, null);
            JComboBox<?> model = (JComboBox<?>) ext.createComponents().get(0);
            for (int i = 0; i < model.getItemCount(); i++) {
                items.add(model.getItemAt(i));
            }
        });

        assertTrue(items.contains("persisted-x"), "the info bar must offer the persisted list: " + items);
    }
}
