package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netbeans.libs.git.GitMergeResult;
import org.netbeans.libs.git.GitRefUpdateResult;

class GitProviderTest {

    @Test
    void commitTargets_followRestrictToProjectSetting(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path sibling = Files.createFile(tmp.resolve("sibling.txt"));
        McpHookServer server = new McpHookServer(0);
        server.init();
        try {
            server.registerSession("restricted", CLAUDE, List.of(project.toFile()), true);
            assertTrue(GitProvider.areCommitTargetsAllowed(
                    new File[]{project.toFile()}, server, "restricted"));
            assertFalse(GitProvider.areCommitTargetsAllowed(
                    new File[]{sibling.toFile()}, server, "restricted"));

            server.registerSession("unrestricted", CLAUDE, List.of(project.toFile()), false);
            assertTrue(GitProvider.areCommitTargetsAllowed(
                    new File[]{sibling.toFile()}, server, "unrestricted"));
        }
        finally {
            server.stop();
        }
    }

    @Test
    void formatTransportResults_reportsRejectedPushAsFailure() {
        String result = GitProvider.formatTransportResults(
                "Push", Map.of("refs/heads/main", GitRefUpdateResult.REJECTED_NONFASTFORWARD));

        assertEquals("Push failed:\n  refs/heads/main: REJECTED_NONFASTFORWARD", result);
    }

    @Test
    void formatTransportResults_reportsMixedFetchAsPartial() {
        String result = GitProvider.formatTransportResults(
                "Fetch",
                Map.of(
                        "refs/heads/main", GitRefUpdateResult.FAST_FORWARD,
                        "refs/heads/old", GitRefUpdateResult.REJECTED));

        assertTrue(result.startsWith("Fetch partially completed:"), result);
        assertTrue(result.contains("FAST_FORWARD"), result);
        assertTrue(result.contains("REJECTED"), result);
    }

    @Test
    void successfulMergeStatuses_excludeConflictsAndFailures() {
        assertTrue(GitProvider.isSuccessfulMergeStatus(GitMergeResult.MergeStatus.MERGED));
        assertTrue(GitProvider.isSuccessfulMergeStatus(GitMergeResult.MergeStatus.ALREADY_UP_TO_DATE));
        assertFalse(GitProvider.isSuccessfulMergeStatus(GitMergeResult.MergeStatus.CONFLICTING));
        assertFalse(GitProvider.isSuccessfulMergeStatus(GitMergeResult.MergeStatus.FAILED));
    }

    /**
     * refreshVcsStatus resolves FileOwnerQuery.getOwner(), which throws
     * ExceptionInInitializerError/NoClassDefFoundError in this isolated unit-test JVM (no ProjectManagerImplementation
     * registered in global Lookup) — the same condition that used to make refreshVcsStatus throw an uncaught Error out
     * to every caller. It is now guarded inside this method itself, so every caller (the best-effort refresh after
     * RefactoringProvider's write/edit/delete/copy/move, and RefreshFileStatusTool, where the refresh IS the operation
     * and needs a real reportable result) gets a string back instead of a crash, with no per-caller guarding required.
     */
    @Test
    void refreshVcsStatus_returnsAResultInsteadOfThrowingWhenProjectManagerLookupIsUnavailable(@TempDir Path tmp) throws Exception {
        Path file = Files.createFile(tmp.resolve("refresh-target.txt"));

        String result = GitProvider.refreshVcsStatus(file.toString());

        assertTrue(result != null && !result.isBlank(), "must return a non-blank result, not throw: " + result);
    }
}
