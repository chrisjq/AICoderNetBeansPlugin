package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Shortens absolute paths for display.
 *
 * <p>
 * Confirm prompts and notifications show a path back to the user, and an absolute path is both long enough to dominate
 * the panel and mostly redundant — the leading directories are the same for every file in the project. This renders a
 * path inside an open project relative to that project but keeps the project directory name, so a prompt reads
 * {@code MyProject/target/scratch.txt} rather than {@code /home/user/code/MyProject/target/scratch.txt} — short, but
 * still saying which project it is when several are open.
 */
public final class ProjectPathUtil {

    private static final int MAX_LEN = 128;

    private ProjectPathUtil() {
    }

    /**
     * Project directory name plus the path within it, or the unchanged absolute path when the file belongs to no open
     * project. Long results are truncated from the left, keeping the end — the file name matters more than the
     * directories above it.
     *
     * @param fp absolute path; returned unchanged when null or blank
     */
    public static String shortPath(String fp) {
        return shortPath(fp, openProjectDirs());
    }

    /**
     * Whether {@code path} identifies one of the projects currently open in NetBeans. Equivalent filesystem locations
     * reached through different aliases or symlinks are treated as the same project.
     */
    public static boolean isOpenProjectPath(String path) {
        return isOpenProjectPath(path, openProjectDirs());
    }

    /**
     * Pure form of {@link #isOpenProjectPath(String)} with the IDE lookup lifted out for testing.
     */
    static boolean isOpenProjectPath(String path, List<File> projectDirs) {
        return path != null && projectDirs.stream()
                .filter(java.util.Objects::nonNull)
                .map(File::getPath)
                .anyMatch(projectPath -> samePath(path, projectPath));
    }

    /**
     * Whether two path spellings resolve to the same filesystem location.
     */
    public static boolean samePath(String left, String right) {
        return left != null && !left.isBlank()
                && right != null && !right.isBlank()
                && canonical(left).equals(canonical(right));
    }

    /**
     * Removes duplicate spellings of the same filesystem location, retaining the shortest spelling for each location.
     * This keeps a project that NetBeans exposes through a symlink or shared-folder alias from appearing twice in a
     * selection list while ensuring new sessions persist the most concise usable path.
     */
    public static List<String> distinctShortestPaths(List<String> paths) {
        Map<String, String> preferredPaths = new LinkedHashMap<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            preferredPaths.merge(canonical(path), path, ProjectPathUtil::shorterPath);
        }
        return new ArrayList<>(preferredPaths.values());
    }

    private static String shorterPath(String left, String right) {
        if (right.length() != left.length()) {
            return right.length() < left.length() ? right : left;
        }
        return right.compareTo(left) < 0 ? right : left;
    }

    /**
     * Pure form of {@link #shortPath(String)} with the open-project lookup lifted out, so the shortening rules can be
     * tested without a running IDE.
     *
     * @param fp absolute path; returned unchanged when null or blank
     * @param projectDirs directories of the currently open projects
     */
    static String shortPath(String fp, List<File> projectDirs) {
        if (fp == null || fp.isBlank()) {
            return fp;
        }
        String canonicalFp = canonical(fp);
        for (File projectDir : projectDirs) {
            if (projectDir == null) {
                continue;
            }
            String base = projectDir.getAbsolutePath();
            // Compare as given first, then with both sides canonicalised. A project
            // reached through a symlink (/share -> /Users/…/.SyncShare) otherwise
            // fails a raw prefix test whenever the path and the project directory
            // were spelled with different aliases, and the user is shown the full
            // path the shortening exists to remove.
            String rel = relativise(fp, base);
            if (rel == null) {
                rel = relativise(canonicalFp, canonical(base));
            }
            if (rel != null) {
                // Keep the project directory name: with several projects open,
                // "target/scratch.txt" alone does not say which one it belongs to.
                return truncate(rel.isEmpty()
                                ? projectDir.getName()
                                : projectDir.getName() + File.separator + rel);
            }
        }
        // Outside every open project: keep the whole path. Shortening is only safe
        // when the part removed is redundant, which it is not here — "delete
        // hosts?" would not say which file, or that it sits outside the project
        // at all. Truncation still applies, from the left, so the file name and
        // its nearest directories survive.
        return truncate(fp);
    }

    /**
     * Path of {@code fp} below {@code base}, {@code ""} when they are the same directory, or null when {@code fp} is
     * not inside {@code base}.
     */
    private static String relativise(String fp, String base) {
        if (fp == null || base == null || base.isEmpty()) {
            return null;
        }
        if (fp.equals(base)) {
            return "";
        }
        // Require a separator boundary: a plain startsWith would treat
        // /code/MyProject-old as living inside /code/MyProject and then report a
        // nonsense relative path built from the tail of a different directory.
        String prefix = base.endsWith(File.separator) ? base : base + File.separator;
        return fp.startsWith(prefix) ? fp.substring(prefix.length()) : null;
    }

    /**
     * Symlinks resolved. {@link File#getCanonicalPath()} also works for a file that does not exist yet (it resolves the
     * part of the path that does), which matters because these prompts are shown before a create or a move happens.
     * <p>
     * Public so a caller can canonicalise once — for example a model precomputing an open-project membership set —
     * instead of resolving the same paths on every render. The result is for comparison only, never for NetBeans
     * {@code FileObject} or cache APIs.
     */
    public static String canonical(String p) {
        try {
            return new File(p).getCanonicalPath();
        }
        catch (IOException e) {
            return new File(p).getAbsolutePath();
        }
    }

    /**
     * OS directories of the currently open projects, via the same {@code FileUtil.toFile} conversion the rest of the
     * codebase uses. Empty when no project is open. Public so the open set can be computed once and reused.
     */
    public static List<File> openProjectDirs() {
        List<File> dirs = new ArrayList<>();
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            FileObject fo = p.getProjectDirectory();
            // FileUtil.toFile is how the rest of this codebase turns a project
            // directory into an OS path; FileObject.getPath() is a filesystem-
            // relative path and is not guaranteed to be one.
            File dir = FileUtil.toFile(fo);
            if (dir == null) {
                dir = new File(fo.getPath());
            }
            dirs.add(dir);
        }
        return dirs;
    }

    /**
     * Canonical spellings of the supplied project directories. Computed once — by a model, for example, when the
     * open-project set changes — so membership tests can run without resolving paths per session per render. The
     * canonical value is for comparison only, never for NetBeans {@code FileObject} or cache APIs.
     */
    public static Set<String> canonicalDirs(List<File> projectDirs) {
        Set<String> canonical = new HashSet<>();
        if (projectDirs != null) {
            for (File dir : projectDirs) {
                if (dir != null) {
                    canonical.add(canonical(dir.getPath()));
                }
            }
        }
        return canonical;
    }

    /**
     * Whether {@code path} canonicalises to one of the precomputed open-project spellings. The set must come from
     * {@link #canonicalDirs(List)}; {@code path} is resolved here so both sides are canonical.
     */
    public static boolean matchesCanonicalDirs(Set<String> canonicalDirs, String path) {
        return path != null && !path.isBlank() && canonicalDirs.contains(canonical(path));
    }

    private static String truncate(String s) {
        return s.length() > MAX_LEN ? "..." + s.substring(s.length() - (MAX_LEN - 3)) : s;
    }
}
