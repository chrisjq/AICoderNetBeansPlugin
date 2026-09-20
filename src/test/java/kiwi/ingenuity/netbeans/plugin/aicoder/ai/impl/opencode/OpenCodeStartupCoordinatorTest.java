package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OpenCodeStartupCoordinatorTest {

    @Test
    void matchingVersionDoesNotCoordinate() throws Exception {
        Path database = Files.createTempDirectory("opencode-startup-").resolve("opencode.db");
        Files.writeString(database, "database");
        OpenCodeStartupCoordinator.clearStoredVersionForTests(database);
        try (OpenCodeStartupCoordinator leader = OpenCodeStartupCoordinator.acquire(database, "2.1.0", 1L)) {
            assertTrue(leader.isLeader());
            leader.recordSuccessfulStart();
        }
        try (OpenCodeStartupCoordinator coordinator = OpenCodeStartupCoordinator.acquire(database, "2.1.0", 1L)) {
            assertFalse(coordinator.isLeader(), "a matching persisted version must skip lock coordination");
        }
    }

    @Test
    void missingDatabaseCoordinatesEvenWhenTheStoredVersionMatches() throws Exception {
        Path database = Files.createTempDirectory("opencode-startup-").resolve("opencode.db");
        OpenCodeStartupCoordinator.clearStoredVersionForTests(database);
        try (OpenCodeStartupCoordinator firstLeader = OpenCodeStartupCoordinator.acquire(database, "2.1.0", 1L)) {
            assertTrue(firstLeader.isLeader());
            firstLeader.recordSuccessfulStart();
        }

        try (OpenCodeStartupCoordinator freshDatabaseLeader = OpenCodeStartupCoordinator.acquire(database, "2.1.0", 1L)) {
            assertTrue(freshDatabaseLeader.isLeader(),
                       "a missing database must coordinate its first schema migration despite a stored version");
        }
    }

    @Test
    void changedVersionLeaderRecordsOnlyAfterSuccessfulStart() throws Exception {
        Path database = Files.createTempDirectory("opencode-startup-").resolve("opencode.db");
        Files.writeString(database, "database");
        OpenCodeStartupCoordinator.clearStoredVersionForTests(database);

        try (OpenCodeStartupCoordinator leader = OpenCodeStartupCoordinator.acquire(database, "2.1.0", 1L)) {
            assertTrue(leader.isLeader());
            leader.recordSuccessfulStart();
        }
        try (OpenCodeStartupCoordinator follower = OpenCodeStartupCoordinator.acquire(database, "2.1.0", 1L)) {
            assertFalse(follower.isLeader());
        }
    }

    @Test
    void failedLeaderLeavesVersionUnmarkedForTheNextStart() throws Exception {
        Path database = Files.createTempDirectory("opencode-startup-").resolve("opencode.db");
        OpenCodeStartupCoordinator.clearStoredVersionForTests(database);

        try (OpenCodeStartupCoordinator failedLeader = OpenCodeStartupCoordinator.acquire(database, "2.1.0", 1L)) {
            assertTrue(failedLeader.isLeader());
        }
        try (OpenCodeStartupCoordinator retry = OpenCodeStartupCoordinator.acquire(database, "2.1.0", 1L)) {
            assertTrue(retry.isLeader());
        }
    }

    @Test
    void contendedFileLockFailsOpenAfterTheBound() throws Exception {
        Path database = Files.createTempDirectory("opencode-startup-").resolve("opencode.db");
        Path lockPath = database.resolveSibling("opencode.db.aicoder-start.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock(); OpenCodeStartupCoordinator contender = OpenCodeStartupCoordinator.acquire(database, "2.1.0", 1L)) {
            assertFalse(contender.isLeader(), "a held OS file lock must not hang another IDE session");
        }
    }
}
