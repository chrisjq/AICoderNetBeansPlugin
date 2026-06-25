package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
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

    public static String getGitStatus() {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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

    public static String getGitDiff(boolean staged) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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

    public static String gitAdd(List<String> files) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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

    public static String gitCommit(String message, List<String> files) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            if (files != null && !files.isEmpty()) {
                client.add(resolveFiles(root, files), NULL_PM);
            }
            GitUser user;
            try {
                user = client.getUser();
            }
            catch (GitException ex) {
                user = null;
            }
            GitRevisionInfo info = client.commit(new File[]{gitRoot}, message, user, user, NULL_PM);
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

    public static String gitLog(int limit) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            SearchCriteria criteria = new SearchCriteria();
            criteria.setLimit(limit > 0 ? limit : 20);
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

    public static String gitPush(String remote, String branch) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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
            Map<String, GitTransportUpdate> updates = result.getRemoteRepositoryUpdates();
            if (updates.isEmpty()) {
                return "Push complete (nothing to update)";
            }
            StringBuilder sb = new StringBuilder("Push complete:\n");
            for (Map.Entry<String, GitTransportUpdate> e : updates.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ")
                        .append(e.getValue().getResult()).append('\n');
            }
            return sb.toString().strip();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitPush error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitPull(String remote) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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
            GitMergeResult merge = result.getMergeResult();
            if (merge != null) {
                return "Pull complete: " + merge.getMergeStatus();
            }
            return "Pull complete";
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitPull error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitCheckout(String branchOrRevision, boolean createNew) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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

    public static String gitBranch(boolean all, String newBranch) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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

    public static String refreshVcsStatus(String filePath) {
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

    public static String gitDeleteBranch(String branch, boolean force) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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

    public static String gitStash(String action, int index, String message, boolean includeUntracked) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
        }
        String act = action != null ? action.toLowerCase() : "push";
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            switch (act) {
                case "list" -> {
                    GitRevisionInfo[] stashes = client.stashList(NULL_PM);
                    if (stashes.length == 0) {
                        return "No stashes";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < stashes.length; i++) {
                        sb.append("stash@{").append(i).append("}: ").append(stashes[i].getShortMessage()).append('\n');
                    }
                    return sb.toString().strip();
                }
                case "pop" -> {
                    client.stashApply(index, true, NULL_PM);
                    FileUtil.refreshFor(root);
                    return "Popped stash@{" + index + "}";
                }
                case "apply" -> {
                    client.stashApply(index, false, NULL_PM);
                    FileUtil.refreshFor(root);
                    return "Applied stash@{" + index + "}";
                }
                case "drop" -> {
                    client.stashDrop(index, NULL_PM);
                    return "Dropped stash@{" + index + "}";
                }
                default -> {
                    String msg = (message != null && !message.isBlank()) ? message : "WIP";
                    GitRevisionInfo info = client.stashSave(msg, includeUntracked, NULL_PM);
                    if (info == null) {
                        return "Nothing to stash";
                    }
                    return "Stashed: " + info.getShortMessage();
                }
            }
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitStash error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitFetch(String remote) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
        }
        String remoteName = (remote != null && !remote.isBlank()) ? remote : "origin";
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            Map<String, GitTransportUpdate> updates = client.fetch(remoteName, NULL_PM);
            if (updates.isEmpty()) {
                return "Fetch complete (nothing to update)";
            }
            StringBuilder sb = new StringBuilder("Fetch complete:\n");
            for (Map.Entry<String, GitTransportUpdate> e : updates.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue().getResult()).append('\n');
            }
            return sb.toString().strip();
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitFetch error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitReset(List<String> files, String revision, String resetType) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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
                    type = GitClient.ResetType.valueOf((resetType != null ? resetType : "MIXED").toUpperCase());
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

    public static String gitMerge(String branch) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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

    public static String gitShow(String revision) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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
            sb.append("Date:   ").append(info.getCommitTime()).append('\n');
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

    public static String gitBlame(String filePath) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
        }
        if (filePath == null || filePath.isBlank()) {
            return "filePath is required";
        }
        File file = new File(filePath);
        if (!file.isAbsolute()) {
            file = new File(root, filePath);
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

    public static String gitRebase(String upstream, String operation) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
        }
        GitClient.RebaseOperationType op;
        try {
            op = GitClient.RebaseOperationType.valueOf((operation != null ? operation : "BEGIN").toUpperCase());
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

    public static String gitCherryPick(String operation, List<String> revisions) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
        }
        GitClient.CherryPickOperation op;
        try {
            op = GitClient.CherryPickOperation.valueOf((operation != null ? operation : "BEGIN").toUpperCase());
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

    public static String gitTag(String action, String name, String revision, String message) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
        }
        String act = action != null ? action.toLowerCase() : "list";
        if (("create".equals(act) || "delete".equals(act)) && (name == null || name.isBlank())) {
            return "Error: name is required for action=" + act;
        }
        if (("create".equals(act) || "delete".equals(act)) && name != null && !isValidBranchName(name)) {
            return "Invalid tag name: '" + name + "'";
        }
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            switch (act) {
                case "create" -> {
                    String rev = (revision != null && !revision.isBlank()) ? revision : "HEAD";
                    String msg = (message != null) ? message : "";
                    GitTag tag = client.createTag(name, rev, msg, false, false, NULL_PM);
                    return "Created tag: " + tag.getTagName();
                }
                case "delete" -> {
                    client.deleteTag(name, NULL_PM);
                    return "Deleted tag: " + name;
                }
                default -> {
                    Map<String, GitTag> tags = client.getTags(NULL_PM, false);
                    if (tags.isEmpty()) {
                        return "No tags";
                    }
                    StringBuilder sb = new StringBuilder();
                    tags.forEach((k, v) -> sb.append(k).append('\n'));
                    return sb.toString().strip();
                }
            }
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitTag error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitRemote(String action, String name, String url) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
        }
        String act = action != null ? action.toLowerCase() : "list";
        try (GitClient client = GitRepository.getInstance(gitRoot).createClient()) {
            switch (act) {
                case "add" -> {
                    if (name == null || name.isBlank()) {
                        return "name is required";
                    }
                    if (url == null || url.isBlank()) {
                        return "url is required";
                    }
                    String fetchSpec = "+refs/heads/*:refs/remotes/" + name + "/*";
                    GitRemoteConfig cfg = new GitRemoteConfig(name, List.of(url), List.of(), List.of(fetchSpec), List.of());
                    client.setRemote(cfg, NULL_PM);
                    return "Added remote: " + name + " -> " + url;
                }
                case "remove" -> {
                    if (name == null || name.isBlank()) {
                        return "name is required";
                    }
                    client.removeRemote(name, NULL_PM);
                    return "Removed remote: " + name;
                }
                default -> {
                    Map<String, GitRemoteConfig> remotes = client.getRemotes(NULL_PM);
                    if (remotes.isEmpty()) {
                        return "No remotes configured";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, GitRemoteConfig> e : remotes.entrySet()) {
                        List<String> uris = e.getValue().getUris();
                        sb.append(e.getKey()).append('\t')
                                .append(uris.isEmpty() ? "(no url)" : uris.get(0)).append('\n');
                    }
                    return sb.toString().strip();
                }
            }
        }
        catch (GitException e) {
            LOG.log(Level.WARNING, "gitRemote error", e);
            return "Git error: " + e.getMessage();
        }
    }

    public static String gitRevert(String revision) {
        File root = getOpenProjectRoot();
        if (root == null) {
            return "No open project found";
        }
        File gitRoot = findGitRoot(root);
        if (gitRoot == null) {
            return "Not a git repository";
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

    private static final List<String> PROTECTED_BRANCHES
            = List.of("main", "master", "production", "release");

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
