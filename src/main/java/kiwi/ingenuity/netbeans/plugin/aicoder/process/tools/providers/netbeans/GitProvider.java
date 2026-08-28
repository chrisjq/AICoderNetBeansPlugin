package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.DateUtil;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.libs.git.GitBlameResult;
import org.netbeans.libs.git.GitBranch;
import org.netbeans.libs.git.GitCherryPickResult;
import org.netbeans.libs.git.GitClient;
import org.netbeans.libs.git.GitException;
import org.netbeans.libs.git.GitLineDetails;
import org.netbeans.libs.git.GitMergeResult;
import org.netbeans.libs.git.GitPullResult;
import org.netbeans.libs.git.GitPushResult;
import org.netbeans.libs.git.GitRebaseResult;
import org.netbeans.libs.git.GitRefUpdateResult;
import org.netbeans.libs.git.GitRemoteConfig;
import org.netbeans.libs.git.GitRepository;
import org.netbeans.libs.git.GitRevertResult;
import org.netbeans.libs.git.GitRevisionInfo;
import org.netbeans.libs.git.GitStatus;
import org.netbeans.libs.git.GitTag;
import org.netbeans.libs.git.GitTransportUpdate;
import org.netbeans.libs.git.GitUser;
import org.netbeans.libs.git.SearchCriteria;
import org.netbeans.libs.git.progress.ProgressMonitor;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

public class GitProvider {

    private static final Logger LOG = Logger.getLogger(GitProvider.class.getName());

    /**
     * Message given to a stash push when the caller supplies none. Documented as the default in GitStash's schema, so
     * the two must agree.
     */
    public static final String STASH_DEFAULT_MESSAGE = "WIP";

    private static final ProgressMonitor NULL_PM = new ProgressMonitor() {
        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public void started(String s) {
        }

        @Override
        public void finished() {
        }

        @Override
        public void preparationsFailed(String s) {
        }

        @Override
        public void notifyError(String s) {
            LOG.log(Level.SEVERE, "Git error: {0}", s);
        }

        @Override
        public void notifyWarning(String s) {
            LOG.log(Level.WARNING, "Git: {0}", s);
        }
    };
    private static final List<String> PROTECTED_BRANCHES
            = List.of("main", "master", "production", "release");

