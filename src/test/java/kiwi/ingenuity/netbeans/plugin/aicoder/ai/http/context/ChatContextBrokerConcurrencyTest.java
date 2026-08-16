package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ChatContextBrokerConcurrencyTest {

    private static ChatMessage user(String t) {
        return new ChatMessage(ChatRole.USER, t, List.of(), null);
    }

    @Test
    void concurrentReadersNeverSeeAHalfAppliedState() throws Exception {
        TestBroker b = new TestBroker();
        b.upsertPin(PinSlotEnum.IDENTITY, "ID");

        int writers = 4;
        int perWriter = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + 2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int w = 0; w < writers; w++) {
            final int id = w;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        b.append(user("w" + id + "-" + i));
                    }
                }
                catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
                finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        for (int r = 0; r < 2; r++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        List<ChatMessage> snap = b.snapshot();
                        for (ChatMessage m : snap) {
                            if (m.role() == null) {
                                throw new IllegalStateException("torn message in snapshot");
                            }
                        }
                        b.estimatedTokenTotal();
                        b.entryCount();
                    }
                }
                catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
                finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish — possible deadlock");
        assertNull(failure.get(), () -> "concurrent access failed: " + failure.get());
        assertEquals(writers * perWriter, b.entryCount());
    }

    @Test
    void aSnapshotTakenBeforeAMutationIsUnaffectedByIt() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(user("before"));
        b.commitTurn();

        List<ChatMessage> snap = b.snapshot();
        b.clearHistory();
        b.append(user("after"));

        assertEquals(1, snap.size());
        assertEquals("before", snap.get(0).content(),
                "snapshot() must hand out copies, not live references");
    }

    static class TestBroker extends AbstractChatContextBroker {

        TestBroker() {
            super("concurrency-session", ContextBrokerSettings.defaults());
        }

        @Override
        protected int contextLimit() {
            return 100000;
        }
    }
}
