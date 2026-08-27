package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves every parameter and mode of the MUTATING git MCP tools by invoking {@code handle()} against throwaway
 * repositories created with the real {@code git} CLI. Never touches the plugin's own repository: {@code projectPath}
 * always points at a per-test temp dir which is deleted by the harness.
 */
class GitMutatingToolsAuditTest {

    private static JsonArray arr(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        return a;
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
        // Preserve leading whitespace: porcelain's first character is the index column and a leading space is
        // semantically meaningful (unstaged vs staged). Only strip the trailing newline the process appends.
        // WARNING: do NOT call String.strip()/trim() here. It erases that leading column marker, so " M a.txt"
        // (unstaged) and "M  a.txt" (staged) collapse to the same string and every staged-vs-unstaged assertion
        // produces confident false failures. Keep this helper byte-faithful.
        String out = bos.toString(StandardCharsets.UTF_8);
        return out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
    }

    private static AbstractAiSession newSession() {
        return new AbstractAiSession(AiSession.create(null, AiTypeEnum.CLAUDE)) {
            @Override
            public String getId() {
                return "git-mutating-audit";
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
     * Starts a REAL {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer} and registers this
     * test's session with the throwaway repository as its only allowed project directory, under
     * restrict-to-project-files. Commit is the one git tool that additionally consults
     * {@code GitProvider.areCommitTargetsAllowed}, which fails closed when no server is registered; without a server
     * every commit assertion below would only ever prove the absence of a server. Nothing here fakes or bypasses the
     * gate — {@code isFileAllowed} runs for real and answers true only because the temp repo is genuinely in scope.
     * Mirrors the harness in {@code GitProjectPathScopeTest} and {@code ApplyEditToolTest}.
     */
    private void startServerScopedToRepo() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        serverStarted = true;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("git-mutating-audit-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        McpServerRegistry.getServer().registerSession(session.getId(), AiTypeEnum.CLAUDE, List.of(repo.toFile()), true);
    }

    /**
     * Stops the server with NO session registered, so {@code areCommitTargetsAllowed} sees a null server and takes its
     * fail-closed branch. Used to prove the refusal is real rather than assumed.
     */
    private void stopServerEntirely() {
        serverStarted = true;
        McpServerRegistry.stopAll();
    }

    @AfterEach
    void stopServerIfStarted() {
        if (serverStarted) {
            McpServerRegistry.stopAll();
            McpServerRegistry.portOverride = null;
            serverStarted = false;
        }
    }

    // ---- GitAdd ----
    @Test
    void gitAddStagesASpecificFile() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "alpha changed");
        JsonObject args = base();
        args.add(GitAddParamEnum.FILES.key(), arr("a.txt"));

        String result = new GitAddTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("Added 1 path(s)"), result);
        assertTrue(git(repo, "status", "--porcelain").contains("M  a.txt"),
                "a.txt must be staged after GitAdd: " + git(repo, "status", "--porcelain"));
    }