    public static String getGitStatus(String projectPath) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            StringBuilder sb = new StringBuilder();
            Map<String, GitBranch> branches = client.getBranches(false, NULL_PM);
            for (Map.Entry<String, GitBranch> e : branches.entrySet()) {
                if (e.getValue().isActive()) {
                    sb.append("## ").append(e.getKey()).append('\n');
                    break;
                }
            }
            Map<File, GitStatus> statuses = client.getStatus(new File[]{root}, NULL_PM);
            for (Map.Entry<File, GitStatus> e : statuses.entrySet()) {
                GitStatus s = e.getValue();
                if (!s.isTracked()) {
                    sb.append("?? ").append(s.getRelativePath()).append('\n');
                    continue;
                }
                char idx = statusChar(s.getStatusHeadIndex());
                char wt = statusChar(s.getStatusIndexWC());
                if (idx == ' ' && wt == ' ') {
                    continue;
                }
                sb.append(idx).append(wt).append(' ').append(s.getRelativePath()).append('\n');
            }
            return sb.length() == 0 ? "nothing to commit, working tree clean" : sb.toString();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "getGitStatus error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String getGitDiff(String projectPath, boolean staged) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GitClient.DiffMode mode = staged
                    ? GitClient.DiffMode.HEAD_VS_INDEX
                    : GitClient.DiffMode.INDEX_VS_WORKINGTREE;
            client.exportDiff(new File[]{root}, mode, baos, NULL_PM);
            String result = baos.toString(StandardCharsets.UTF_8);
            return result.isBlank() ? "(no changes)" : result;
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "getGitDiff error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitAdd(String projectPath, List<String> files) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            File[] toAdd = resolveFiles(root, files);
            client.add(toAdd, NULL_PM);
            FileUtil.refreshFor(root);
            return "Added " + toAdd.length + " path(s)";
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "gitAdd error", e);
            return "Invalid path: " + e.getMessage();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitAdd error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitCommit(String projectPath, String message, List<String> files, String sessionId) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            File[] commitTargets = resolveFiles(root, files);
            if (!areCommitTargetsAllowed(commitTargets, McpServerRegistry.getServer(), sessionId)) {
                return "Error: commit paths are not within the allowed project directories";
            }
            if (files != null && !files.isEmpty()) {
                client.add(commitTargets, NULL_PM);
            }
            GitUser user;
            try {
                user = client.getUser();
            }
            catch (GitException ex) {
                user = null;
            }
            GitRevisionInfo info = client.commit(commitTargets, message, user, user, NULL_PM);
            FileUtil.refreshFor(root);
            String rev = info.getRevision();
            return "Committed: " + rev.substring(0, Math.min(7, rev.length())) + " " + info.getShortMessage();
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "gitCommit error", e);
            return "Invalid path: " + e.getMessage();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitCommit error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitLog(String projectPath, int limit, String file, boolean follow) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        // When a file is given, scope the log to it (relative paths resolve against
        // the project root). setFollowRenames mirrors `git log --follow`, which is
        // only meaningful for a single path, so it is applied only alongside a file.
        File target = null;
        if (file != null && !file.isBlank()) {
            File f = new File(file);
            target = f.isAbsolute() ? f : new File(root, file);
        }
        if (target != null && !isWithinRepository(gitRoot, target)) {
            return "File is outside repository: " + file;
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            SearchCriteria criteria = new SearchCriteria();
            criteria.setLimit(limit > 0 ? limit : 20);
            if (target != null) {
                criteria.setFiles(new File[]{target});
                criteria.setFollowRenames(follow);
            }
            GitRevisionInfo[] revisions = client.log(criteria, NULL_PM);
            if (revisions.length == 0) {
                return "No commits found";
            }
            StringBuilder sb = new StringBuilder();
            for (GitRevisionInfo rev : revisions) {
                String hash = rev.getRevision();
                sb.append(hash.substring(0, Math.min(7, hash.length())))
                        .append(' ').append(rev.getShortMessage()).append('\n');
            }
            return sb.toString().strip();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitLog error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitPush(String projectPath, String remote, String branch) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        String remoteName = (remote != null && !remote.isBlank()) ? remote : "origin";
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            String branchName = branch;
            if (branchName == null || branchName.isBlank()) {
                Map<String, GitBranch> branches = client.getBranches(false, NULL_PM);
                for (Map.Entry<String, GitBranch> e : branches.entrySet()) {
                    if (e.getValue().isActive()) {
                        branchName = e.getKey();
                        break;
                    }
                }
            }
            if (branchName == null) {
                return "No active branch found";
            }
            if (isProtectedBranch(branchName)) {
                return "Push to protected branch '" + branchName + "' is blocked. Use a pull request instead.";
            }
            String refspec = "refs/heads/" + branchName + ":refs/heads/" + branchName;
            GitPushResult result = client.push(remoteName, List.of(refspec), List.of(), NULL_PM);
            return formatTransportUpdates("Push", result.getRemoteRepositoryUpdates());
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitPush error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitPull(String projectPath, String remote) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        String remoteName = (remote != null && !remote.isBlank()) ? remote : "origin";
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            String branchName = null;
            Map<String, GitBranch> branches = client.getBranches(false, NULL_PM);
            for (Map.Entry<String, GitBranch> e : branches.entrySet()) {
                if (e.getValue().isActive()) {
                    branchName = e.getKey();
                    break;
                }
            }
            if (branchName == null) {
                return "No active branch found";
            }
            String fetchRefSpec = "+refs/heads/*:refs/remotes/" + remoteName + "/*";
            String branchToMerge = "refs/remotes/" + remoteName + "/" + branchName;
            GitPullResult result = client.pull(remoteName, List.of(fetchRefSpec), branchToMerge, NULL_PM);
            Map<String, GitTransportUpdate> fetchUpdates = result.getFetchResult();
            if (!areTransportUpdatesSuccessful(fetchUpdates)) {
                return formatTransportUpdates("Pull fetch", fetchUpdates);
            }
            GitMergeResult merge = result.getMergeResult();
            if (merge == null) {
                return "Pull complete";
            }
            GitMergeResult.MergeStatus status = merge.getMergeStatus();
            if (isSuccessfulMergeStatus(status)) {
                return "Pull complete: " + status;
            }
            return "Pull failed: " + status;
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitPull error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitCheckout(String projectPath, String branchOrRevision, boolean createNew) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        if (!isValidBranchName(branchOrRevision)) {
            return "Invalid branch name: '" + branchOrRevision + "'";
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            if (createNew) {
                client.createBranch(branchOrRevision, "HEAD", NULL_PM);
            }
            client.checkoutRevision(branchOrRevision, true, NULL_PM);
            FileUtil.refreshFor(root);
            return "Switched to " + (createNew ? "new branch " : "") + branchOrRevision;
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitCheckout error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitBranch(String projectPath, boolean all, String newBranch) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            if (newBranch != null && !newBranch.isBlank()) {
                if (!isValidBranchName(newBranch)) {
                    return "Invalid branch name: '" + newBranch + "'";
                }
                GitBranch created = client.createBranch(newBranch, "HEAD", NULL_PM);
                return "Created branch: " + created.getName();
            }
            Map<String, GitBranch> branches = client.getBranches(all, NULL_PM);
            if (branches.isEmpty()) {
                return "No branches found";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, GitBranch> e : branches.entrySet()) {
                sb.append(e.getValue().isActive() ? "* " : "  ")
                        .append(e.getKey()).append('\n');
            }
            return sb.toString().strip();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitBranch error", e);
            return "Git error: " + e.getMessage();
        }
    }

    /**
     * Guarded here rather than per-caller so every call site — including any added later — is protected at once.
     * {@code FileOwnerQuery.getOwner()} can throw {@code ExceptionInInitializerError}/{@code NoClassDefFoundError} when
     * the IDE's ProjectManager Lookup is unavailable; those are Errors, not Exceptions, so {@code catch(Throwable)} is
     * required to contain them.
     * <p>
     * Most callers (RefactoringProvider's write/edit/delete/copy/move) treat this as a best-effort cosmetic refresh
     * after an already-completed file operation and ignore the returned string either way — a stale VCS badge is a much
     * smaller problem than reporting a completed operation as failed, so swallowing the failure into a returned message
     * here is CORRECT for them, not the false-success pattern the rest of this review targets. Do not "fix" this back
     * into throwing. RefreshFileStatusTool is the one caller where this refresh IS the operation, and it returns this
     * method's result directly — for that caller, this branch is what turns an uncaught Error into a real, reportable
     * failure message instead.
     */
    public static String refreshVcsStatus(String filePath) {
        try {
            if (filePath != null && !filePath.isBlank()) {
                FileObject fo = FileUtils.resolveByPath(filePath);
                if (fo != null) {
                    Project p = FileOwnerQuery.getOwner(fo);
                    if (p != null) {
                        File dir = FileUtil.toFile(p.getProjectDirectory());
                        if (dir != null) {
                            FileUtil.refreshFor(dir);
                            return "Refreshed VCS status for project: " + p.getProjectDirectory().getName();
                        }
                    }
                    File f = FileUtil.toFile(fo);
                    if (f != null) {
                        FileUtil.refreshFor(f);
                    }
                    return "Refreshed VCS status for: " + filePath;
                }
                return "File not found: " + filePath;
            }
            Project[] projects = OpenProjects.getDefault().getOpenProjects();
            if (projects.length == 0) {
                return "No open projects to refresh";
            }
            int count = 0;
            for (Project p : projects) {
                File dir = FileUtil.toFile(p.getProjectDirectory());
                if (dir != null) {
                    FileUtil.refreshFor(dir);
                    count++;
                }
            }
            return "Refreshed VCS status for " + count + " project" + (count != 1 ? "s" : "");
        }
        catch (Throwable t) {
            // A headless JVM (the unit-test harness) registers no ProjectManagerImplementation in global Lookup, so
            // FileOwnerQuery's first call throws ExceptionInInitializerError and every later one
            // NoClassDefFoundError once the class is poisoned. That is EXPECTED there and says nothing about this
            // call, so it drops to FINE: at WARNING it printed a full stack trace roughly ten times per suite run
            // and buried the real failures in the build output.
            // Matched on the CAUSE, not merely the type: demoting every NoClassDefFoundError here would equally
            // silence an unrelated missing class on this call path, which inside a running IDE is a real defect
            // worth shouting about. Only the known ProjectManager-initialisation failure is quietened; anything
            // else — including a different classloading failure — still logs WARNING.
            // Note this is deliberately invisible at the IDE log's default threshold, so absence of this warning
            // does NOT prove the refresh succeeded; the returned string is what callers and the user actually see.
            String cause = String.valueOf(t.getMessage());
            boolean projectManagerUnavailable = (t instanceof NoClassDefFoundError
                    || t instanceof ExceptionInInitializerError)
                    && cause.contains("ProjectManager");
            LOG.log(projectManagerUnavailable ? Level.FINE : Level.WARNING,
                    "refreshVcsStatus failed for " + filePath, t);
            return "Could not refresh VCS status: " + t.getMessage();
        }
    }

    private static File getOpenProjectRoot() {
        Project main = OpenProjects.getDefault().getMainProject();
        if (main != null) {
            File dir = FileUtil.toFile(main.getProjectDirectory());
            if (dir != null) {
                return dir;
            }
        }
        Project[] projects = OpenProjects.getDefault().getOpenProjects();
        if (projects.length == 0) {
            return null;
        }
        return FileUtil.toFile(projects[0].getProjectDirectory());
    }

    /**
     * Resolves the working root for a git operation. {@code projectPath} is required and is used directly as the target
     * project/repository root (relative paths are resolved against the default project root, as a convenience). This
     * lets a caller always explicitly target any open project's repository, or any repo on disk — instead of relying on
     * NetBeans' "main project" notion, which is ambiguous (and can be plain wrong) whenever multiple projects/repos are
     * open at once, or when the git repository lives outside any open project's directory.
     */
    private static File resolveRoot(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }
        File dir = new File(projectPath);
        if (!dir.isAbsolute()) {
            File defaultRoot = getOpenProjectRoot();
            dir = defaultRoot != null ? new File(defaultRoot, projectPath) : dir.getAbsoluteFile();
        }
        return dir.isDirectory() ? dir : null;
    }

    private static String noRepoError(String projectPath) {
        return (projectPath != null && !projectPath.isBlank())
                ? "Repository not found: " + projectPath
                : GitCommonParamEnum.PROJECT_PATH.key() + " is required";
    }

    /**
     * Resolves the best root directory for locating a specific file's git repository: the NetBeans project that owns
     * the file (so a file in a non-default open project resolves to its own repo), falling back to the file's own
     * directory so {@link #findGitRoot} can still walk upward to find {@code .git}. Only used by {@link #gitBlame} when
     * projectPath is omitted, since blame is a single-file operation and the project can be determined from the file
     * itself.
     * <p>
     * The owner lookup is contained by {@code catch(Throwable)} for the same reason as {@link #refreshVcsStatus}:
     * {@code FileOwnerQuery.getOwner()} throws {@code ExceptionInInitializerError}/{@code NoClassDefFoundError} —
     * Errors, not Exceptions — when the IDE's ProjectManager Lookup is unavailable. Unguarded, that escaped
     * {@code gitBlame} as a raw Error whenever a caller passed an absolute filePath without a projectPath. Falling
     * through to the file's own directory is a genuine degradation and not a swallowed failure: {@link #findGitRoot}
     * still walks upward from there and finds the same repository in every layout except a file owned by a project that
     * sits below its own git root.
     */
    private static File resolveRootForFile(File file) {
        FileObject fo = FileUtils.resolveByFile(file);
        if (fo != null) {
            try {
                Project owner = FileOwnerQuery.getOwner(fo);
                if (owner != null) {
                    File dir = FileUtil.toFile(owner.getProjectDirectory());
                    if (dir != null) {
                        return dir;
                    }
                }
            }
            catch (Throwable t) {
                // Same discrimination as refreshVcsStatus: the headless ProjectManager-initialisation failure is
                // EXPECTED and drops to FINE, but any other classloading failure on this path is a real defect inside a
                // running IDE and must still be shouted about. Do not demote this to a blanket FINE.
                String cause = String.valueOf(t.getMessage());
                boolean projectManagerUnavailable = (t instanceof NoClassDefFoundError
                        || t instanceof ExceptionInInitializerError)
                        && cause.contains("ProjectManager");
                // The WARNING half is a real defect worth surfacing, so it is NOT gated. Only the expected-and-noisy
                // FINE half sits behind the debug flag.
                if (!projectManagerUnavailable) {
                    LOG.log(Level.WARNING,
                            "Project owner lookup failed for " + file + ", falling back to its own directory", t);
                }
                else if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.FINE,
                            "Project owner lookup unavailable for " + file + ", falling back to its own directory", t);
                }
            }
        }
        return file.isDirectory() ? file : file.getParentFile();
    }

    private static File findGitRoot(File dir) {
        File f = dir;
        while (f != null) {
            if (new File(f, ".git").exists()) {
                return f;
            }
            f = f.getParentFile();
        }
        return null;
    }

    private static File[] resolveFiles(File root, List<String> paths) throws IOException {
        if (paths == null || paths.isEmpty()
                || (paths.size() == 1 && ".".equals(paths.get(0)))) {
            return new File[]{root};
        }
        List<File> result = new ArrayList<>();
        for (String path : paths) {
            if (".".equals(path)) {
                result.add(root);
            }
            else {
                File f = new File(path);
                if (!f.isAbsolute()) {
                    f = new File(root, path);
                }
                if (!isWithinRepository(root, f)) {
                    throw new IOException("Path is outside the repository: " + path);
                }
                result.add(f);
            }
        }
        return result.toArray(File[]::new);
    }

    static boolean areCommitTargetsAllowed(File[] targets, McpHookServer server, String sessionId) {
        if (server == null || sessionId == null) {
            return false;
        }
        for (File target : targets) {
            if (!server.isFileAllowed(sessionId, target.getPath())) {
                return false;
            }
        }
        return true;
    }

    static String formatTransportResults(String operation, Map<String, GitRefUpdateResult> results) {
        if (results == null || results.isEmpty()) {
            return operation + " complete (nothing to update)";
        }
        int successes = 0;
        for (GitRefUpdateResult result : results.values()) {
            if (isSuccessfulTransportResult(result)) {
                successes++;
            }
        }
        String prefix;
        if (successes == results.size()) {
            prefix = operation + " complete:";
        }
        else if (successes == 0) {
            prefix = operation + " failed:";
        }
        else {
            prefix = operation + " partially completed:";
        }
        StringBuilder sb = new StringBuilder(prefix).append('\n');
        for (Map.Entry<String, GitRefUpdateResult> entry : results.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString().strip();
    }

    static boolean isSuccessfulTransportResult(GitRefUpdateResult result) {
        return result == GitRefUpdateResult.NO_CHANGE
                || result == GitRefUpdateResult.NEW
                || result == GitRefUpdateResult.FORCED
                || result == GitRefUpdateResult.FAST_FORWARD
                || result == GitRefUpdateResult.UP_TO_DATE
                || result == GitRefUpdateResult.RENAMED
                || result == GitRefUpdateResult.OK;
    }

    static boolean isSuccessfulMergeStatus(GitMergeResult.MergeStatus status) {
        return status == GitMergeResult.MergeStatus.FAST_FORWARD
                || status == GitMergeResult.MergeStatus.ALREADY_UP_TO_DATE
                || status == GitMergeResult.MergeStatus.MERGED;
    }

    private static String formatTransportUpdates(String operation, Map<String, GitTransportUpdate> updates) {
        Map<String, GitRefUpdateResult> results = new LinkedHashMap<>();
        if (updates != null) {
            for (Map.Entry<String, GitTransportUpdate> entry : updates.entrySet()) {
                results.put(entry.getKey(), entry.getValue().getResult());
            }
        }
        return formatTransportResults(operation, results);
    }

    private static boolean areTransportUpdatesSuccessful(Map<String, GitTransportUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return true;
        }
        for (GitTransportUpdate update : updates.values()) {
            if (!isSuccessfulTransportResult(update.getResult())) {
                return false;
            }
        }
        return true;
    }

    private static char statusChar(GitStatus.Status status) {
        if (status == null) {
            return ' ';
        }
        if (status == GitStatus.Status.STATUS_ADDED) {
            return 'A';
        }
        if (status == GitStatus.Status.STATUS_MODIFIED) {
            return 'M';
        }
        if (status == GitStatus.Status.STATUS_REMOVED) {
            return 'D';
        }
        if (status == GitStatus.Status.STATUS_IGNORED) {
            return '!';
        }
        return ' ';
    }

    public static String gitDeleteBranch(String projectPath, String branch, boolean force) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        if (!isValidBranchName(branch)) {
            return "Invalid branch name: '" + branch + "'";
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            client.deleteBranch(branch, force, NULL_PM);
            FileUtil.refreshFor(root);
            return "Deleted branch: " + branch;
        }
        catch (GitException.NotMergedException e) {
            return "Branch not merged. Use force=true to delete anyway.";
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitDeleteBranch error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitStash(String projectPath, GitStashActionEnum action, int index, String message, boolean includeUntracked) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        GitStashActionEnum act = action != null ? action : GitStashActionEnum.DEFAULT;
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            // A switch expression over the enum, so adding an action to
            // GitStashActionEnum without handling it here fails to compile.
            // The old string switch sent anything unrecognised to its default
            // branch, which stashed the working tree.
            return switch (act) {
                case LIST -> {
                    GitRevisionInfo[] stashes = client.stashList(NULL_PM);
                    if (stashes.length == 0) {
                        yield "No stashes";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < stashes.length; i++) {
                        sb.append("stash@{").append(i).append("}: ").append(stashes[i].getShortMessage()).append('\n');
                    }
                    yield sb.toString().strip();
                }
                case POP -> {
                    client.stashApply(index, true, NULL_PM);
                    FileUtil.refreshFor(root);
                    yield "Popped stash@{" + index + "}";
                }
                case APPLY -> {
                    client.stashApply(index, false, NULL_PM);
                    FileUtil.refreshFor(root);
                    yield "Applied stash@{" + index + "}";
                }
                case DROP -> {
                    client.stashDrop(index, NULL_PM);
                    yield "Dropped stash@{" + index + "}";
                }
                case PUSH -> {
                    String msg = (message != null && !message.isBlank()) ? message : STASH_DEFAULT_MESSAGE;
                    GitRevisionInfo info = client.stashSave(msg, includeUntracked, NULL_PM);
                    yield info == null ? "Nothing to stash" : "Stashed: " + info.getShortMessage();
                }
            };
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitStash error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitFetch(String projectPath, String remote) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        String remoteName = (remote != null && !remote.isBlank()) ? remote : "origin";
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            return formatTransportUpdates("Fetch", client.fetch(remoteName, NULL_PM));
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitFetch error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitReset(String projectPath, List<String> files, String revision, String resetType) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        String rev = (revision != null && !revision.isBlank()) ? revision : "HEAD";
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            if (files != null && !files.isEmpty() && !(files.size() == 1 && ".".equals(files.get(0)))) {
                client.reset(resolveFiles(root, files), rev, true, NULL_PM);
                FileUtil.refreshFor(root);
                return "Reset " + files.size() + " file(s) to " + rev;
            }
            else {
                GitClient.ResetType type;
                try {
                    // Locale.ROOT, not the default locale. The CALLER's value is what gets
                    // folded, and callers pass lowercase ("mixed", "soft"). Under a Turkish
                    // locale "mixed" uppercases to "MİXED" — dotted capital I — and valueOf
                    // throws, so the requested reset type is silently replaced by the MIXED
                    // fallback below. Verified against a real JDK, not assumed.
                    type = GitClient.ResetType.valueOf(
                            (resetType != null ? resetType : "MIXED").toUpperCase(Locale.ROOT));
                }
                catch (IllegalArgumentException ex) {
                    type = GitClient.ResetType.MIXED;
                }
                client.reset(rev, type, NULL_PM);
                FileUtil.refreshFor(root);
                return "Reset " + type.name().toLowerCase() + " to " + rev;
            }
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "gitReset error", e);
            return "Invalid path: " + e.getMessage();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitReset error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitMerge(String projectPath, String branch) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        if (!isValidBranchName(branch)) {
            return "Invalid branch name: '" + branch + "'";
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            GitMergeResult result = client.merge(branch, NULL_PM);
            String status = result.getMergeStatus().toString();
            Collection<File> conflicts = result.getConflicts();
            if (conflicts != null && !conflicts.isEmpty()) {
                StringBuilder sb = new StringBuilder("Merge ").append(status).append(" — conflicts:\n");
                for (File f : conflicts) {
                    sb.append("  ").append(f.getName()).append('\n');
                }
                return sb.toString().strip();
            }
            FileUtil.refreshFor(root);
            return "Merge " + status;
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitMerge error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitShow(String projectPath, String revision) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        String rev = (revision != null && !revision.isBlank()) ? revision : "HEAD";
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            GitRevisionInfo info = client.log(rev, NULL_PM);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            client.exportCommit(info.getRevision(), baos, NULL_PM);
            String diff = baos.toString(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append("commit ").append(info.getRevision()).append('\n');
            GitUser author = info.getAuthor();
            if (author != null) {
                sb.append("Author: ").append(author.getName()).append(" <").append(author.getEmailAddress()).append(">\n");
            }
            sb.append("Date:   ").append(DateUtil.format(info.getCommitTime())).append('\n');
            sb.append('\n').append(info.getFullMessage()).append('\n');
            if (!diff.isBlank()) {
                sb.append('\n').append(diff);
            }
            return sb.toString().strip();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitShow error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitBlame(String projectPath, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return McpToolPropertyEnum.FILE_PATH.key() + " is required";
        }
        File file = new File(filePath);
        File root;
        if (projectPath != null && !projectPath.isBlank()) {
            root = resolveRoot(projectPath);
        }
        else if (file.isAbsolute()) {
            // projectPath omitted — this is a single-file operation, so the
            // owning project (and therefore its repository) can be
            // determined directly from the file itself.
            root = resolveRootForFile(file);
        }
        else {
            root = null;
        }
        if (root == null) {
            return noRepoError(projectPath);
        }
        if (!file.isAbsolute()) {
            file = new File(root, filePath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        if (!isWithinRepository(gitRoot, file)) {
            return "File is outside repository: " + filePath;
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            GitBlameResult result = client.blame(file, "HEAD", NULL_PM);
            if (result == null) {
                return "No blame info (file not tracked?): " + filePath;
            }
            int lines = result.getLineCount();
            if (lines == 0) {
                return "(empty file)";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines; i++) {
                GitLineDetails d = result.getLineDetails(i);
                if (d == null) {
                    continue;
                }
                org.netbeans.libs.git.GitRevisionInfo revInfo = d.getRevisionInfo();
                String hash = revInfo != null ? revInfo.getRevision() : "0000000";
                String authorName = d.getAuthor() != null ? d.getAuthor().getName() : "?";
                if (authorName.length() > 20) {
                    authorName = authorName.substring(0, 20);
                }
                sb.append(String.format("%-7s %-20s %4d %s%n",
                        hash.substring(0, Math.min(7, hash.length())),
                        authorName, i + 1, d.getContent()));
            }
            return sb.toString().stripTrailing();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitBlame error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitRebase(String projectPath, String upstream, String operation) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        GitClient.RebaseOperationType op;
        try {
            // Locale.ROOT — see gitReset. Lowercase caller input is what breaks: "begin",
            // "continue" and "skip" all contain an i, so a Turkish locale turns them into
            // "BEGİN", "CONTİNUE" and "SKİP" and valueOf rejects them.
            op = GitClient.RebaseOperationType.valueOf(
                    (operation != null ? operation : "BEGIN").toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            return "Invalid operation. Use: BEGIN, CONTINUE, SKIP, ABORT";
        }
        String rev = (upstream != null && !upstream.isBlank()) ? upstream : "HEAD";
        if (!rev.equals("HEAD") && !isValidBranchName(rev)) {
            return "Invalid upstream: '" + rev + "'";
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            GitRebaseResult result = client.rebase(op, rev, NULL_PM);
            String status = result.getRebaseStatus().toString();
            Collection<File> conflicts = result.getConflicts();
            if (conflicts != null && !conflicts.isEmpty()) {
                StringBuilder sb = new StringBuilder("Rebase ").append(status).append(" — conflicts:\n");
                for (File f : conflicts) {
                    sb.append("  ").append(f.getName()).append('\n');
                }
                return sb.toString().strip();
            }
            FileUtil.refreshFor(root);
            return "Rebase " + status;
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitRebase error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitCherryPick(String projectPath, String operation, List<String> revisions) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        GitClient.CherryPickOperation op;
        try {
            // Locale.ROOT — see gitReset. Same exposure as gitRebase: "begin", "continue" and
            // "quit" all contain an i.
            op = GitClient.CherryPickOperation.valueOf(
                    (operation != null ? operation : "BEGIN").toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            return "Invalid operation. Use: BEGIN, CONTINUE, QUIT, ABORT";
        }
        String[] revArray = (revisions != null ? revisions : List.of()).toArray(new String[0]);
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            GitCherryPickResult result = client.cherryPick(op, revArray, NULL_PM);
            String status = result.getCherryPickStatus().toString();
            Collection<File> conflicts = result.getConflicts();
            if (conflicts != null && !conflicts.isEmpty()) {
                StringBuilder sb = new StringBuilder("Cherry-pick ").append(status).append(" — conflicts:\n");
                for (File f : conflicts) {
                    sb.append("  ").append(f.getName()).append('\n');
                }
                return sb.toString().strip();
            }
            FileUtil.refreshFor(root);
            return "Cherry-pick " + status;
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitCherryPick error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitTag(String projectPath, GitTagActionEnum action, String name, String revision, String message) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        GitTagActionEnum act = action != null ? action : GitTagActionEnum.DEFAULT;
        if ((act == GitTagActionEnum.CREATE || act == GitTagActionEnum.DELETE) && (name == null || name.isBlank())) {
            return "Error: name is required for action=" + act.action();
        }
        if ((act == GitTagActionEnum.CREATE || act == GitTagActionEnum.DELETE) && name != null && !isValidBranchName(name)) {
            return "Invalid tag name: '" + name + "'";
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            return switch (act) {
                case CREATE -> {
                    String rev = (revision != null && !revision.isBlank()) ? revision : "HEAD";
                    String msg = (message != null) ? message : "";
                    GitTag tag = client.createTag(name, rev, msg, false, false, NULL_PM);
                    yield "Created tag: " + tag.getTagName();
                }
                case DELETE -> {
                    client.deleteTag(name, NULL_PM);
                    yield "Deleted tag: " + name;
                }
                case LIST -> {
                    Map<String, GitTag> tags = client.getTags(NULL_PM, false);
                    if (tags.isEmpty()) {
                        yield "No tags";
                    }
                    StringBuilder sb = new StringBuilder();
                    tags.forEach((k, v) -> sb.append(k).append('\n'));
                    yield sb.toString().strip();
                }
            };
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitTag error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitRemote(String projectPath, GitRemoteActionEnum action, String name, String url) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        GitRemoteActionEnum act = action != null ? action : GitRemoteActionEnum.DEFAULT;
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            return switch (act) {
                case ADD -> {
                    if (name == null || name.isBlank()) {
                        yield "name is required";
                    }
                    if (url == null || url.isBlank()) {
                        yield "url is required";
                    }
                    String fetchSpec = "+refs/heads/*:refs/remotes/" + name + "/*";
                    GitRemoteConfig cfg = new GitRemoteConfig(name, List.of(url), List.of(), List.of(fetchSpec), List.of());
                    client.setRemote(cfg, NULL_PM);
                    yield "Added remote: " + name + " -> " + url;
                }
                case REMOVE -> {
                    if (name == null || name.isBlank()) {
                        yield "name is required";
                    }
                    client.removeRemote(name, NULL_PM);
                    yield "Removed remote: " + name;
                }
                case LIST -> {
                    Map<String, GitRemoteConfig> remotes = client.getRemotes(NULL_PM);
                    if (remotes.isEmpty()) {
                        yield "No remotes configured";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, GitRemoteConfig> e : remotes.entrySet()) {
                        List<String> uris = e.getValue().getUris();
                        sb.append(e.getKey()).append('\t')
                                .append(uris.isEmpty() ? "(no url)" : uris.get(0)).append('\n');
                    }
                    yield sb.toString().strip();
                }
            };
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitRemote error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitRevert(String projectPath, String revision) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return noRepoError(projectPath);
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository: " + root;
        }
        String rev = (revision != null && !revision.isBlank()) ? revision : "HEAD";
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            GitRevertResult result = client.revert(rev, "Revert \"" + rev + "\"", true, NULL_PM);
            String status = result.getStatus().toString();
            Collection<File> conflicts = result.getConflicts();
            if (conflicts != null && !conflicts.isEmpty()) {
                StringBuilder sb = new StringBuilder("Revert ").append(status).append(" — conflicts:\n");
                for (File f : conflicts) {
                    sb.append("  ").append(f.getName()).append('\n');
                }
                return sb.toString().strip();
            }
            GitRevisionInfo head = result.getNewHead();
            FileUtil.refreshFor(root);
            if (head != null) {
                String hash = head.getRevision();
                return "Reverted: " + hash.substring(0, Math.min(7, hash.length())) + " " + head.getShortMessage();
            }
            return "Revert " + status;
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitRevert error", e);
            return "Git error: " + e.getMessage();
        }
    }

    private static boolean isProtectedBranch(String name) {
        return name != null && PROTECTED_BRANCHES.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean isValidBranchName(String name) {
        if (name == null || name.isEmpty() || name.length() > 250) {
            return false;
        }
        return name.matches("^[a-zA-Z0-9._/+-]+$")
                && !name.contains("..")
                && !name.endsWith(".lock")
                && !name.startsWith("-");
    }

    private static boolean isWithinRepository(File gitRoot, File file) {
        try {
            String canonical = file.getCanonicalPath();
            String rootCanonical = gitRoot.getCanonicalPath();
            return canonical.startsWith(rootCanonical + File.separator)
                    || canonical.equals(rootCanonical);
        }
        catch (IOException e) {
            return false;
        }
    }

    private GitProvider() {
    }
}
