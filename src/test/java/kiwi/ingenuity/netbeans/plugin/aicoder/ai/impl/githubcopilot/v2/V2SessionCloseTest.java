package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v2;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class V2SessionCloseTest {

    @Test
    void closingTwiceRunsTheSessionCloseOnce() {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();

        V2Session.closeOnce(closed, closes::incrementAndGet);
        V2Session.closeOnce(closed, closes::incrementAndGet);

        assertEquals(1, closes.get());
    }
}
