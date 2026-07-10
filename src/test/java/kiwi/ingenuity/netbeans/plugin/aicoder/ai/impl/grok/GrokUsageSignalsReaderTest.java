package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokTokenUsageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in {@link GrokUsageSignalsReader} against the real on-disk
 * {@code ~/.grok/sessions/<encoded-cwd>/<sessionId>/signals.json} layout
 * (empirically confirmed against a live installed grok CLI, see class
 * Javadoc), including the symlink/canonical-path bug fixed in this class and
 * the new {@link GrokUsageSignalsReader#sessionExists} lookup that backs the
 * Grok resume-session-lifecycle fix in {@code GrokAiImplementation}.
 *
 * <p>
 * {@code user.home} is temporarily redirected to a JUnit {@code @TempDir} for
 * the duration of each test so these tests never touch the real
 * {@code ~/.grok} directory.
 */
class GrokUsageSignalsReaderTest {

    private String originalUserHome;

    @TempDir
    Path tempHome;

    @BeforeEach
    void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void read_missingSignalsFile_returnsNull() throws IOException {
        File workDir = Files.createDirectory(tempHome.resolve("project")).toFile();
        GrokTokenUsageEvent event = GrokUsageSignalsReader.read(workDir, UUID.randomUUID().toString(), "grok-4.5");
        assertNull(event);
    }

    @Test
    void read_malformedJson_returnsNullInsteadOfThrowing() throws IOException {
        File workDir = Files.createDirectory(tempHome.resolve("project")).toFile();
        String sessionId = UUID.randomUUID().toString();
        writeSignals(workDir, sessionId, "{not valid json");
        assertNull(GrokUsageSignalsReader.read(workDir, sessionId, "grok-4.5"));
    }

    @Test
    void read_validSignals_parsesUsageAndModel() throws IOException {
        File workDir = Files.createDirectory(tempHome.resolve("project")).toFile();
        String sessionId = UUID.randomUUID().toString();
        writeSignals(workDir, sessionId,
                "{\"contextTokensUsed\":5606,\"contextWindowTokens\":500000,\"primaryModelId\":\"grok-4.5\",\"turnCount\":2}");
        GrokTokenUsageEvent event = GrokUsageSignalsReader.read(workDir, sessionId, "fallback-model");
        assertEquals(5606, event.currentTokens());
        assertEquals(500000, event.maxTokens());
        assertEquals("grok-4.5", event.model());
    }

    @Test
    void read_missingPrimaryModelId_fallsBackToPassedModel() throws IOException {
        File workDir = Files.createDirectory(tempHome.resolve("project")).toFile();
        String sessionId = UUID.randomUUID().toString();
        writeSignals(workDir, sessionId, "{\"contextTokensUsed\":10,\"contextWindowTokens\":100}");
        GrokTokenUsageEvent event = GrokUsageSignalsReader.read(workDir, sessionId, "fallback-model");
        assertEquals("fallback-model", event.model());
    }

    @Test
    void read_negativeOrZeroUsageFields_returnsNull() throws IOException {
        File workDir = Files.createDirectory(tempHome.resolve("project")).toFile();
        String sessionId = UUID.randomUUID().toString();
        writeSignals(workDir, sessionId, "{\"contextTokensUsed\":5,\"contextWindowTokens\":0}");
        assertNull(GrokUsageSignalsReader.read(workDir, sessionId, "m"));
    }

    @Test
    void read_nullArguments_returnsNullWithoutThrowing() {
        assertNull(GrokUsageSignalsReader.read(null, "id", "m"));
        assertNull(GrokUsageSignalsReader.read(new File("/tmp"), null, "m"));
        assertNull(GrokUsageSignalsReader.read(new File("/tmp"), "", "m"));
    }

    @Test
    void sessionExists_noSessionsDirectory_returnsFalse() {
        assertFalse(GrokUsageSignalsReader.sessionExists(UUID.randomUUID().toString()));
    }

    @Test
    void sessionExists_matchingSessionDirNestedUnderAnyCwd_returnsTrue() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        Path sessionsRoot = tempHome.resolve(".grok").resolve("sessions");
        Files.createDirectories(sessionsRoot.resolve("%2Fsome%2Fencoded%2Fcwd").resolve(sessionId));
        assertTrue(GrokUsageSignalsReader.sessionExists(sessionId));
    }

    @Test
    void sessionExists_noMatchingSessionDir_returnsFalse() throws IOException {
        Files.createDirectories(tempHome.resolve(".grok").resolve("sessions").resolve("%2Fsome%2Fcwd")
                .resolve(UUID.randomUUID().toString()));
        assertFalse(GrokUsageSignalsReader.sessionExists(UUID.randomUUID().toString()));
    }

    @Test
    void sessionExists_blankOrNullId_returnsFalse() {
        assertFalse(GrokUsageSignalsReader.sessionExists(null));
        assertFalse(GrokUsageSignalsReader.sessionExists(""));
        assertFalse(GrokUsageSignalsReader.sessionExists("   "));
    }

    private void writeSignals(File workDir, String sessionId, String json) throws IOException {
        // Mirrors GrokUsageSignalsReader's own encodeCwd()/canonical-path logic
        // (URLEncoder with '+' -> '%20') so the file lands exactly where the
        // reader under test will look for it.
        String encodedCwd = java.net.URLEncoder.encode(workDir.getCanonicalPath(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        Path dir = Path.of(tempHome.toString(), ".grok", "sessions", encodedCwd, sessionId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("signals.json"), json, StandardCharsets.UTF_8);
    }
}
