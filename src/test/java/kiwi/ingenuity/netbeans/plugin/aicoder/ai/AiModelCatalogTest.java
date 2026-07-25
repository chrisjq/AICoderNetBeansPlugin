package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiModelCatalogTest {

    @Test
    void publishesOnlyChangedListsAndReplaysCachedModelsToNewListeners() {
        AiModelCatalog catalog = new AiModelCatalog();
        List<List<String>> received = new ArrayList<>();
        Consumer<List<String>> listener = received::add;
        catalog.addListener(listener);

        assertTrue(catalog.publish(List.of("model-a", "model-b")));
        assertFalse(catalog.publish(List.of("model-a", "model-b")));
        assertEquals(List.of(List.of("model-a", "model-b")), received);

        List<List<String>> replayed = new ArrayList<>();
        catalog.addListener(replayed::add);
        assertEquals(List.of(List.of("model-a", "model-b")), replayed);
    }

    @Test
    void coalescesRefreshUntilSuccessOrFailure() {
        AiModelCatalog catalog = new AiModelCatalog();

        assertTrue(catalog.beginRefresh());
        assertFalse(catalog.beginRefresh());
        catalog.refreshFailed();
        assertTrue(catalog.beginRefresh());
    }
}
