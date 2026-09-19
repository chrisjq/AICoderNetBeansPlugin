package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.netbeans.api.java.classpath.GlobalPathRegistry;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

public class FileUtils {

    private static final Logger LOG = Logger.getLogger(FileUtils.class.getName());
    private static volatile Map<String, String> IDE_ROOTS = Map.of();

    static {
        try {
            OpenProjects.getDefault().addPropertyChangeListener(evt -> {
                if (OpenProjects.PROPERTY_OPEN_PROJECTS.equals(evt.getPropertyName())) {
                    refreshIdeRoots();
                }
            });
        }
        catch (Throwable t) {
            LOG.log(Level.FINE, "Cannot register open-project listener", t);
        }
        refreshIdeRoots();
    }

    private static void refreshIdeRoots() {
        try {
            Map<String, String> roots = new HashMap<>();
            for (Project project : OpenProjects.getDefault().getOpenProjects()) {
                FileObject fo = project.getProjectDirectory();
                File ideRoot = fo != null ? FileUtil.toFile(fo) : null;
                if (ideRoot != null) {
                    roots.put(toRealPath(ideRoot).getPath(), ideRoot.getPath());
                }
            }
            IDE_ROOTS = Collections.unmodifiableMap(roots);
        }
        catch (Throwable t) {
            LOG.log(Level.FINE, "Cannot refresh IDE path roots", t);
            IDE_ROOTS = Map.of();
        }
    }

