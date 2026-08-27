package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Audit of the NON-MUTATING git MCP tools. Proves every schema-advertised parameter actually changes the tool's
 * behaviour by invoking {@code handle()} against throwaway repositories created with the real {@code git} CLI. Never
 * touches the plugin's own repository: {@code projectPath} always points at a per-test temp dir which is deleted by the
 * test harness. The mutating actions of branch/remote/tag (create/delete, add/remove) are covered by
 * {@code GitMutatingToolsAuditTest} — this class covers the read-only list modes and the read-only tools.
 */
class GitReadToolsAuditTest {

    @TempDir
    Path tempDir;

    private Path repo;
    private String projectPath;
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
    void getGitStatus_cleanCommittedRepoPrintsBranchLine() throws Exception {
        String result = new GetGitStatusTool().handle(new ToolRequestArguments(base()), session);

        assertEquals("## master\n", result);
    }

    @Test
    void getGitStatus_emptyRepoWithoutCommitsSaysNothingToCommit() throws Exception {
        Path empty = Files.createDirectory(tempDir.resolve("empty"));
        git(empty, "init", "-b", "master");
        JsonObject args = new JsonObject();
        args.addProperty(GitCommonParamEnum.PROJECT_PATH.key(), empty.toString());

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
        args.addProperty(GitCommonParamEnum.PROJECT_PATH.key(), plain.toString());

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

    // ---- helpers ----
    private JsonObject base() {
        JsonObject o = new JsonObject();
        o.addProperty(GitCommonParamEnum.PROJECT_PATH.key(), projectPath);
        return o;
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
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
}
