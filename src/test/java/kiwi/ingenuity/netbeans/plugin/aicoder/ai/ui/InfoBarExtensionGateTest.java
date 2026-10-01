package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The core's one door into an info bar extension: every call lands on the EDT whichever thread made it.
 */
class InfoBarExtensionGateTest {

    private static final long WAIT_SECONDS = 10;

    private static class RecordingExtension implements AiInfoBarExtension {

        final AtomicBoolean propertyOnEdt = new AtomicBoolean();
        final AtomicBoolean disposeOnEdt = new AtomicBoolean();
        final AtomicInteger disposed = new AtomicInteger();
        final CountDownLatch propertySeen = new CountDownLatch(1);
        final CountDownLatch disposeSeen = new CountDownLatch(1);

        @Override
        public List<JComponent> createComponents() {
            return List.of();
        }

        @Override
        public void onPropertyEvent(AiPropertyEvent event) {
            propertyOnEdt.set(SwingUtilities.isEventDispatchThread());
            propertySeen.countDown();
        }

        @Override
        public void onAiProcessImplEvent(AiProcessImplEvent event) {
        }

        @Override
        public void dispose() {
            disposeOnEdt.set(SwingUtilities.isEventDispatchThread());
            disposed.incrementAndGet();
            disposeSeen.countDown();
        }
    }

    private static Thread offEdt(Runnable action) {
        Thread t = new Thread(action, "not-the-edt");
        t.start();
        return t;
    }

    @Test
    void aCallMadeOffTheEdtIsDeliveredOnTheEdt() throws Exception {
        InfoBarExtensionGate gate = new InfoBarExtensionGate();
        RecordingExtension ext = new RecordingExtension();
        gate.install(ext);

        offEdt(() -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            gate.deliver(e -> e.onPropertyEvent(null));
        }).join();

        assertTrue(ext.propertySeen.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(ext.propertyOnEdt.get(), "the extension must be called on the EDT, not the caller's thread");
    }

    @Test
    void aCallMadeOnTheEdtRunsInlineSoOrderingWithOtherEdtWorkIsKept() throws Exception {
        InfoBarExtensionGate gate = new InfoBarExtensionGate();
        RecordingExtension ext = new RecordingExtension();
        gate.install(ext);
        List<String> order = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> {
            order.add("before");
            gate.deliver(e -> order.add("delivered"));
            order.add("after");
        });

        assertEquals(List.of("before", "delivered", "after"), order);
    }

    @Test
    void aCallQueuedBeforeDisposeFindsNothingWhenItRuns() throws Exception {
        InfoBarExtensionGate gate = new InfoBarExtensionGate();
        RecordingExtension ext = new RecordingExtension();
        gate.install(ext);
        AtomicInteger delivered = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            try {
                offEdt(() -> gate.deliver(e -> delivered.incrementAndGet())).join();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            gate.dispose();
        });
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals(0, delivered.get(), "the extension must be looked up when the call runs, not when queued");
        assertEquals(1, ext.disposed.get());
    }

    @Test
    void disposeFromOffTheEdtDisposesOnTheEdtExactlyOnce() throws Exception {
        InfoBarExtensionGate gate = new InfoBarExtensionGate();
        RecordingExtension ext = new RecordingExtension();
        gate.install(ext);

        offEdt(gate::dispose).join();
        assertTrue(ext.disposeSeen.await(WAIT_SECONDS, TimeUnit.SECONDS));
        offEdt(gate::dispose).join();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertTrue(ext.disposeOnEdt.get(), "dispose must run on the EDT");
        assertEquals(1, ext.disposed.get(), "a second dispose must find the extension already detached");
    }

    @Test
    void aFailingExtensionDisposeDoesNotEscape() throws Exception {
        InfoBarExtensionGate gate = new InfoBarExtensionGate();
        gate.install(new RecordingExtension() {
            @Override
            public void dispose() {
                throw new IllegalStateException("boom");
            }
        });

        SwingUtilities.invokeAndWait(gate::dispose);
    }

    @Test
    void deliveringWithNothingInstalledIsANoOp() throws Exception {
        InfoBarExtensionGate gate = new InfoBarExtensionGate();
        AtomicInteger delivered = new AtomicInteger();

        gate.deliver(e -> delivered.incrementAndGet());
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals(0, delivered.get());
    }
}
