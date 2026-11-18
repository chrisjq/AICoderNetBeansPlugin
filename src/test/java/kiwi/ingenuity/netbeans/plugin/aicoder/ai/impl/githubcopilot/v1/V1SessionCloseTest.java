package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class V1SessionCloseTest {

    @Test
    void closingTwiceRunsTheSessionCloseOnce() {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();

        V1Session.closeOnce(closed, closes::incrementAndGet);
        V1Session.closeOnce(closed, closes::incrementAndGet);

        assertEquals(1, closes.get());
    }
}
