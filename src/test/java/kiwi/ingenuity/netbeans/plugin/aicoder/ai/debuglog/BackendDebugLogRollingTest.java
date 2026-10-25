package kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Rolling generations (.1 .. .N, 0 = truncate) and the settable shutdown drain bound. Each entry is about 650 bytes
 * against a 1000-byte cap, so every write after the first rolls the file.
 */
@ResourceLock("aicoder-debuglog")
class BackendDebugLogRollingTest {

    private static final long CAP = 1000L;

    @AfterEach
    void tearDown() {
        BackendDebugLogWriter.pauseForTest(false);
        BackendDebugLogWriter.resetForTest();
        BackendDebugLogWriter.setShutdownDrainBound(BackendDebugLogWriter.SHUTDOWN_DRAIN_BOUND);
    }

    private static String entry(String marker) {
        return marker + "-" + "x".repeat(600);
    }

    @Test
    @Timeout(10)
    void twoRolledFilesAreKeptAndTheOldestIsDiscarded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("r.log");
        var log = BackendDebugLogWriter.open(file, DebugLogBudget.of(8L << 20, 10_000, 4096, CAP, 2));
        for (String marker : new String[]{"alpha", "bravo", "charlie", "delta"}) {
            log.log(DebugDirectionEnum.SENT, "k", entry(marker));
        }
        log.close().get(5, TimeUnit.SECONDS);

        assertTrue(Files.readString(file).contains("delta"), "live file holds the newest entry");
        assertTrue(Files.readString(dir.resolve("r.log.1")).contains("charlie"));
        assertTrue(Files.readString(dir.resolve("r.log.2")).contains("bravo"));
        assertFalse(Files.exists(dir.resolve("r.log.3")), "no third generation is kept");
        assertFalse(Files.readString(dir.resolve("r.log.2")).contains("alpha"), "the oldest was discarded");
    }

    @Test
    @Timeout(10)
    void zeroRolledFilesTruncatesInsteadOfRolling(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("t.log");
        var log = BackendDebugLogWriter.open(file, DebugLogBudget.of(8L << 20, 10_000, 4096, CAP, 0));
        log.log(DebugDirectionEnum.SENT, "k", entry("alpha"));
        log.log(DebugDirectionEnum.SENT, "k", entry("bravo"));
        log.close().get(5, TimeUnit.SECONDS);

        String text = Files.readString(file);
        assertTrue(text.contains("bravo"));
        assertFalse(text.contains("alpha"), "the older entry was truncated away");
        assertFalse(Files.exists(dir.resolve("t.log.1")));
    }

    @Test
    @Timeout(5)
    void theFourLimitFormKeepsOneRolledFile() {
        assertEquals(1, new DebugLogBudget(8L << 20, 10_000, 4096, CAP).rolledFiles());
        assertEquals(1, DebugLogBudget.of(8L << 20, 10_000, 4096L, CAP).rolledFiles());
        assertEquals(0, DebugLogBudget.of(8L << 20, 10_000, 4096L, CAP, -3).rolledFiles(), "negative counts as zero");
    }

    @Test
    @Timeout(5)
    void shutdownDrainBoundDefaultsToTheConstantAndCanBeSet() {
        assertEquals(BackendDebugLogWriter.SHUTDOWN_DRAIN_BOUND, BackendDebugLogWriter.shutdownDrainBound());

        BackendDebugLogWriter.setShutdownDrainBound(Duration.ofMillis(750));
        assertEquals(Duration.ofMillis(750), BackendDebugLogWriter.shutdownDrainBound());

        BackendDebugLogWriter.setShutdownDrainBound(Duration.ofSeconds(-1));
        assertEquals(Duration.ZERO, BackendDebugLogWriter.shutdownDrainBound(), "a negative bound counts as zero");
    }

    @Test
    @Timeout(5)
    void aNullShutdownDrainBoundIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BackendDebugLogWriter.setShutdownDrainBound(null));
    }
}