    /**
     * Resolves an absolute path string to a FileObject, resolving symlinks so the result is always recognised within
     * the open project. Falls back to direct VFS lookup if no source or project root matches.
     */
    public static FileObject resolveByPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        File f = new File(filePath);
        if (!f.exists()) {
            return null;
        }
        return resolveByFile(f);
    }

    /**
     * Same as resolveByPath but accepts a File directly. Resolution order: 1. GlobalPathRegistry source roots (Java
     * source files) 2. OpenProjects project directories (all project files incl. pom.xml) 3. Direct VFS lookup (files
     * outside any project)
     */
    public static FileObject resolveByFile(File f) {
        try {
            File canonical = f.getCanonicalFile();

            for (FileObject root : GlobalPathRegistry.getDefault().getSourceRoots()) {
                FileObject fo = matchUnder(root, canonical);
                if (fo != null) {
                    return fo;
                }
            }

            for (Project p : OpenProjects.getDefault().getOpenProjects()) {
                FileObject root = p.getProjectDirectory();
                FileObject fo = matchUnder(root, canonical);
                if (fo != null) {
                    return fo;
                }
            }

            f = canonical;
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot resolve canonical path for {0}: {1}", new Object[]{f, e.getMessage()});
        }
        return FileUtil.toFileObject(FileUtil.normalizeFile(f));
    }

    /**
     * Resolves a file through the filesystem's real path, so symlink aliases such as {@code /share/code} and
     * {@code /Users/chris/.SyncShare} compare identically for project and scope checks.
     *
     * <p>
     * The real-path lookup is strongest because it follows existing symlinks. If it cannot complete, the canonical path
     * still removes {@code .}/{@code ..} and makes the result absolute; if that also fails, NetBeans normalization
     * provides a stable non-null fallback. This method intentionally differs from
     * {@code SessionFileScopeRegistry.resolveRealPath(Path)}, which resolves not-yet-existing paths through their
     * parent and therefore has distinct security-scope semantics.</p>
     *
     * <p>
     * Use the returned path only for comparisons and OS-level work, such as scope checks or process working
     * directories. Never pass it to NetBeans {@code FileObject} or cache APIs: those caches are keyed by the path
     * spelling they were given, so resolving {@code /share/code/...} to {@code /Users/chris/.SyncShare/...} can address
     * a different object from the one the IDE already knows.</p>
     *
     * @param f file or directory to resolve
     *
     * @return a non-null resolved file
     */
    public static File toRealPath(File f) {
        File input = f != null ? f : new File("");
        try {
            return input.toPath().toRealPath().toFile();
        }
        catch (IOException realPathFailure) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.FINE, "Could not resolve real path for " + input.getAbsolutePath(), realPathFailure);
            }
            try {
                return input.getCanonicalFile();
            }
            catch (IOException canonicalFailure) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.FINE, "Could not resolve canonical path for " + input.getAbsolutePath(), canonicalFailure);
                }
                return FileUtil.normalizeFile(input);
            }
        }
    }

    /**
     * Converts an inbound path to the spelling NetBeans uses for the matching open project, for outbound tool
     * responses. The input is resolved through {@link #toRealPath(File)} only for comparison; the returned path is
     * rebuilt beneath the project's own {@link FileObject} directory spelling. If no open project matches, the
     * normalized input spelling is returned unchanged, and the result is never null.
     *
     * <p>
     * This is the outbound counterpart to {@link #toRealPath(File)}. Use this result when handing a path back to an AI,
     * not as input to NetBeans {@code FileObject} or cache APIs. Prefer the {@code FileObject} itself for IDE
     * operations because cache keys are spelling-sensitive. Known project roots are cached and invalidated when the
     * open-project set changes; already-IDE-spelled paths take a syscall-free prefix fast path.</p>
     *
     * @param f inbound file or directory
     *
     * @return the IDE-known spelling when the path belongs to an open project, otherwise a normalized input path
     */
    public static File toIdePath(File f) {
        File input = f != null ? f : new File("");
        String raw = input.getPath();
        for (String ideRoot : IDE_ROOTS.values()) {
            if (raw.equals(ideRoot) || raw.startsWith(ideRoot + File.separator)) {
                return input;
            }
        }
        File normalized = FileUtil.normalizeFile(input);
        String real = toRealPath(input).getPath();
        for (Map.Entry<String, String> entry : IDE_ROOTS.entrySet()) {
            String realRoot = entry.getKey();
            if (real.equals(realRoot) || real.startsWith(realRoot + File.separator)) {
                String relative = real.equals(realRoot) ? "" : real.substring(realRoot.length() + 1);
                return relative.isEmpty() ? new File(entry.getValue()) : new File(entry.getValue(), relative);
            }
        }
        return normalized;
    }

    /**
     * String convenience overload for outbound tool responses.
     *
     * @param path inbound path text
     *
     * @return the IDE-known spelling or normalized input spelling, never null
     */
    public static String toIdePath(String path) {
        return toIdePath(path == null ? null : new File(path)).getPath();
    }

    public static FileObject matchUnder(FileObject root, File canonical) throws IOException {
        File rootFile = FileUtil.toFile(root);
        if (rootFile == null) {
            return null;
        }
        File rootCanonical = rootFile.getCanonicalFile();
        String cp = canonical.getPath();
        String rp = rootCanonical.getPath();
        if (cp.startsWith(rp + File.separator) || cp.equals(rp)) {
            String rel = cp.substring(rp.length());
            if (rel.startsWith(File.separator)) {
                rel = rel.substring(1);
            }
            FileObject fo = root.getFileObject(rel.replace(File.separatorChar, '/'));
            if (fo != null && fo.isValid()) {
                return fo;
            }
        }
        return null;
    }

    /**
     * Re-stats a {@link FileObject} after its content has been written, so the IDE's cached size and the editor's view
     * match the bytes now on disk. Without it the next cached read sees the pre-write length.
     *
     * <p>
     * <b>There is deliberately no refreshBeforeRead sibling, and refreshing before a read is not a way to make one
     * safe.</b> NetBeans caches a FileObject's size and {@code asBytes()} returns only that many bytes, so a stale
     * cache yields a TRUNCATED read — which a read-modify-write then persists, destroying everything past the boundary.
     * {@code refresh()} does not fix that: it re-stats from last-modified time, so a file whose mtime has not changed
     * keeps its stale cached length however often it is called: a 969-line file was read as 114 lines and a 282-line
     * file as 120 lines, both while a refresh ran before every read. A method named for refreshing before a read is a
     * trap for exactly the reader who goes looking for one, so it was deleted rather than left with a warning. Content
     * reads must go to disk with {@code java.nio} and check the byte count against the file's real size — see
     * {@code RefactoringProvider.applyEdit}.
     *
     * <p>
     * This method rests on the same last-modified assumption and is sound only because it runs AFTER a write, and a
     * write does change mtime. Do not repurpose it as a general "make this FileObject current" call.
     *
     * @param fo the file object just written; null is ignored
     */
    public static void refreshAfterWrite(FileObject fo) {
        if (fo != null) {
            fo.refresh();
        }
    }

    /**
     * Path-taking form of {@link #refreshAfterWrite(FileObject)}, for callers holding only a path string.
     *
     * <p>
     * Resolves through {@link #resolveByPath} so the object refreshed is the CANONICAL one. A bare
     * {@code FileUtil.toFileObject(new File(path))} does not canonicalise, and callers here address the project through
     * the {@code /share} symlink — so it can hand back a different object (or none) than the one the IDE actually
     * holds, leaving the refresh with no effect.
     *
     * <p>
     * Swallows failures: callers are UI callbacks where a refresh is best-effort housekeeping and must never surface as
     * an error.
     *
     * @param filePath absolute path of the file just written; null, blank or missing is ignored
     */
    public static void refreshAfterWrite(String filePath) {
        try {
            refreshAfterWrite(resolveByPath(filePath));
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "Post-write refresh failed for " + filePath, e);
        }
    }

    /**
     * Finds the source FileObject for a class name by walking registered source roots. Accepts a FQN
     * ("com.example.Outer.Inner") or simple name ("Foo"). Progressive shortening handles inner classes: Outer.Inner →
     * Outer.java.
     */
    public static FileObject locateSourceFile(String className) {
        String[] parts = className.replace('$', '.').split("\\.");
        for (FileObject root : GlobalPathRegistry.getDefault().getSourceRoots()) {
            for (int len = parts.length; len >= 1; len--) {
                String path = String.join("/", Arrays.copyOf(parts, len)) + ".java";
                FileObject fo = root.getFileObject(path);
                if (fo != null) {
                    return fo;
                }
            }
        }
        return null;
    }

    private FileUtils() {
    }
}
