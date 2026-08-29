package kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFile;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFileRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the PRODUCTION layout branch ({@code {configRoot}/{type}/{sessionId}/tmp/}), which {@link TempFileRegistryTest}
 * cannot reach because its tests run through {@code overrideBasePath}.
 *
 * <p>
 * Regression context: this branch once used {@code resolveSibling("tmp")} on the session config dir, silently producing
 * {@code {configRoot}/{type}/tmp} — one shared root for every session of a type, so {@code cleanupSession} wiped other
 * live sessions' pastes/tool_results and created files fell outside the {@code isOwnSessionConfigFile} scope exemption.
 * All override-branch tests stayed green through that bug; these tests exist so it can never happen again unnoticed.
 */
class TempFileRegistryProductionLayoutTest {

    @TempDir
    Path base;

    private Path typeDir;
    private Path configDirA;
    private Path configDirB;

    @BeforeEach
    void setUp() {
        TempFileRegistry.resetForTests();
        // Mirrors production SessionFileScopeRegistry.sessionConfigDirOrNull:
        // ~/.ai-coder/{type}/{sessionId}/ — two sessions of the SAME type get sibling dirs.
        typeDir = base.resolve("claude");
        configDirA = typeDir.resolve("ses-a");
        configDirB = typeDir.resolve("ses-b");
        TempFileRegistry.overrideConfigDirResolverForTests(sessionId -> {
            if ("ses-a".equals(sessionId)) {
                return configDirA;
            }
            if ("ses-b".equals(sessionId)) {
                return configDirB;
            }
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        TempFileRegistry.resetForTests();
    }

    private static AiSession session(String id) {
        return new AiSession(id, "T", null, AiTypeEnum.CLAUDE, null, null, Instant.now(), Instant.now());
    }

    @Test
    void productionLayout_rootIsInsideOwnSessionConfigDir_notASharedTypeLevelTmp() throws Exception {
        TempFile a = TempFileRegistry.createTempFile(session("ses-a"), "paste", ".png");
        assertNotNull(a);

        Path expectedRoot = configDirA.resolve("tmp");
        assertEquals(expectedRoot, TempFileRegistry.getSessionTempDir("ses-a"),
                "tmp root must be {configRoot}/{type}/{sessionId}/tmp — appended to the session's own config dir");
        assertTrue(a.path().startsWith(configDirA),
                "created files must sit INSIDE the session config dir so the "
                + "isOwnSessionConfigFile scope exemption covers them even under restrict-to-project");

        // The exact old bug: resolveSibling would have produced {type}/tmp instead.
        assertNotEquals(typeDir.resolve("tmp"), TempFileRegistry.getSessionTempDir("ses-a"),
                "the root must never collapse to a per-type tmp dir shared by all sessions");
    }

    @Test
    void cleanupSession_removesOnlyTheOwner_evenWhenSessionsShareTheSameTypeDirectory() throws Exception {
        TempFile a = TempFileRegistry.createTempFile(session("ses-a"), "paste", ".png");
        TempFile b = TempFileRegistry.createTempFile(session("ses-b"), "paste", ".png");
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(Files.exists(b.path()));

        TempFileRegistry.cleanupSession("ses-a");

        assertTrue(Files.notExists(a.path()), "session A's own temp file goes");
        assertNull(TempFileRegistry.getSessionTempDir("ses-a"), "session A's root is untracked");
        assertTrue(Files.exists(b.path()),
                "session B's temp file MUST survive A's cleanup — the shared-per-type-root regression wiped it");
        assertEquals(configDirB.resolve("tmp"), TempFileRegistry.getSessionTempDir("ses-b"));
    }

    @Test
    void unknownSession_noConfigDir_returnsNull_likeProductionFailurePolicy() throws Exception {
        assertNull(TempFileRegistry.createTempFile(session("ses-unknown"), "paste", ".png"));
        assertEquals(0, TempFileRegistry.trackedFileCount());
    }
}