    @Test
    void gitAddDotStagesAllChanges() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "alpha changed 2");
        Files.writeString(repo.resolve("b.txt"), "beta changed");
        JsonObject args = base();
        args.add(GitAddParamEnum.FILES.key(), arr("."));

        String result = new GitAddTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("Added"), result);
        String porcelain = git(repo, "status", "--porcelain");
        assertTrue(porcelain.contains("M  a.txt"), porcelain);
        assertTrue(porcelain.contains("M  b.txt"), porcelain);
    }

    // ---- GitCommit ----
    @Test
    void gitCommitWithoutFilesCommitsStagedChanges() throws Exception {
        startServerScopedToRepo();
        Files.writeString(repo.resolve("a.txt"), "alpha commit");
        git(repo, "add", "a.txt");
        JsonObject args = base();
        args.addProperty(GitCommitParamEnum.MESSAGE.key(), "commit without files");

        String result = new GitCommitTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("Committed"),
                "commit of staged changes must succeed; actual result: " + result);
        assertTrue(git(repo, "log", "--oneline", "-1").contains("commit without files"),
                git(repo, "log", "--oneline", "-1"));
    }

    /**
     * The other half of the gate: with no server registered {@code areCommitTargetsAllowed} must refuse rather than
     * wave the commit through. Without this, the two passing commit tests above would not distinguish "the gate allows
     * an in-scope repo" from "the gate never runs".
     */
    @Test
    void gitCommitRefusesWhenNoServerIsRegistered() throws Exception {
        stopServerEntirely();
        Files.writeString(repo.resolve("a.txt"), "alpha unauthorised");
        git(repo, "add", "a.txt");
        JsonObject args = base();
        args.addProperty(GitCommitParamEnum.MESSAGE.key(), "must not be committed");

        String result = new GitCommitTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("not within the allowed project directories"),
                "with no server the commit gate must fail closed; actual result: " + result);
        assertFalse(git(repo, "log", "--oneline", "-1").contains("must not be committed"),
                "the refused commit must not reach the repository: " + git(repo, "log", "--oneline", "-1"));
    }

    @Test
    void gitCommitWithFilesStagesThenCommits() throws Exception {
        startServerScopedToRepo();
        Files.writeString(repo.resolve("b.txt"), "beta commit");
        JsonObject args = base();
        args.addProperty(GitCommitParamEnum.MESSAGE.key(), "commit with files");
        args.add(GitCommitParamEnum.FILES.key(), arr("b.txt"));

        String result = new GitCommitTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("Committed"),
                "GitCommit files parameter must stage then commit; actual result: " + result);
        assertTrue(git(repo, "log", "--oneline", "-1").contains("commit with files"),
                git(repo, "log", "--oneline", "-1"));
    }

    // ---- GitCheckout ----
    @Test
    void gitCheckoutSwitchAndCreate() throws Exception {
        git(repo, "branch", "feature");
        JsonObject switchArgs = base();
        switchArgs.addProperty(GitCheckoutParamEnum.BRANCH.key(), "feature");
        String switched = new GitCheckoutTool().handle(new ToolRequestArguments(switchArgs), session);
        assertTrue(switched.contains("Switched to feature"), switched);
        assertTrue(git(repo, "branch", "--show-current").contains("feature"));

        JsonObject createArgs = base();
        createArgs.addProperty(GitCheckoutParamEnum.BRANCH.key(), "newbranch");
        createArgs.addProperty(GitCheckoutParamEnum.CREATE.key(), true);
        String created = new GitCheckoutTool().handle(new ToolRequestArguments(createArgs), session);
        assertTrue(created.contains("new branch newbranch"), created);
        assertTrue(git(repo, "branch", "--show-current").contains("newbranch"));
    }

    // ---- GitBranch ----
    @Test
    void gitBranchCreateAndList() throws Exception {
        JsonObject createArgs = base();
        createArgs.addProperty(GitBranchParamEnum.CREATE.key(), "hotfix");
        String created = new GitBranchTool().handle(new ToolRequestArguments(createArgs), session);
        assertTrue(created.contains("Created branch: hotfix"), created);
        assertTrue(git(repo, "branch").contains("hotfix"), git(repo, "branch"));

        JsonObject listArgs = base();
        String listed = new GitBranchTool().handle(new ToolRequestArguments(listArgs), session);
        assertTrue(listed.contains("hotfix"), listed);
        assertTrue(listed.contains("* master"), listed);

        JsonObject allArgs = base();
        allArgs.addProperty(GitBranchParamEnum.ALL.key(), true);
        String allListed = new GitBranchTool().handle(new ToolRequestArguments(allArgs), session);
        assertTrue(allListed.contains("hotfix"), "all=true must still list branches: " + allListed);
    }

    // ---- GitDeleteBranch ----
    @Test
    void gitDeleteBranchMergedAndUnmergedForce() throws Exception {
        git(repo, "branch", "mergedbranch");
        JsonObject ok = base();
        ok.addProperty(GitDeleteBranchParamEnum.BRANCH.key(), "mergedbranch");
        String deleted = new GitDeleteBranchTool().handle(new ToolRequestArguments(ok), session);
        assertTrue(deleted.contains("Deleted branch: mergedbranch"), deleted);
        assertTrue(!git(repo, "branch").contains("mergedbranch"), git(repo, "branch"));

        git(repo, "checkout", "-b", "unmerged");
        Files.writeString(repo.resolve("u.txt"), "u");
        git(repo, "add", "u.txt");
        git(repo, "commit", "-m", "unmerged");
        git(repo, "checkout", "master");

        JsonObject noForce = base();
        noForce.addProperty(GitDeleteBranchParamEnum.BRANCH.key(), "unmerged");
        noForce.addProperty(GitDeleteBranchParamEnum.FORCE.key(), false);
        String refused = new GitDeleteBranchTool().handle(new ToolRequestArguments(noForce), session);
        assertTrue(refused.toLowerCase().contains("not merged"),
                "unmerged branch without force must be refused: " + refused);
        assertTrue(git(repo, "branch").contains("unmerged"), git(repo, "branch"));

        JsonObject force = base();
        force.addProperty(GitDeleteBranchParamEnum.BRANCH.key(), "unmerged");
        force.addProperty(GitDeleteBranchParamEnum.FORCE.key(), true);
        String forced = new GitDeleteBranchTool().handle(new ToolRequestArguments(force), session);
        assertTrue(forced.contains("Deleted branch: unmerged"), forced);
        assertTrue(!git(repo, "branch").contains("unmerged"), git(repo, "branch"));
    }

    // ---- GitMerge ----
    @Test
    void gitMergeFastForward() throws Exception {
        git(repo, "checkout", "-b", "topic");
        Files.writeString(repo.resolve("t.txt"), "t");
        git(repo, "add", "t.txt");
        git(repo, "commit", "-m", "topic commit");
        git(repo, "checkout", "master");

        JsonObject args = base();
        args.addProperty(GitMergeParamEnum.BRANCH.key(), "topic");
        String result = new GitMergeTool().handle(new ToolRequestArguments(args), session);
        assertTrue(result.contains("Fast-forward") || result.toLowerCase().contains("fast forward"), result);
        assertTrue(git(repo, "log", "--oneline", "-1").contains("topic commit"), git(repo, "log", "--oneline", "-1"));
    }

    // ---- GitRevert ----
    @Test
    void gitRevertCreatesInverseCommit() throws Exception {
        git(repo, "checkout", "-b", "work");
        Files.writeString(repo.resolve("w.txt"), "w");
        git(repo, "add", "w.txt");
        git(repo, "commit", "-m", "to be reverted");
        Files.writeString(repo.resolve("keep.txt"), "keep");
        git(repo, "add", "keep.txt");
        git(repo, "commit", "-m", "keep me");
        String revertTarget = git(repo, "rev-parse", "HEAD~1").strip();

        JsonObject args = base();
        args.addProperty(GitRevertParamEnum.REVISION.key(), revertTarget);
        String result = new GitRevertTool().handle(new ToolRequestArguments(args), session);

        assertTrue(result.contains("Reverted"), result);
        assertTrue(!git(repo, "ls-files").contains("w.txt"),
                "revert must remove the file added by the reverted commit: " + git(repo, "ls-files"));
    }

    // ---- GitReset ----
    @Test
    void gitResetHardToPriorRevision() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "alpha2");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-m", "second");
        JsonObject hard = base();
        hard.addProperty(GitResetParamEnum.TYPE.key(), "HARD");
        hard.addProperty(GitResetParamEnum.REVISION.key(), "HEAD~1");

        String result = new GitResetTool().handle(new ToolRequestArguments(hard), session);

        assertTrue(result.toLowerCase().contains("reset hard"), result);
        assertTrue(git(repo, "log", "--oneline", "-1").contains("initial"), git(repo, "log", "--oneline", "-1"));
        assertTrue(git(repo, "status", "--porcelain").isBlank(), git(repo, "status", "--porcelain"));
    }

    @Test
    void gitResetSoftKeepsChangesStaged() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "alpha2");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-m", "second");
        JsonObject soft = base();
        soft.addProperty(GitResetParamEnum.TYPE.key(), "soft");
        soft.addProperty(GitResetParamEnum.REVISION.key(), "HEAD~1");

        String result = new GitResetTool().handle(new ToolRequestArguments(soft), session);

        assertTrue(result.toLowerCase().contains("reset soft"), "lowercase 'soft' must fold to SOFT: " + result);
        assertTrue(git(repo, "status", "--porcelain").contains("M  a.txt"),
                "soft reset leaves the change staged: " + git(repo, "status", "--porcelain"));
    }

    @Test
    void gitResetMixedUnstages() throws Exception {
        // Mixed reset moves HEAD and resets the index, leaving the worktree change UNSTAGED (porcelain " M a.txt").
        // The leading space is meaningful; this proved the mixed mode works once the harness stayed byte-faithful.
        Files.writeString(repo.resolve("a.txt"), "alpha2");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-m", "second");
        JsonObject mixed = base();
        mixed.addProperty(GitResetParamEnum.TYPE.key(), "mixed");
        mixed.addProperty(GitResetParamEnum.REVISION.key(), "HEAD~1");

        String result = new GitResetTool().handle(new ToolRequestArguments(mixed), session);

        assertTrue(result.toLowerCase().contains("reset mixed"), result);
        assertTrue(git(repo, "status", "--porcelain").contains(" M a.txt"),
                "mixed reset leaves the change unstaged: " + git(repo, "status", "--porcelain"));
    }

    @Test
    void gitResetFilesUnstagesASpecificFile() throws Exception {
        // Files-based reset (FILES=["a.txt"], no type) must unstage only those files: "M  a.txt" -> " M a.txt".
        Files.writeString(repo.resolve("a.txt"), "zyx");
        git(repo, "add", "a.txt");
        assertTrue(git(repo, "status", "--porcelain").contains("M  a.txt"), git(repo, "status", "--porcelain"));
        JsonObject files = base();
        files.add(GitResetParamEnum.FILES.key(), arr("a.txt"));

        String result = new GitResetTool().handle(new ToolRequestArguments(files), session);

        assertTrue(result.contains("Reset 1 file"), result);
        assertTrue(git(repo, "status", "--porcelain").contains(" M a.txt"),
                "files reset must unstage: " + git(repo, "status", "--porcelain"));
    }

    // ---- GitRebase ----
    @Test
    void gitRebaseBeginThenAbortRollsBack() throws Exception {
        Files.writeString(repo.resolve("shared.txt"), "base\n");
        git(repo, "add", "shared.txt");
        git(repo, "commit", "-m", "base commit");
        git(repo, "checkout", "-b", "topic");
        Files.writeString(repo.resolve("topic.txt"), "t");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "topic change");
        git(repo, "checkout", "master");
        Files.writeString(repo.resolve("shared.txt"), "master\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "master change");
        git(repo, "checkout", "topic");

        JsonObject begin = base();
        begin.addProperty(GitRebaseParamEnum.OPERATION.key(), "BEGIN");
        begin.addProperty(GitRebaseParamEnum.UPSTREAM.key(), "master");
        String b = new GitRebaseTool().handle(new ToolRequestArguments(begin), session);
        assertTrue(!b.contains("Invalid operation"), "BEGIN must not be rejected: " + b);

        JsonObject abort = base();
        abort.addProperty(GitRebaseParamEnum.OPERATION.key(), "ABORT");
        String a = new GitRebaseTool().handle(new ToolRequestArguments(abort), session);
        assertTrue(!a.contains("Invalid operation"), "ABORT must not be rejected: " + a);
        assertTrue(git(repo, "log", "--oneline", "-1").contains("topic change"),
                "ABORT must restore the pre-rebase ref: " + git(repo, "log", "--oneline", "-1"));
    }

    @Test
    void gitRebaseContinueAndSkipDispatchDuringConflict() throws Exception {
        Files.writeString(repo.resolve("shared.txt"), "base\n");
        git(repo, "add", "shared.txt");
        git(repo, "commit", "-m", "base commit");
        git(repo, "checkout", "-b", "topic");
        Files.writeString(repo.resolve("shared.txt"), "topic\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "topic change");
        git(repo, "checkout", "master");
        Files.writeString(repo.resolve("shared.txt"), "master\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "master change");
        git(repo, "checkout", "topic");

        JsonObject begin = base();
        begin.addProperty(GitRebaseParamEnum.OPERATION.key(), "BEGIN");
        begin.addProperty(GitRebaseParamEnum.UPSTREAM.key(), "master");
        new GitRebaseTool().handle(new ToolRequestArguments(begin), session);

        JsonObject cont = base();
        cont.addProperty(GitRebaseParamEnum.OPERATION.key(), "CONTINUE");
        String c = new GitRebaseTool().handle(new ToolRequestArguments(cont), session);
        assertTrue(!c.contains("Invalid operation"), "CONTINUE must be wired, not rejected: " + c);

        JsonObject abort = base();
        abort.addProperty(GitRebaseParamEnum.OPERATION.key(), "ABORT");
        new GitRebaseTool().handle(new ToolRequestArguments(abort), session);
        new GitRebaseTool().handle(new ToolRequestArguments(begin), session);

        JsonObject skip = base();
        skip.addProperty(GitRebaseParamEnum.OPERATION.key(), "SKIP");
        String s = new GitRebaseTool().handle(new ToolRequestArguments(skip), session);
        assertTrue(!s.contains("Invalid operation"), "SKIP must be wired, not rejected: " + s);
    }

    // ---- GitCherryPick ----
    @Test
    void gitCherryPickBeginAppliesCommit() throws Exception {
        git(repo, "checkout", "-b", "source");
        Files.writeString(repo.resolve("cherry.txt"), "c\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "cherry commit");
        String sha = git(repo, "rev-parse", "HEAD").strip();
        git(repo, "checkout", "master");

        JsonObject begin = base();
        begin.addProperty(GitCherryPickParamEnum.OPERATION.key(), "BEGIN");
        begin.add(GitCherryPickParamEnum.REVISIONS.key(), arr(sha));
        String b = new GitCherryPickTool().handle(new ToolRequestArguments(begin), session);

        assertTrue(!b.contains("Invalid operation"), "BEGIN must not be rejected: " + b);
        assertTrue(git(repo, "log", "--oneline", "-1").contains("cherry commit"),
                "cherry-pick must create a commit: " + git(repo, "log", "--oneline", "-1"));
    }

    @Test
    void gitCherryPickQuitAndAbortRecoverFromConflict() throws Exception {
        Files.writeString(repo.resolve("f.txt"), "base\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "f base");
        git(repo, "checkout", "-b", "source");
        Files.writeString(repo.resolve("f.txt"), "source\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "f source change");
        String sha = git(repo, "rev-parse", "HEAD").strip();
        git(repo, "checkout", "master");
        Files.writeString(repo.resolve("f.txt"), "master\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "f master change");

        JsonObject begin = base();
        begin.addProperty(GitCherryPickParamEnum.OPERATION.key(), "BEGIN");
        begin.add(GitCherryPickParamEnum.REVISIONS.key(), arr(sha));
        String begun = new GitCherryPickTool().handle(new ToolRequestArguments(begin), session);
        assertTrue(!begun.contains("Invalid operation"), begun);

        JsonObject quit = base();
        quit.addProperty(GitCherryPickParamEnum.OPERATION.key(), "QUIT");
        String q = new GitCherryPickTool().handle(new ToolRequestArguments(quit), session);
        assertTrue(!q.contains("Invalid operation"), "QUIT must be wired, not rejected: " + q);

        new GitCherryPickTool().handle(new ToolRequestArguments(begin), session);
        JsonObject abort = base();
        abort.addProperty(GitCherryPickParamEnum.OPERATION.key(), "ABORT");
        String a = new GitCherryPickTool().handle(new ToolRequestArguments(abort), session);
        assertTrue(!a.contains("Invalid operation"), "ABORT must be wired, not rejected: " + a);
        assertTrue(git(repo, "log", "--oneline", "-1").contains("f master change"),
                "ABORT must restore the pre-pick HEAD: " + git(repo, "log", "--oneline", "-1"));
    }

    // ---- GitStash ----
    @Test
    void gitStashPushListPop() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "dirty");
        Files.writeString(repo.resolve("untracked.txt"), "u");
        JsonObject push = base();
        push.addProperty(GitStashParamEnum.ACTION.key(), "push");
        push.addProperty(GitStashParamEnum.MESSAGE.key(), "my stash");
        push.addProperty(GitStashParamEnum.INCLUDE_UNTRACKED.key(), true);
        String p = new GitStashTool().handle(new ToolRequestArguments(push), session);
        assertTrue(p.contains("Stashed"), p);
        assertTrue(git(repo, "status", "--porcelain").isBlank(), git(repo, "status", "--porcelain"));

        JsonObject list = base();
        list.addProperty(GitStashParamEnum.ACTION.key(), "list");
        String l = new GitStashTool().handle(new ToolRequestArguments(list), session);
        assertTrue(l.contains("my stash"), "list must show the pushed stash message: " + l);

        JsonObject pop = base();
        pop.addProperty(GitStashParamEnum.ACTION.key(), "pop");
        pop.addProperty(GitStashParamEnum.INDEX.key(), 0);
        String po = new GitStashTool().handle(new ToolRequestArguments(pop), session);
        assertTrue(po.contains("Popped"), po);
        assertTrue(Files.readString(repo.resolve("a.txt")).strip().equals("dirty"),
                "pop must restore the stashed content; porcelain: " + git(repo, "status", "--porcelain"));
        assertTrue(Files.exists(repo.resolve("untracked.txt")),
                "includeUntracked pop must restore the untracked file; porcelain: " + git(repo, "status", "--porcelain"));
        assertTrue(git(repo, "status", "--porcelain").contains("a.txt"),
                "the stashed change must be visible in porcelain after pop: " + git(repo, "status", "--porcelain"));
    }

    @Test
    void gitStashApplyLeavesEntryAndDropRemovesIt() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "dirty");
        JsonObject push = base();
        push.addProperty(GitStashParamEnum.ACTION.key(), "push");
        push.addProperty(GitStashParamEnum.MESSAGE.key(), "stash one");
        String p = new GitStashTool().handle(new ToolRequestArguments(push), session);
        assertTrue(p.contains("Stashed"), p);

        JsonObject list = base();
        list.addProperty(GitStashParamEnum.ACTION.key(), "list");

        JsonObject apply = base();
        apply.addProperty(GitStashParamEnum.ACTION.key(), "apply");
        apply.addProperty(GitStashParamEnum.INDEX.key(), 0);
        String ap = new GitStashTool().handle(new ToolRequestArguments(apply), session);
        assertTrue(ap.contains("Applied"), ap);
        assertTrue(Files.readString(repo.resolve("a.txt")).strip().equals("dirty"),
                "apply must restore the stashed content; porcelain: " + git(repo, "status", "--porcelain"));
        assertTrue(new GitStashTool().handle(new ToolRequestArguments(list), session).contains("stash one"),
                "apply must leave the entry in the stash");

        JsonObject drop = base();
        drop.addProperty(GitStashParamEnum.ACTION.key(), "drop");
        drop.addProperty(GitStashParamEnum.INDEX.key(), 0);
        String dr = new GitStashTool().handle(new ToolRequestArguments(drop), session);
        assertTrue(dr.contains("Dropped"), dr);
        assertTrue(!new GitStashTool().handle(new ToolRequestArguments(list), session).contains("stash one"),
                "drop must remove the entry");
    }

    @Test
    void gitStashDefaultActionIsPushAndIndexSelectsEntry() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "one");
        JsonObject first = base();
        first.addProperty(GitStashParamEnum.MESSAGE.key(), "first");
        assertTrue(new GitStashTool().handle(new ToolRequestArguments(first), session).contains("Stashed"),
                "omitted action must default to push");
        Files.writeString(repo.resolve("a.txt"), "two");
        JsonObject second = base();
        second.addProperty(GitStashParamEnum.ACTION.key(), "push");
        second.addProperty(GitStashParamEnum.MESSAGE.key(), "second");
        assertTrue(new GitStashTool().handle(new ToolRequestArguments(second), session).contains("Stashed"));

        JsonObject popLater = base();
        popLater.addProperty(GitStashParamEnum.ACTION.key(), "pop");
        popLater.addProperty(GitStashParamEnum.INDEX.key(), 1);
        String pp = new GitStashTool().handle(new ToolRequestArguments(popLater), session);
        assertTrue(pp.contains("Popped"), pp);
        assertTrue(Files.readString(repo.resolve("a.txt")).strip().equals("one"),
                "index 1 must select the older stash whose content was 'one'; porcelain: "
                + git(repo, "status", "--porcelain"));
    }

    // ---- GitTag ----
    @Test
    void gitTagCreateListDelete() throws Exception {
        JsonObject create = base();
        create.addProperty(GitTagParamEnum.ACTION.key(), "create");
        create.addProperty(GitTagParamEnum.NAME.key(), "v1.0");
        create.addProperty(GitTagParamEnum.MESSAGE.key(), "release one");
        String c = new GitTagTool().handle(new ToolRequestArguments(create), session);
        assertTrue(c.contains("Created tag: v1.0"), c);
        assertTrue(git(repo, "tag").contains("v1.0"), git(repo, "tag"));

        JsonObject list = base();
        list.addProperty(GitTagParamEnum.ACTION.key(), "list");
        assertTrue(new GitTagTool().handle(new ToolRequestArguments(list), session).contains("v1.0"));

        JsonObject del = base();
        del.addProperty(GitTagParamEnum.ACTION.key(), "delete");
        del.addProperty(GitTagParamEnum.NAME.key(), "v1.0");
        String d = new GitTagTool().handle(new ToolRequestArguments(del), session);
        assertTrue(d.contains("Deleted tag: v1.0"), d);
        assertTrue(!git(repo, "tag").contains("v1.0"), git(repo, "tag"));
    }

    // ---- GitRemote ----
    @Test
    void gitRemoteAddListRemove() throws Exception {
        JsonObject add = base();
        add.addProperty(GitRemoteParamEnum.ACTION.key(), "add");
        add.addProperty(GitRemoteParamEnum.NAME.key(), "origin2");
        add.addProperty(GitRemoteParamEnum.URL.key(), "/tmp/audit-nonexistent.git");
        String a = new GitRemoteTool().handle(new ToolRequestArguments(add), session);
        assertTrue(a.contains("Added remote: origin2"), a);
        assertTrue(git(repo, "remote").contains("origin2"), git(repo, "remote"));

        JsonObject list = base();
        list.addProperty(GitRemoteParamEnum.ACTION.key(), "list");
        String l = new GitRemoteTool().handle(new ToolRequestArguments(list), session);
        assertTrue(l.contains("origin2"), l);

        JsonObject rem = base();
        rem.addProperty(GitRemoteParamEnum.ACTION.key(), "remove");
        rem.addProperty(GitRemoteParamEnum.NAME.key(), "origin2");
        String r = new GitRemoteTool().handle(new ToolRequestArguments(rem), session);
        assertTrue(r.contains("Removed remote: origin2"), r);
        assertTrue(!git(repo, "remote").contains("origin2"), git(repo, "remote"));
    }

    // ---- GitFetch / GitPush / GitPull (against a local bare remote) ----
    @Test
    void gitPushFetchPullAgainstBareRemote() throws Exception {
        Path bare = tempDir.resolve("bare.git");
        Files.createDirectories(bare);
        git(bare, "init", "--bare");

        git(repo, "checkout", "-b", "dev");
        Files.writeString(repo.resolve("dev.txt"), "dev\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "dev commit");

        JsonObject add = base();
        add.addProperty(GitRemoteParamEnum.ACTION.key(), "add");
        add.addProperty(GitRemoteParamEnum.NAME.key(), "origin");
        add.addProperty(GitRemoteParamEnum.URL.key(), bare.toString());
        assertTrue(new GitRemoteTool().handle(new ToolRequestArguments(add), session).contains("Added remote"),
                "add remote for bare origin");

        JsonObject push = base();
        push.addProperty(GitPushParamEnum.REMOTE.key(), "origin");
        push.addProperty(GitPushParamEnum.BRANCH.key(), "dev");
        String pushed = new GitPushTool().handle(new ToolRequestArguments(push), session);
        assertTrue(pushed.contains("complete"), "push: " + pushed);
        assertTrue(git(bare, "for-each-ref").contains("dev"), git(bare, "for-each-ref"));

        String cloneDir = repo.getParent().resolve("clone").toString();
        git(repo.getParent(), "clone", bare.toString(), cloneDir);
        Path clone = Path.of(cloneDir);
        git(clone, "config", "user.name", "Audit");
        git(clone, "config", "user.email", "audit@example.com");
        git(clone, "checkout", "dev");
        Files.writeString(clone.resolve("remote.txt"), "r\n");
        git(clone, "add", ".");
        git(clone, "commit", "-m", "remote advance");
        git(clone, "push", "origin", "dev");

        JsonObject fetch = base();
        fetch.addProperty(GitFetchParamEnum.REMOTE.key(), "origin");
        String fetched = new GitFetchTool().handle(new ToolRequestArguments(fetch), session);
        assertTrue(fetched.contains("complete"), "fetch: " + fetched);
        assertTrue(git(repo, "log", "origin/dev", "--oneline", "-1").contains("remote advance"),
                "fetch must update the remote-tracking ref: " + git(repo, "log", "origin/dev", "--oneline", "-1"));

        JsonObject pull = base();
        pull.addProperty(GitPullParamEnum.REMOTE.key(), "origin");
        String pulled = new GitPullTool().handle(new ToolRequestArguments(pull), session);
        assertTrue(pulled.contains("complete"), "pull: " + pulled);
        assertTrue(git(repo, "log", "--oneline", "-1").contains("remote advance"),
                "pull must fast-forward local dev: " + git(repo, "log", "--oneline", "-1"));
    }

    // ---- helpers ----
    private JsonObject base() {
        JsonObject o = new JsonObject();
        o.addProperty(GitCommonParamEnum.PROJECT_PATH.key(), projectPath);
        return o;
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
