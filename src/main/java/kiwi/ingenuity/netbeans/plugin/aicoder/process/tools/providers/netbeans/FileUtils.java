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
     * Resolves an absolute path string to a FileObject, resolving symlinks so
     * the result is always recognised within the open project. Falls back to
     * direct VFS lookup if no source or project root matches.
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
     * Same as resolveByPath but accepts a File directly. Resolution order: 1.
     * GlobalPathRegistry source roots (Java source files) 2. OpenProjects
     * project directories (all project files incl. pom.xml) 3. Direct VFS
     * lookup (files outside any project)
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
     * Finds the source FileObject for a class name by walking registered source
     * roots. Accepts a FQN ("com.example.Outer.Inner") or simple name ("Foo").
     * Progressive shortening handles inner classes: Outer.Inner → Outer.java.
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

    /**
     * Returns the first .java file found across all registered source roots.
     * Used to bootstrap a ClasspathInfo for classpath-level queries.
     */
    public static FileObject findProjectSourceFile() {
        for (FileObject root : GlobalPathRegistry.getDefault().getSourceRoots()) {
            FileObject found = findJavaFile(root);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public static FileObject findJavaFile(FileObject dir) {
        for (FileObject child : dir.getChildren()) {
            if ("java".equals(child.getExt())) {
                return child;
            }
        }
        for (FileObject child : dir.getChildren()) {
            if (child.isFolder()) {
                FileObject found = findJavaFile(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private FileUtils() {
    }
}
