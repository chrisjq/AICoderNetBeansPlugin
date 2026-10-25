package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleLimitsTest {

    @Test
    @Timeout(5)
    void defaultsMatchTheSpecTable() {
        var d = OpenAiCompatibleLimits.defaults();
        assertEquals(Duration.ofSeconds(10), d.connectTimeout());
        assertEquals(Duration.ofSeconds(600), d.firstByteTimeout());
        assertEquals(Duration.ofSeconds(120), d.idleTimeout());
        assertEquals(Duration.ofMinutes(60), d.totalCap());
        assertEquals(Duration.ofSeconds(1), d.checkInterval());
        assertEquals(Duration.ofMinutes(5), d.abandonedToolWait());
        assertEquals(Duration.ofSeconds(3), d.probeTimeout());
        assertEquals(Duration.ofSeconds(60), d.redetectInterval());
        assertEquals(1L << 20, d.probeBodyCapBytes());
        assertEquals(64L << 20, d.transcriptCapBytes());
        assertEquals(1L << 20, d.toolArgumentCapBytes());
        assertEquals(1, d.transportRetries());
        assertEquals(Duration.ofSeconds(2), d.retryBackoff());
        assertEquals(Duration.ofSeconds(30), d.retryAfterCap());
    }

    @Test
    @Timeout(5)
    void clampsToTheSpecFloors() {
        var d = OpenAiCompatibleLimits.defaults()
                .withConnectTimeout(Duration.ZERO)
                .withTotalCap(Duration.ofSeconds(1))
                .withTranscriptCapBytes(10);
        assertEquals(Duration.ofSeconds(1), d.connectTimeout());
        assertTrue(d.totalCap().compareTo(d.firstByteTimeout()) > 0);
        assertTrue(d.totalCap().compareTo(d.idleTimeout()) > 0);
        assertEquals(1L << 20, d.transcriptCapBytes());
    }

    @Test
    @Timeout(5)
    void idleZeroMeansOff() {
        assertTrue(OpenAiCompatibleLimits.defaults().withIdleTimeout(Duration.ZERO).idleTimeoutOff());
    }
}
