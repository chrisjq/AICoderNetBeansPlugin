package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ProjectPathParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Audit of the NON-MUTATING git MCP tools. Proves every schema-advertised parameter actually changes the
 * tool's behaviour by invoking {@code handle()} against throwaway repositories created with the real
 * {@code git} CLI. Never touches the plugin's own repository: {@code projectPath} always points at a per-test
 * temp dir which is deleted by the test harness. The mutating actions of branch/remote/tag (create/delete,
 * add/remove) are covered by {@code GitMutatingToolsAuditTest} — this class covers the read-only list modes
 * and the read-only tools.
 */
class GitReadToolsAuditTest {

    @TempDir
    Path tempDir;

    private Path repo;
    private String projectPath;
    private boolean serverStarted;
    private final AbstractAiSession session = newSession();

    @BeforeEach
    void initRepo() throws Exception {
        repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "master");
        git(repo, "config", "user.name", "Audit");
        git(repo, "config", "user.email", "audit@example.com");
        Files.writeString(repo.resolve("a.txt"), "alpha");
        Files.writeString(repo.resolve("b.txt"), "beta");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");
        projectPath = repo.toString();
    }

    /**
     * Restricts {@link #session} to {@code allowedDir} only. An in-repo path outside that directory must then
     * fail closed. Mirrors {@code GitMutatingToolsAuditTest#startServerScopedToRepo}.
     */
    private void startServerScopedTo(Path allowedDir) throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        serverStarted = true;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("git-read-audit-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        McpServerRegistry.getServer().registerSession(session.getId(), AiTypeEnum.CLAUDE,
                List.of(allowedDir.toFile()), true);
    }

    @AfterEach
    void stopServerIfStarted() {
        if (serverStarted) {
            McpServerRegistry.stopAll();
            McpServerRegistry.portOverride = null;
            serverStarted = false;
        }
    }

    // ---- GetGitStatus: projectPath ----
    @Test
    void getGitStatus_reportsBranchAndWorkingTreeChanges() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "alpha changed");
        Files.writeString(repo.resolve("new.txt"), "untracked");

        String result = new GetGitStatusTool().handle(new ToolRequestArguments(base()), session);

        assertTrue(result.contains("## master"), result);
        assertTrue(result.contains(" M a.txt"), result);
        assertTrue(result.contains("?? new.txt"), result);
    }

    @Test
    void getGitStatus_hidesIgnoredPathsButShowsUntrackedPaths() throws Exception {
        Files.writeString(repo.resolve(".git/info/exclude"), "ignored/\n");
        Files.createDirectories(repo.resolve("ignored/nested"));
        Files.writeString(repo.resolve("ignored/nested/file.txt"), "ignored");
        Files.writeString(repo.resolve("untracked.txt"), "untracked");

        String result = new GetGitStatusTool().handle(new ToolRequestArguments(base()), session);

        assertFalse(result.contains("ignored"), result);
        assertTrue(result.contains("?? untracked.txt"), result);
    }

    @Test
    void getGitStatus_cleanCommittedRepoPrintsBranchLine() throws Exception {
        String result = new GetGitStatusTool().handle(new ToolRequestArguments(base()), session);

        assertEquals("## master\n", result);
    }

    @Test
    void getGitStatus_emptyRepoWithoutCommitsSaysNothingToCommit() throws Exception {
        Path empty = Files.createDirectory(tempDir.resolve("empty"));
        git(empty, "init", "-b", "master");
        JsonObject args = new JsonObject();
        args.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), empty.toString());

        String result = new GetGitStatusTool().handle(new ToolRequestArguments(args), session);

        assertEquals("nothing to commit, working tree clean", result);
    }

    @Test
    void getGitStatus_missingProjectPathThrows() {
        assertThrows(McpArgumentException.class,
                () -> new GetGitStatusTool().handle(new ToolRequestArguments(new JsonObject()), session));
    }

    @Test
    void getGitStatus_plainDirectoryIsNotARepository() throws Exception {
        Path plain = Files.createDirectory(tempDir.resolve("plain"));
        JsonObject args = new JsonObject();
        args.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), plain.toString());

        String result = new GetGitStatusTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("Not a git repository"), result);
    }

    // ---- GetGitDiff: projectPath, staged ----
    @Test
    void getGitDiff_unstagedShowsWorkingTreeChanges() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "alpha changed");

        String result = new GetGitDiffTool().handle(new ToolRequestArguments(base()), session);

        assertTrue(result.contains("a.txt"), result);
        assertTrue(result.contains("-alpha"), result);
        assertTrue(result.contains("+alpha changed"), result);
    }

    @Test
    void getGitDiff_stagedFlipsToIndexDiff() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "alpha staged");
        git(repo, "add", "a.txt");
        Files.writeString(repo.resolve("b.txt"), "beta unstaged");

        JsonObject staged = base();
        staged.addProperty(GetGitDiffParamEnum.STAGED.key(), true);
        String stagedResult = new GetGitDiffTool().handle(new ToolRequestArguments(staged), session);

        assertTrue(stagedResult.contains("a.txt"), "staged diff must show the indexed file: " + stagedResult);
        assertFalse(stagedResult.contains("b.txt"), "staged diff must not show the unstaged file: " + stagedResult);

        String unstagedResult = new GetGitDiffTool().handle(new ToolRequestArguments(base()), session);
        assertFalse(unstagedResult.contains("a.txt"), "unstaged diff must not show the staged file: " + unstagedResult);
        assertTrue(unstagedResult.contains("b.txt"), "unstaged diff must show the working-tree change: " + unstagedResult);
    }

    @Test
    void getGitDiff_cleanRepoReturnsNoChanges() throws Exception {
        String result = new GetGitDiffTool().handle(new ToolRequestArguments(base()), session);

        assertEquals("(no changes)", result);
    }

    // ---- GitLog: projectPath, limit, file, follow ----
    @Test
    void gitLog_limitRestrictsHistory() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "second");
        git(repo, "commit", "-am", "second");
        Files.writeString(repo.resolve("a.txt"), "third");
        git(repo, "commit", "-am", "third");
        JsonObject args = base();
        args.addProperty(GitLogParamEnum.LIMIT.key(), 1);

        String result = new GitLogTool().handle(new ToolRequestArguments(args), session);

        assertEquals(1L, result.lines().count(), result);
        assertTrue(result.contains("third"), result);
        assertFalse(result.contains("second"), result);
    }

    @Test
    void gitLog_defaultLimitCoversWholeHistory() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "second");
        git(repo, "commit", "-am", "second");
        Files.writeString(repo.resolve("a.txt"), "third");
        git(repo, "commit", "-am", "third");

        String result = new GitLogTool().handle(new ToolRequestArguments(base()), session);

        assertEquals(3L, result.lines().count(), result);
        assertTrue(result.contains("third"), result);
        assertTrue(result.contains("initial"), result);
    }

    @Test
    void gitLog_fileScopesHistoryToThatPath() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "second");
        git(repo, "commit", "-am", "second");
        Files.writeString(repo.resolve("b.txt"), "beta changed");
        git(repo, "commit", "-am", "config change");
        JsonObject args = base();
        args.addProperty(GitLogParamEnum.LIMIT.key(), 10);
        args.addProperty(GitLogParamEnum.FILE.key(), "a.txt");

        String result = new GitLogTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("second"), result);
        assertTrue(result.contains("initial"), result);
        assertFalse(result.contains("config change"),
                "log scoped to a.txt must exclude commits touching only b.txt: " + result);
    }

    @Test
    void gitLog_followTracksRenamesWhenFileSet() throws Exception {
        Files.writeString(repo.resolve("f.txt"), "file content");
        git(repo, "add", "f.txt");
        git(repo, "commit", "-m", "create f");
        Files.move(repo.resolve("f.txt"), repo.resolve("renamed.txt"));
        git(repo, "add", "-A");
        git(repo, "commit", "-m", "rename f");

        JsonObject followArgs = base();
        followArgs.addProperty(GitLogParamEnum.FILE.key(), "renamed.txt");
        followArgs.addProperty(GitLogParamEnum.FOLLOW.key(), true);
        String follow = new GitLogTool().handle(new ToolRequestArguments(followArgs), session);

        assertTrue(follow.contains("rename f"), follow);
        assertTrue(follow.contains("create f"), "follow=true must surface the pre-rename commit: " + follow);

        JsonObject noFollowArgs = base();
        noFollowArgs.addProperty(GitLogParamEnum.FILE.key(), "renamed.txt");
        String noFollow = new GitLogTool().handle(new ToolRequestArguments(noFollowArgs), session);

        assertTrue(noFollow.contains("rename f"), noFollow);
        assertFalse(noFollow.contains("create f"),
                "without follow the pre-rename commit must stay hidden: " + noFollow);
    }

    // ---- GitBlame: projectPath, file ----
    @Test
    void gitBlame_showsAuthorHashAndContent() throws Exception {
        JsonObject args = base();
        args.addProperty(GitBlameParamEnum.FILE.key(), repo.resolve("a.txt").toString());

        String result = new GitBlameTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("Audit"), result);
        assertTrue(result.contains("alpha"), result);
        assertTrue(result.contains(" 1 "), result);
    }

    @Test
    void gitBlame_relativeFileResolvesAgainstProjectPath() throws Exception {
        JsonObject args = base();
        args.addProperty(GitBlameParamEnum.FILE.key(), "a.txt");

        String result = new GitBlameTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("alpha"), result);
    }

    @Test
    void gitBlame_missingFileThrows() {
        assertThrows(McpArgumentException.class,
                () -> new GitBlameTool().handle(new ToolRequestArguments(base()), session));
    }

    @Test
    void gitBlame_fileOutsideRepositoryIsRejected() throws Exception {
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "x");
        JsonObject args = base();
        args.addProperty(GitBlameParamEnum.FILE.key(), outside.toString());

        String result = new GitBlameTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("File is outside repository"), result);
    }

    @Test
    void gitBlame_untrackedFileHasNoBlameInfo() throws Exception {
        Files.writeString(repo.resolve("u.txt"), "uncommitted");
        JsonObject args = base();
        args.addProperty(GitBlameParamEnum.FILE.key(), repo.resolve("u.txt").toString());

        String result = new GitBlameTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("No blame info"), result);
    }

    @Test
    void gitBlame_infersRepositoryWithoutProjectPath() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty(GitBlameParamEnum.FILE.key(), repo.resolve("a.txt").toString());

        // FIXED (was a verified defect): with an absolute file and projectPath omitted, gitBlame resolves the owner via
        // FileOwnerQuery.getOwner() in GitProvider.resolveRootForFile, which threw a raw Error in any JVM with no
        // ProjectManagerImplementation — ExceptionInInitializerError on first use, NoClassDefFoundError thereafter.
        // That lookup is now contained the same way refreshVcsStatus contains it, degrading to the file's own directory
        // so findGitRoot still walks up to the repository. Blame must therefore SUCCEED here rather than throw.
        String result = new GitBlameTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("alpha"), result);
    }

    // ---- GitShow: projectPath, revision ----
    @Test
    void gitShow_defaultsToHead() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "second");
        git(repo, "commit", "-am", "second");
        String head = git(repo, "rev-parse", "HEAD");

        String result = new GitShowTool().handle(new ToolRequestArguments(base()), session);

        assertTrue(result.contains("commit " + head), result);
        assertTrue(result.contains("second"), result);
    }

    @Test
    void gitShow_explicitRevisionShowsThatCommit() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "second");
        git(repo, "commit", "-am", "second");
        JsonObject args = base();
        args.addProperty(GitShowParamEnum.REVISION.key(), "HEAD~1");
        String head = git(repo, "rev-parse", "HEAD");

        String result = new GitShowTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("initial"), result);
        assertFalse(result.contains("commit " + head), result);
    }

    @Test
    void gitShow_filePathsIncludesRootCommitChanges() {
        String result = GitProvider.gitShow(projectPath, "HEAD", List.of("a.txt"), null);

        assertTrue(result.contains("alpha"), result);
        assertTrue(result.contains("a.txt"), result);
        assertFalse(result.contains("b.txt"), result);
    }

    @Test
    void gitShow_filePathsDoesNotMatchPatchBodyText() throws Exception {
        Files.writeString(repo.resolve("public.txt"), "public");
        Files.writeString(repo.resolve("secret.txt"), "safe");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "add files");
        Files.writeString(repo.resolve("secret.txt"), "secret body a/public.txt marker");
        git(repo, "commit", "-am", "secret change");

        String result = GitProvider.gitShow(projectPath, "HEAD", List.of("public.txt"), null);

        assertFalse(result.contains("secret body"), result);
        assertFalse(result.contains("secret.txt"), result);
    }

    @Test
    void gitShow_filePathsMatchesExactHeaderPathNotPrefix() throws Exception {
        Files.writeString(repo.resolve("foo"), "foo");
        Files.writeString(repo.resolve("foo bar"), "foo bar");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "add foo files");
        Files.writeString(repo.resolve("foo bar"), "changed foo bar");
        git(repo, "commit", "-am", "change second file");

        String result = GitProvider.gitShow(projectPath, "HEAD", List.of("foo"), null);

        assertFalse(result.contains("changed foo bar"), result);
        assertFalse(result.contains("foo bar"), result);
    }

    @Test
    void gitShow_filePathsHandlesQuotedAndRenamedPaths() throws Exception {
        Path oldName = repo.resolve("old name\\\".txt");
        Path newName = repo.resolve("new name\\\".txt");
        Files.writeString(oldName, "content");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "add quoted name");
        Files.move(oldName, newName);
        git(repo, "add", "-A");
        git(repo, "commit", "-m", "rename quoted name");

        String oldResult = GitProvider.gitShow(projectPath, "HEAD", List.of(oldName.getFileName().toString()), null);
        String newResult = GitProvider.gitShow(projectPath, "HEAD", List.of(newName.getFileName().toString()), null);

        assertTrue(oldResult.contains("rename from"), oldResult);
        assertTrue(newResult.contains("rename to"), newResult);
    }

    @Test
    void gitReadFilePaths_rejectsOutsideRepository() throws Exception {
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");

        String diff = GitProvider.getGitDiff(projectPath, false, List.of(outside.toString()), null);
        String log = GitProvider.gitLog(projectPath, 20, null, List.of(outside.toString()), false, null);
        String show = GitProvider.gitShow(projectPath, "HEAD", List.of(outside.toString()), null);

        assertTrue(diff.contains("outside repository"), diff);
        assertTrue(log.contains("outside repository"), log);
        assertTrue(show.contains("outside repository"), show);
    }

    @Test
    void gitReadFilePaths_rejectsInvalidArgumentShape() {
        JsonObject args = base();
        args.addProperty(GetGitDiffParamEnum.FILE_PATHS.key(), "a.txt");

        assertThrows(McpArgumentException.class,
                () -> new GetGitDiffTool().handle(new ToolRequestArguments(args), session));
    }

    // ---- GitBranch (list mode): projectPath, all ----
    @Test
    void gitBranch_listMarksCurrentBranch() throws Exception {
        String result = new GitBranchTool().handle(new ToolRequestArguments(base()), session);

        assertEquals("* master", result);
    }

    @Test
    void gitBranch_allIncludesRemoteTrackingBranches() throws Exception {
        Path bare = tempDir.resolve("remote.git");
        git(tempDir, "init", "--bare", "remote.git");
        git(repo, "remote", "add", "origin", bare.toString());
        git(repo, "push", "origin", "master");
        git(repo, "fetch", "origin");

        String local = new GitBranchTool().handle(new ToolRequestArguments(base()), session);

        assertEquals(1L, local.lines().count(), local);
        assertFalse(local.contains("origin"), local);

        JsonObject args = base();
        args.addProperty(GitBranchParamEnum.ALL.key(), true);
        String withRemote = new GitBranchTool().handle(new ToolRequestArguments(args), session);

        assertEquals(2L, withRemote.lines().count(), withRemote);
        assertTrue(withRemote.contains("master"), withRemote);
        assertTrue(withRemote.contains("origin"), withRemote);
    }

    // ---- GitRemote (list mode): projectPath, action ----
    @Test
    void gitRemote_defaultActionListsRemotes() throws Exception {
        git(repo, "remote", "add", "origin", "https://example.com/repo.git");

        String result = new GitRemoteTool().handle(new ToolRequestArguments(base()), session);

        assertTrue(result.contains("origin"), result);
        assertTrue(result.contains("https://example.com/repo.git"), result);
    }

    @Test
    void gitRemote_explicitActionAndInvalidAction() throws Exception {
        git(repo, "remote", "add", "origin", "https://example.com/repo.git");

        JsonObject listArgs = base();
        listArgs.addProperty(GitRemoteParamEnum.ACTION.key(), "LIST");
        String listed = new GitRemoteTool().handle(new ToolRequestArguments(listArgs), session);
        assertTrue(listed.contains("origin"), listed);

        JsonObject bad = base();
        bad.addProperty(GitRemoteParamEnum.ACTION.key(), "bogus");
        String rejected = new GitRemoteTool().handle(new ToolRequestArguments(bad), session);
        assertTrue(rejected.contains("Invalid action 'bogus'"), rejected);
        assertTrue(rejected.contains("list (default)"), rejected);
    }

    @Test
    void gitRemote_noRemotesConfigured() throws Exception {
        String result = new GitRemoteTool().handle(new ToolRequestArguments(base()), session);

        assertEquals("No remotes configured", result);
    }

    // ---- GitTag (list mode): projectPath, action ----
    @Test
    void gitTag_defaultActionListsTags() throws Exception {
        git(repo, "tag", "v1.0");
        git(repo, "tag", "-a", "v2.0", "-m", "note");

        String result = new GitTagTool().handle(new ToolRequestArguments(base()), session);

        assertTrue(result.contains("v1.0"), result);
        assertTrue(result.contains("v2.0"), result);
    }

    @Test
    void gitTag_explicitListAction() throws Exception {
        git(repo, "tag", "v1.0");
        JsonObject args = base();
        args.addProperty(GitTagParamEnum.ACTION.key(), "list");

        String result = new GitTagTool().handle(new ToolRequestArguments(args), session);

        assertEquals("v1.0", result);
    }

    @Test
    void gitTag_invalidActionRejected() throws Exception {
        JsonObject args = base();
        args.addProperty(GitTagParamEnum.ACTION.key(), "bogus");

        String result = new GitTagTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("Invalid action 'bogus'"), result);
        assertTrue(result.contains("list (default)"), result);
    }

    @Test
    void gitTag_noTags() throws Exception {
        String result = new GitTagTool().handle(new ToolRequestArguments(base()), session);

        assertEquals("No tags", result);
    }

    // ---- filePaths: session scope, escapes, rename, follow, directories ----
    @Test
    void filePathsSessionScopeDeniesGetGitDiff() throws Exception {
        commitScopedPair();
        Files.writeString(repo.resolve("secret/hidden.txt"), "HIDDEN-TOKEN-DIFF");
        JsonObject hidden = base();
        hidden.add(GetGitDiffParamEnum.FILE_PATHS.key(), arr("secret/hidden.txt"));

        String unscoped = new GetGitDiffTool().handle(new ToolRequestArguments(hidden), session);

        assertTrue(unscoped.contains("HIDDEN-TOKEN-DIFF"), unscoped);

        startServerScopedTo(repo.resolve("allowed"));
        String denied = new GetGitDiffTool().handle(new ToolRequestArguments(hidden), session);

        assertTrue(denied.contains("Invalid filePaths:"), denied);
        assertTrue(denied.contains("not accessible"), denied);
        assertFalse(denied.contains("HIDDEN-TOKEN-DIFF"), denied);

        Files.writeString(repo.resolve("allowed/ok.txt"), "visible-ok-edited");
        JsonObject visible = base();
        visible.add(GetGitDiffParamEnum.FILE_PATHS.key(), arr("allowed/ok.txt"));
        String allowed = new GetGitDiffTool().handle(new ToolRequestArguments(visible), session);

        assertTrue(allowed.contains("visible-ok-edited"), allowed);
        assertFalse(allowed.contains("not accessible"), allowed);
    }

    @Test
    void filePathsSessionScopeDeniesGitShow() throws Exception {
        commitScopedPair();
        JsonObject args = base();
        args.add(GitShowParamEnum.FILE_PATHS.key(), arr("secret/hidden.txt"));

        String unscoped = new GitShowTool().handle(new ToolRequestArguments(args), session);

        assertTrue(unscoped.contains("HIDDEN-TOKEN"), unscoped);

        startServerScopedTo(repo.resolve("allowed"));
        String denied = new GitShowTool().handle(new ToolRequestArguments(args), session);

        assertTrue(denied.contains("Invalid filePaths:"), denied);
        assertTrue(denied.contains("not accessible"), denied);
        assertFalse(denied.contains("HIDDEN-TOKEN"), denied);
    }

    @Test
    void filePathsSessionScopeDeniesGitLog() throws Exception {
        commitScopedPair();
        JsonObject args = base();
        args.add(GitLogParamEnum.FILE_PATHS.key(), arr("secret/hidden.txt"));

        String unscoped = new GitLogTool().handle(new ToolRequestArguments(args), session);

        assertTrue(unscoped.contains("secret-only-commit"), unscoped);

        startServerScopedTo(repo.resolve("allowed"));
        String denied = new GitLogTool().handle(new ToolRequestArguments(args), session);

        assertTrue(denied.contains("Invalid filePaths:"), denied);
        assertTrue(denied.contains("not accessible"), denied);
        assertFalse(denied.contains("secret-only-commit"), denied);
    }

    @Test
    void gitLogLegacyFileSessionScopeDeniesInRepoPath() throws Exception {
        commitScopedPair();
        JsonObject args = base();
        args.addProperty(GitLogParamEnum.FILE.key(), "secret/hidden.txt");

        String unscoped = new GitLogTool().handle(new ToolRequestArguments(args), session);

        assertTrue(unscoped.contains("secret-only-commit"), unscoped);

        startServerScopedTo(repo.resolve("allowed"));
        String denied = new GitLogTool().handle(new ToolRequestArguments(args), session);

        assertTrue(denied.startsWith("Invalid file:"), denied);
        assertTrue(denied.contains("not accessible"), denied);
        assertFalse(denied.contains("Invalid filePaths:"), denied);
        assertFalse(denied.contains("secret-only-commit"), denied);
    }

    @Test
    void gitShowDropsRenameWhenOtherSideIsOutOfScope() throws Exception {
        Files.createDirectories(repo.resolve("allowed"));
        Files.createDirectories(repo.resolve("secret"));
        Files.writeString(repo.resolve("allowed/keep.txt"), "keep-before");
        Files.writeString(repo.resolve("secret/creds.txt"), "SECRET-CREDS-BODY");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "add creds");
        Files.move(repo.resolve("secret/creds.txt"), repo.resolve("allowed/moved.txt"));
        Files.writeString(repo.resolve("allowed/keep.txt"), "keep-still-visible");
        git(repo, "add", "-A");
        git(repo, "commit", "-m", "rename creds");

        String unscoped = GitProvider.gitShow(projectPath, "HEAD", List.of("allowed/moved.txt"), null);

        assertTrue(unscoped.contains("rename from secret/creds.txt"), unscoped);

        startServerScopedTo(repo.resolve("allowed"));
        JsonObject moved = base();
        moved.add(GitShowParamEnum.FILE_PATHS.key(), arr("allowed/moved.txt"));
        String scoped = new GitShowTool().handle(new ToolRequestArguments(moved), session);

        assertTrue(scoped.contains("rename creds"), scoped);
        assertFalse(scoped.contains("SECRET-CREDS-BODY"), scoped);
        assertFalse(scoped.contains("secret/creds.txt"), scoped);

        JsonObject keep = base();
        keep.add(GitShowParamEnum.FILE_PATHS.key(), arr("allowed/keep.txt"));
        String keepResult = new GitShowTool().handle(new ToolRequestArguments(keep), session);

        assertTrue(keepResult.contains("keep-still-visible"), keepResult);
        assertFalse(keepResult.contains("SECRET-CREDS-BODY"), keepResult);
    }

    @Test
    void dotDotAndSymlinkOutsideRepositoryAreRefused() throws Exception {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "OUTSIDE-SECRET");
        Files.createSymbolicLink(repo.resolve("leak.txt"), outside);

        for (String escaped : new String[]{"../outside.txt", "leak.txt"}) {
            JsonObject diffArgs = base();
            diffArgs.add(GetGitDiffParamEnum.FILE_PATHS.key(), arr(escaped));
            String diff = new GetGitDiffTool().handle(new ToolRequestArguments(diffArgs), session);
            assertTrue(diff.contains("outside repository"), escaped + " -> " + diff);
            assertFalse(diff.contains("OUTSIDE-SECRET"), diff);

            JsonObject showArgs = base();
            showArgs.add(GitShowParamEnum.FILE_PATHS.key(), arr(escaped));
            String show = new GitShowTool().handle(new ToolRequestArguments(showArgs), session);
            assertTrue(show.contains("outside repository"), escaped + " -> " + show);
            assertFalse(show.contains("OUTSIDE-SECRET"), show);

            JsonObject logArgs = base();
            logArgs.add(GitLogParamEnum.FILE_PATHS.key(), arr(escaped));
            String log = new GitLogTool().handle(new ToolRequestArguments(logArgs), session);
            assertTrue(log.contains("outside repository"), escaped + " -> " + log);
            assertFalse(log.contains("OUTSIDE-SECRET"), log);
        }
    }

    @Test
    void gitLogRejectsFileTogetherWithFilePaths() throws Exception {
        JsonObject args = base();
        args.addProperty(GitLogParamEnum.FILE.key(), "a.txt");
        args.add(GitLogParamEnum.FILE_PATHS.key(), arr("b.txt"));

        String result = new GitLogTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("Specify either file or filePaths, not both"), result);
    }

    @Test
    void gitLogFollowWithTwoPathsIsRejected() throws Exception {
        JsonObject args = base();
        args.add(GitLogParamEnum.FILE_PATHS.key(), arr("a.txt", "b.txt"));
        args.addProperty(GitLogParamEnum.FOLLOW.key(), true);

        String result = new GitLogTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("follow=true requires exactly one file path"), result);
    }

    @Test
    void gitLogFollowWithZeroPathsIsIgnored() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "second");
        git(repo, "commit", "-am", "touch a");
        Files.writeString(repo.resolve("b.txt"), "beta changed");
        git(repo, "commit", "-am", "touch b");

        String plain = new GitLogTool().handle(new ToolRequestArguments(base()), session);
        JsonObject followOmitted = base();
        followOmitted.addProperty(GitLogParamEnum.FOLLOW.key(), true);
        String omitted = new GitLogTool().handle(new ToolRequestArguments(followOmitted), session);
        JsonObject followEmpty = base();
        followEmpty.add(GitLogParamEnum.FILE_PATHS.key(), new JsonArray());
        followEmpty.addProperty(GitLogParamEnum.FOLLOW.key(), true);
        String empty = new GitLogTool().handle(new ToolRequestArguments(followEmpty), session);

        assertEquals(plain, omitted);
        assertEquals(plain, empty);
        assertTrue(plain.contains("touch a"), plain);
        assertTrue(plain.contains("touch b"), plain);
        assertFalse(plain.contains("requires exactly one"), plain);
    }

    @Test
    void gitShowDirectoryTargetIncludesChildrenButNotPrefix() throws Exception {
        Files.createDirectories(repo.resolve("src"));
        Files.createDirectories(repo.resolve("srcx"));
        Files.writeString(repo.resolve("src/Foo.java"), "from-src-tree");
        Files.writeString(repo.resolve("srcx/Bar.java"), "from-srcx-tree");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "add src trees");
        JsonObject args = base();
        args.add(GitShowParamEnum.FILE_PATHS.key(), arr("src"));

        String result = new GitShowTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("from-src-tree"), result);
        assertTrue(result.contains("src/Foo.java"), result);
        assertFalse(result.contains("from-srcx-tree"), result);
        assertFalse(result.contains("srcx/"), result);
    }

    @Test
    void gitShowSymlinkedProjectPathStillMatches() throws Exception {
        Path link = tempDir.resolve("repo-link");
        Files.createSymbolicLink(link, repo);
        JsonObject args = new JsonObject();
        args.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), link.toString());
        args.add(GitShowParamEnum.FILE_PATHS.key(), arr("a.txt"));

        String result = new GitShowTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("alpha"), result);
        assertTrue(result.contains("a.txt"), result);
        assertFalse(result.contains("beta"), result);
    }

    // ---- helpers ----
    private JsonObject base() {
        JsonObject o = new JsonObject();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath);
        return o;
    }

    private static JsonArray arr(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /**
     * Two commits: {@code allowed/ok.txt} then a later commit that touches only {@code secret/hidden.txt}. A
     * scope limited to {@code allowed/} must refuse the secret path while the unscoped call still sees it.
     */
    private void commitScopedPair() throws Exception {
        Files.createDirectories(repo.resolve("allowed"));
        Files.createDirectories(repo.resolve("secret"));
        Files.writeString(repo.resolve("allowed/ok.txt"), "visible-ok");
        git(repo, "add", "allowed/ok.txt");
        git(repo, "commit", "-m", "add allowed");
        Files.writeString(repo.resolve("secret/hidden.txt"), "HIDDEN-TOKEN");
        git(repo, "add", "secret/hidden.txt");
        git(repo, "commit", "-m", "secret-only-commit");
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(bos);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed (" + code + "): "
                                            + bos.toString(StandardCharsets.UTF_8));
        }
        return bos.toString(StandardCharsets.UTF_8).strip();
    }

    private static AbstractAiSession newSession() {
        return new AbstractAiSession(AiSession.create(null, AiTypeEnum.CLAUDE)) {
            @Override
            public String getId() {
                return "git-read-audit";
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }

            @Override
            public java.util.Map<kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum, kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface> getMcpToolHandlers() {
                return java.util.Map.of();
            }
        };
    }

    private static final class NoopRegistrar extends AiMcpRegistrar {

        NoopRegistrar(String sessionId) {
            super(sessionId, AiTypeEnum.CLAUDE);
        }

        @Override
        public void addMcpEndpoint(String endpointUrl) {
        }

        @Override
        public void removeMcpEndpoint() {
        }

        @Override
        public boolean registerHooks(String serverBaseUrl) {
            return true;
        }

        @Override
        public void unregisterHooks() {
        }
    }
}
