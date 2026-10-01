package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiModelCatalogTest {

    private static final AiTypeEnum TYPE = AiTypeEnum.PI;

    private static AvailableModelsEvent next(BlockingQueue<AiPropertyEvent> received) throws InterruptedException {
        AiPropertyEvent event = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(event, "no event reached the bus listener");
        return (AvailableModelsEvent) event;
    }

    @Test
    void publishFiresAvailableModelsOnTheBusOnlyWhenTheListChanged() throws Exception {
        AiModelCatalog catalog = new AiModelCatalog(TYPE);
        BlockingQueue<AiPropertyEvent> received = new LinkedBlockingQueue<>();
        AiPropertyListener listener = received::add;
        AiTypePropertyBus.getInstance().addListener(TYPE, listener);
        try {
            assertTrue(catalog.publish(List.of("model-a", "model-b")));
            assertFalse(catalog.publish(List.of("model-a", "model-b")));
            assertTrue(catalog.publish(List.of("model-c")));

            assertEquals(List.of("model-a", "model-b"), next(received).models(),
                    "first publish must reach the bus");
            assertEquals(List.of("model-c"), next(received).models(),
                    "unchanged publish must not fire, so the next event is the changed list");
        }
        finally {
            AiTypePropertyBus.getInstance().removeListener(TYPE, listener);
        }
    }

    @Test
    void publishFiresOnlyForTheCatalogsOwnType() throws Exception {
        AiModelCatalog catalog = new AiModelCatalog(TYPE);
        BlockingQueue<AiPropertyEvent> otherTypeReceived = new LinkedBlockingQueue<>();
        BlockingQueue<AiPropertyEvent> ownTypeReceived = new LinkedBlockingQueue<>();
        AiPropertyListener otherListener = otherTypeReceived::add;
        AiPropertyListener ownListener = ownTypeReceived::add;
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.GROK, otherListener);
        AiTypePropertyBus.getInstance().addListener(TYPE, ownListener);
        try {
            catalog.publish(List.of("model-a"));

            assertEquals(List.of("model-a"), next(ownTypeReceived).models());
            assertTrue(otherTypeReceived.isEmpty(), "another AI type's listener must not see this catalog's models");
        }
        finally {
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.GROK, otherListener);
            AiTypePropertyBus.getInstance().removeListener(TYPE, ownListener);
        }
    }

    @Test
    void cachedModelsHoldTheLastPublishedList() {
        AiModelCatalog catalog = new AiModelCatalog(TYPE);
        assertTrue(catalog.getCachedModels().isEmpty());

        catalog.publish(List.of("model-a", "model-b"));

        assertEquals(List.of("model-a", "model-b"), catalog.getCachedModels());
    }

    @Test
    void coalescesRefreshUntilSuccessOrFailure() {
        AiModelCatalog catalog = new AiModelCatalog(TYPE);

        assertTrue(catalog.beginRefresh());
        assertFalse(catalog.beginRefresh());
        catalog.refreshFailed();
        assertTrue(catalog.beginRefresh());
    }
}
