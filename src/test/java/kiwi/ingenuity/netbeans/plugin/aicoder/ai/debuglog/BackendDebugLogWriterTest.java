package kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("aicoder-debuglog")
class BackendDebugLogWriterTest {

    @AfterEach
    void tearDown() {
        BackendDebugLogWriter.pauseForTest(false);
        BackendDebugLogWriter.resetForTest();
    }

    @Test
    @Timeout(10)
    void writesEntriesInOrderWithRedaction(@TempDir Path dir) throws Exception {
        var log = BackendDebugLogWriter.open(dir.resolve("backend-debug.log"), DebugLogBudget.defaults());
        log.registerSecret("hdr-secret-123");
        log.log(DebugDirectionEnum.SENT, "request", "{\"h\":\"hdr-secret-123\"}");
        log.log(DebugDirectionEnum.RECEIVED, "chunk", "hello");
        log.close().get(5, TimeUnit.SECONDS);
        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.indexOf("request") < text.indexOf("chunk"));
        assertFalse(text.contains("hdr-secret-123"));
        assertTrue(text.contains("[REDACTED]"));
    }

    @Test
    @Timeout(10)
    void overBudgetEntriesAreDroppedAndCounted(@TempDir Path dir) throws Exception {
        var budget = new DebugLogBudget(2500, 10_000, 600, 10L << 20);
        var log = BackendDebugLogWriter.open(dir.resolve("b.log"), budget);
        BackendDebugLogWriter.pauseForTest(true);
        for (int i = 0; i < 5; i++) {
            log.log(DebugDirectionEnum.SENT, "big", "x".repeat(500));
        }
        assertTrue(log.queuedBytes() <= 2500);
        BackendDebugLogWriter.pauseForTest(false);
        log.close().get(5, TimeUnit.SECONDS);
        assertEquals(0, log.queuedBytes());
        String text = Files.readString(dir.resolve("b.log"));
        assertEquals(2, text.split("big", -1).length - 1);
        assertTrue(text.contains("3 entries dropped"));
    }

    @Test
    @Timeout(10)
    void droppedCountIsWrittenOnCloseEvenWithNoLaterWrite(@TempDir Path dir) throws Exception {
        var log = BackendDebugLogWriter.open(dir.resolve("z.log"), new DebugLogBudget(10, 10_000, 600, 10L << 20));
        log.log(DebugDirectionEnum.SENT, "big", "x".repeat(500));
        log.close().get(5, TimeUnit.SECONDS);
        assertTrue(Files.readString(dir.resolve("z.log")).contains("1 entries dropped"));
    }

    @Test
    @Timeout(10)
    void closeCompletesEvenAfterAnIoError(@TempDir Path dir) throws Exception {
        var log = BackendDebugLogWriter.open(dir.resolve("e.log"), DebugLogBudget.defaults());
        BackendDebugLogWriter.failNextWriteForTest();
        log.log(DebugDirectionEnum.SENT, "k", "v");
        log.log(DebugDirectionEnum.SENT, "k2", "v2");
        log.close().get(5, TimeUnit.SECONDS);
        assertEquals(0, log.queuedBytes());
        var later = BackendDebugLogWriter.open(dir.resolve("f.log"), DebugLogBudget.defaults());
        later.log(DebugDirectionEnum.SENT, "alive", "1");
        later.close().get(5, TimeUnit.SECONDS);
        assertTrue(Files.readString(dir.resolve("f.log")).contains("alive"));
    }

    @Test
    @Timeout(10)
    void oversizedEntryIsTruncated(@TempDir Path dir) throws Exception {
        var log = BackendDebugLogWriter.open(dir.resolve("t.log"), new DebugLogBudget(8L << 20, 100, 100, 10L << 20));
        log.log(DebugDirectionEnum.RECEIVED, "huge", "y".repeat(10_000));
        log.close().get(5, TimeUnit.SECONDS);
        assertTrue(Files.size(dir.resolve("t.log")) < 1000);
    }

    @Test
    @Timeout(10)
    void rollsToDotOneAtTheCap(@TempDir Path dir) throws Exception {
        var log = BackendDebugLogWriter.open(dir.resolve("r.log"), new DebugLogBudget(8L << 20, 10_000, 4096, 2048));
        for (int i = 0; i < 10; i++) {
            log.log(DebugDirectionEnum.SENT, "k", "z".repeat(400));
        }
        log.close().get(5, TimeUnit.SECONDS);
        assertTrue(Files.exists(dir.resolve("r.log.1")));
        assertTrue(Files.size(dir.resolve("r.log")) <= 2048);
    }

    @Test
    @Timeout(10)
    void afterCloseEntriesAreDiscarded(@TempDir Path dir) throws Exception {
        var log = BackendDebugLogWriter.open(dir.resolve("c.log"), DebugLogBudget.defaults());
        log.log(DebugDirectionEnum.SENT, "before", "1");
        var closed = log.close();
        log.log(DebugDirectionEnum.SENT, "after", "2");
        closed.get(5, TimeUnit.SECONDS);
        String text = Files.readString(dir.resolve("c.log"));
        assertTrue(text.contains("before"));
        assertFalse(text.contains("after"));
    }

    @Test
    @Timeout(10)
    void deleteDuringHeavyLoggingRemovesBothFiles(@TempDir Path dir) throws Exception {
        var file = dir.resolve("d.log");
        var log = BackendDebugLogWriter.open(file, new DebugLogBudget(8L << 20, 10_000, 4096, 2048));
        var writer = new Thread(() -> {
            for (int i = 0; i < 5_000; i++) {
                log.log(DebugDirectionEnum.SENT, "k", "w".repeat(200));
            }
        });
        writer.start();
        log.closeAndDelete().get(5, TimeUnit.SECONDS);
        writer.join();
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("d.log.1")));
        assertEquals(0, log.queuedBytes());
    }

    @Test
    @Timeout(10)
    void drainIsBoundedAndLaterLogsAreSilentlyDropped(@TempDir Path dir) throws Exception {
        var log = BackendDebugLogWriter.open(dir.resolve("s.log"), DebugLogBudget.defaults());
        log.log(DebugDirectionEnum.EVENT, "stop", "x");
        long t0 = System.nanoTime();
        BackendDebugLogWriter.drain(Duration.ofSeconds(2));
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(3));
        log.log(DebugDirectionEnum.EVENT, "late", "y");
        assertTrue(Files.readString(dir.resolve("s.log")).contains("stop"));
    }

    @Test
    @Timeout(10)
    void logNeverBlocksTheCaller(@TempDir Path dir) throws Exception {
        var log = BackendDebugLogWriter.open(dir.resolve("n.log"), DebugLogBudget.defaults());
        BackendDebugLogWriter.pauseForTest(true);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            log.log(DebugDirectionEnum.SENT, "k", "v");
        }
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(2));
        BackendDebugLogWriter.pauseForTest(false);
        log.close().get(5, TimeUnit.SECONDS);
    }
}
