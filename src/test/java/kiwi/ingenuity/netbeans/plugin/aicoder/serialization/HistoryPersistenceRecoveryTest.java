package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery state machine for corrupt history files (review fix 15, watchlist 13): quarantine only after
 * stable-corruption confirmation across repeated identical reads; retain untouched when reads disagree; never destroy
 * bytes without a recoverable copy.
 */
class HistoryPersistenceRecoveryTest {

    private static final String VALID_JSON
            = "{\"sessionId\":\"sid\",\"messages\":[{\"role\":\"USER\",\"text\":\"hello\",\"timestamp\":1000}]}";
    private static final String CORRUPT_A = "{definitely not json";
    private static final String CORRUPT_B = "{\"truncated\":";

    @TempDir
    Path tmp;

    private Path historyFile() {
        return tmp.resolve("history.json");
    }

    private List<Path> siblingsMatching(String fragment) throws IOException {
        try (Stream<Path> entries = Files.list(tmp)) {
            return entries
                    .filter(p -> p.getFileName().toString().contains(fragment))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Reader that serves scripted values per call; an IOException is simulated by returning null.
     */
    private void scriptReader(HistoryPersistenceManager mgr, IntFunction<String> script) {
        AtomicInteger call = new AtomicInteger();
        mgr.setContentReader(path -> {
            String value = script.apply(call.incrementAndGet());
            if (value == null) {
                throw new IOException("simulated transient read failure");
            }
            return value;
        });
    }

    @Test
    void stableCorruption_quarantinesOriginalAndStartsFresh() throws IOException {
        Path file = historyFile();
        Files.writeString(file, CORRUPT_A);
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);

        assertTrue(mgr.load().messages().isEmpty());

        assertFalse(Files.exists(file), "stably corrupt original must be moved aside");
        List<Path> quarantined = siblingsMatching(".corrupt-");
        assertEquals(1, quarantined.size(), "exactly one quarantine artifact");
        assertEquals(CORRUPT_A, Files.readString(quarantined.get(0)),
                "quarantine must preserve the exact original bytes");
        assertTrue(mgr.load().messages().isEmpty(), "next load starts clean with no file");
        assertEquals(1, siblingsMatching(".corrupt-").size(), "no duplicate quarantine artifacts");
    }

    @Test
    void transientIoFailure_thenGoodRead_loadsWithoutQuarantine() throws IOException {
        Path file = historyFile();
        Files.writeString(file, VALID_JSON);
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        scriptReader(mgr, call -> call == 1 ? null : VALID_JSON);

        var loaded = mgr.load();

        assertEquals(1, loaded.messages().size());
        assertEquals("hello", loaded.messages().get(0).markdownText());
        assertEquals("sid", loaded.sessionId());
        assertTrue(Files.exists(file), "file must survive a transient failure");
        assertTrue(siblingsMatching(".corrupt-").isEmpty(), "transient failure must never sideline data");
    }

    @Test
    void transientTruncation_thenFullRead_loadsWithoutQuarantine() throws IOException {
        Path file = historyFile();
        Files.writeString(file, VALID_JSON);
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        scriptReader(mgr, call -> call == 1 ? "{" : VALID_JSON);

        var loaded = mgr.load();

        assertEquals(1, loaded.messages().size());
        assertTrue(siblingsMatching(".corrupt-").isEmpty());
        assertEquals(VALID_JSON, Files.readString(file));
    }

    @Test
    void differingUnparseableReads_retainFileWithoutQuarantine() throws IOException {
        Path file = historyFile();
        Files.writeString(file, CORRUPT_A);
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        scriptReader(mgr, call -> call % 2 == 1 ? CORRUPT_A : CORRUPT_B);

        assertTrue(mgr.load().messages().isEmpty());

        assertTrue(Files.exists(file), "unstable corruption retains the original file");
        assertEquals(CORRUPT_A, Files.readString(file), "retained bytes must be untouched by load");
        assertTrue(siblingsMatching(".corrupt-").isEmpty(),
                "unproven corruption must not be quarantined");

        // Retention policy: the next non-empty save copies the unreadable bytes aside first.
        mgr.save(List.of(AiMessage.user("fresh")), "newSid", null);
        List<Path> retainedCopies = siblingsMatching(".unreadable-");
        assertEquals(1, retainedCopies.size());
        assertEquals(CORRUPT_A, Files.readString(retainedCopies.get(0)),
                "the pre-overwrite copy preserves whatever was on disk");
        // A fresh reader (as a later process would see it) loads the overwritten file cleanly.
        assertTrue(new HistoryPersistenceManager(file).load().messages().size() == 1,
                "save then loads back cleanly");

        // Retention flag cleared after the one-time copy — no further artifacts accumulate.
        mgr.save(List.of(AiMessage.user("more"), AiMessage.assistant("ok")), "newSid", null);
        assertEquals(1, siblingsMatching(".unreadable-").size());
    }

    @Test
    void singleUnparseableReadFollowedByIoFailures_retainsWithoutQuarantine() throws IOException {
        Path file = historyFile();
        Files.writeString(file, CORRUPT_A);
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        scriptReader(mgr, call -> call == 1 ? CORRUPT_A : null);

        assertTrue(mgr.load().messages().isEmpty());

        assertTrue(Files.exists(file));
        assertEquals(CORRUPT_A, Files.readString(file));
        assertTrue(siblingsMatching(".corrupt-").isEmpty(),
                "one snapshot can never confirm stable corruption");
    }

    @Test
    void allAttemptsFailWithIo_loadThrowsAndLeavesFileAlone() throws IOException {
        Path file = historyFile();
        Files.writeString(file, VALID_JSON);
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        scriptReader(mgr, call -> null);

        assertThrows(IOException.class, mgr::load);

        assertTrue(Files.exists(file));
        assertEquals(VALID_JSON, Files.readString(file));
        assertTrue(siblingsMatching(".corrupt-").isEmpty());
        assertTrue(siblingsMatching(".unreadable-").isEmpty(),
                "a load that never parsed must not arm retention");
    }

    @Test
    void saveAfterStableQuarantine_recoversCleanly() throws IOException {
        Path file = historyFile();
        Files.writeString(file, CORRUPT_A);
        HistoryPersistenceManager mgr = new HistoryPersistenceManager(file);
        mgr.load();

        mgr.save(List.of(AiMessage.user("reborn")), "sid2", null);

        assertTrue(siblingsMatching(".unreadable-").isEmpty(),
                "stable path quarantines already; no extra retention copy needed");
        var reloaded = new HistoryPersistenceManager(file).load();
        assertEquals(1, reloaded.messages().size());
        assertEquals("reborn", reloaded.messages().get(0).markdownText());
        assertEquals("sid2", reloaded.sessionId());
    }
}
