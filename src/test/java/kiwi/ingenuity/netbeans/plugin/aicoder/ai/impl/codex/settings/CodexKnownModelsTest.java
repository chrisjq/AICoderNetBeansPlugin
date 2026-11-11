package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import java.util.List;
import java.util.stream.IntStream;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CodexKnownModelsTest {

    private List<String> catalogBefore;

    @BeforeEach
    void isolate() {
        CodexPluginSettings.useForTests(MemoryPreferences.create());
        catalogBefore = CodexAiImplementation.modelCatalog().getCachedModels();
    }

    @AfterEach
    void restore() throws Exception {
        CodexAiImplementation.modelCatalog().publish(catalogBefore);
        CodexAiImplementation.modelCatalog().invalidate();
        CodexPluginSettings.useForTests(null);
    }

    @Test
    @Timeout(10)
    void theLiveCatalogWinsOverThePersistedList() {
        CodexPluginSettings.setDiscoveredModels(List.of("persisted-x"));
        CodexAiImplementation.modelCatalog().publish(List.of("live-a"));

        assertArrayEquals(new String[]{"live-a"}, CodexPluginSettings.getKnownModels());
    }

    @Test
    @Timeout(10)
    void thePersistedListIsUsedWhenTheCatalogIsEmpty() {
        CodexPluginSettings.setDiscoveredModels(List.of("persisted-x"));
        CodexAiImplementation.modelCatalog().publish(List.of());

        assertArrayEquals(new String[]{"persisted-x"}, CodexPluginSettings.getKnownModels());
    }

    @Test
    @Timeout(10)
    void nothingKnownGivesAnEmptyArray() {
        CodexPluginSettings.setDiscoveredModels(List.of());
        CodexAiImplementation.modelCatalog().publish(List.of());

        assertEquals(0, CodexPluginSettings.getKnownModels().length);
    }

    @Test
    @Timeout(10)
    void anOverlongListIsTrimmedInsteadOfThrowing() {
        List<String> many = IntStream.range(0, 2000)
                .mapToObj(i -> String.format("model-%04d-xxxxxxxxxxxxxxxxxxxx", i))
                .toList();

        assertDoesNotThrow(() -> CodexPluginSettings.setDiscoveredModels(many));

        List<String> kept = CodexPluginSettings.getDiscoveredModels();
        assertFalse(kept.isEmpty(), "some leading entries must still fit");
        assertTrue(kept.size() < many.size(), "the list must have been trimmed to fit the preference limit");
        assertEquals(many.subList(0, kept.size()), kept, "the kept entries are the leading ones");
    }
}
