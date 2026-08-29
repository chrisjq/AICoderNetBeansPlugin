package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.java.classpath.GlobalPathRegistry;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

public class FileUtils {

    private static final Logger LOG = Logger.getLogger(FileUtils.class.getName());

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
     * keeps its stale cached length however often it is called. A 969-line file was read as 114 lines on 2026-08-29 and
     * a 282-line file as 120 lines on 2026-08-27, both while a refresh ran before every read. A method named for
     * refreshing before a read is a trap for exactly the reader who goes looking for one, so it was deleted rather than
     * left with a warning. Content reads must go to disk with {@code java.nio} and check the byte count against the
     * file's real size — see {@code RefactoringProvider.applyEdit}.
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
