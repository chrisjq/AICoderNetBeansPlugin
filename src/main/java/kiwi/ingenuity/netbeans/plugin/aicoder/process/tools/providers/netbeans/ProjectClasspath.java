package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.ArrayList;
import java.util.List;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.platform.JavaPlatformManager;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.filesystems.FileObject;

/**
 * Classpath of a whole project, merged across every Java source root. Moved unchanged from
 * {@code JavadocProvider}.
 */
final class ProjectClasspath {

    private ProjectClasspath() {
    }

    /**
     * Builds a {@link ClasspathInfo} for the chosen project from its Java source ROOTS rather than from any
     * sample source file. Anchoring a {@code ClasspathInfo} on a file yields the classpath OF THAT FILE, so
     * the visible dependencies would vary with which root happened to provide the anchor (main vs test) or
     * fail entirely when the project had no reachable Java file. Constructing the three classpaths explicitly
     * removes that dependency on file placement.
     * <p>
     * All of the project's Java source groups are merged with {@link ClassPathSupport#createProxyClassPath}
     * for each of BOOT/COMPILE/SOURCE, so a single query sees both main- and test-scoped dependencies. A
     * class a caller might legitimately ask about (including a test-only dependency) therefore resolves
     * instead of failing confusingly; the proxy is a union, so nothing visible on any individual root is
     * dropped. A missing boot path on a group falls back to the default platform's bootstrap libraries.
     * <p>
     * Returns null when the project has no Java source group: {@code ClassPath.getClassPath} only yields a
     * real classpath for a recognised source ROOT such as {@code src/main/java}, never for a bare project
     * directory, so falling back to the project directory would silently build a JDK-only classpath and then
     * report a misleading "Class not found" for the project's own classes. The caller refuses explicitly
     * instead.
     */
    static ClasspathInfo forProject(Project project) {
        SourceGroup[] groups = ProjectUtils.getSources(project).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA);
        if (groups.length == 0) {
            return null;
        }
        List<ClassPath> boot = new ArrayList<>();
        List<ClassPath> compile = new ArrayList<>();
        List<ClassPath> source = new ArrayList<>();
        for (SourceGroup group : groups) {
            FileObject root = group.getRootFolder();
            addNonNull(boot, ClassPath.getClassPath(root, ClassPath.BOOT));
            addNonNull(compile, ClassPath.getClassPath(root, ClassPath.COMPILE));
            addNonNull(source, ClassPath.getClassPath(root, ClassPath.SOURCE));
        }
        return ClasspathInfo.create(
                boot.isEmpty() ? defaultBootPath() : ClassPathSupport.createProxyClassPath(boot.toArray(ClassPath[]::new)),
                compile.isEmpty() ? ClassPath.EMPTY : ClassPathSupport.createProxyClassPath(compile.toArray(ClassPath[]::new)),
                source.isEmpty() ? ClassPath.EMPTY : ClassPathSupport.createProxyClassPath(source.toArray(ClassPath[]::new)));
    }

    /**
     * Only the default platform's bootstrap libraries. Used when a project's index does not already contain
     * JDK types. {@code ClassIndex.SearchScope} has no boot value, so the boot path is a
     * {@link ClasspathInfo} of its own.
     */
    static ClasspathInfo bootOnly() {
        return ClasspathInfo.create(defaultBootPath(), ClassPath.EMPTY, ClassPath.EMPTY);
    }

    /**
     * The default platform's bootstrap libraries, or {@link ClassPath#EMPTY} when the platform is not
     * installed.
     */
    static ClassPath defaultBootPath() {
        try {
            ClassPath boot = JavaPlatformManager.getDefault().getDefaultPlatform().getBootstrapLibraries();
            return boot != null ? boot : ClassPath.EMPTY;
        }
        catch (Throwable ex) {
            return ClassPath.EMPTY;
        }
    }

    private static void addNonNull(List<ClassPath> into, ClassPath path) {
        if (path != null) {
            into.add(path);
        }
    }
}
