package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.java.platform.JavaPlatformManager;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.spi.project.AuxiliaryProperties;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Finds the Java platform the IDE builds a project with, so a build started by the plugin runs on the same
 * JDK as the IDE's own Clean and Build. Gathers the project's inputs from the NetBeans API and hands them to
 * {@link JdkPlatformChooser}; any failure leaves the build environment unchanged rather than failing the
 * build.
 * <p>
 * The project's platform name comes from {@code netbeans.hint.jdkPlatform}: for Maven through the project's
 * auxiliary properties (nb-configuration.xml or the pom), for Gradle from the build root's
 * {@code gradle.properties}. An Ant project names it in {@code platform.active} in {@code nbproject}.
 */
final class ProjectJavaPlatform {

    private static final Logger LOG = Logger.getLogger(ProjectJavaPlatform.class.getName());

    static final String JAVA_HOME = "JAVA_HOME";
    static final String HINT_KEY = "netbeans.hint.jdkPlatform";
    static final String ANT_PLATFORM_KEY = "platform.active";
    private static final String ANT_NAME_PROPERTY = "platform.ant.name";

    private ProjectJavaPlatform() {
    }

    /**
     * The environment a build of the project at {@code root} must run with: {@code JAVA_HOME} set to the
     * install folder of the platform the IDE would use, or empty when none can be worked out.
     */
    static Map<String, String> environmentFor(File root) {
        try {
            JdkPlatformChooser.Choice choice = resolve(root);
            return choice == null ? Map.of() : Map.of(JAVA_HOME, choice.platform().installFolder());
        }
        catch (Throwable ex) {
            LOG.log(Level.FINE, "Java platform not resolved for " + root + "; the build keeps the inherited environment", ex);
            return Map.of();
        }
    }

    private static JdkPlatformChooser.Choice resolve(File root) {
        FileObject rootObject = root == null ? null : FileUtil.toFileObject(FileUtil.normalizeFile(root));
        Project project = rootObject == null ? null : FileOwnerQuery.getOwner(rootObject);
        String named = namedPlatform(project, root);
        Set<String> bootRoots = named == null || named.isBlank() ? projectBootRoots(project) : Set.of();
        JavaPlatformManager manager = JavaPlatformManager.getDefault();
        List<JdkPlatformChooser.Platform> installed = new ArrayList<>();
        for (JavaPlatform platform : manager.getInstalledPlatforms()) {
            installed.add(describe(platform));
        }
        JdkPlatformChooser.Platform defaultPlatform = describe(manager.getDefaultPlatform());
        JdkPlatformChooser.Choice choice = JdkPlatformChooser.choose(installed, defaultPlatform, named, bootRoots);
        if (LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE, "Java platform for {0}: named={1}, projectBootRoots={2}, installed={3}, default={4}, chosen={5}",
                    new Object[]{root, named, bootRoots, installed, defaultPlatform,
                                 choice == null ? "none" : choice.platform().antName() + " by " + choice.source()});
        }
        if (choice != null && choice.namedButMissing() != null) {
            LOG.log(Level.INFO, "Project {0} names Java platform \"{1}\", which is not installed; building with the default"
                                + " platform {2}", new Object[]{root, choice.namedButMissing(), choice.platform().installFolder()});
        }
        return choice;
    }

    /**
     * The platform the project names: the NetBeans hint first, then an Ant project's {@code platform.active}.
     */
    private static String namedPlatform(Project project, File root) {
        if (project != null) {
            AuxiliaryProperties properties = project.getLookup().lookup(AuxiliaryProperties.class);
            if (properties != null) {
                String shared = properties.get(HINT_KEY, true);
                if (shared != null && !shared.isBlank()) {
                    return shared.trim();
                }
                String local = properties.get(HINT_KEY, false);
                if (local != null && !local.isBlank()) {
                    return local.trim();
                }
            }
        }
        String gradle = gradleRootHint(root);
        return gradle != null ? gradle : antActivePlatform(root);
    }

    /**
     * A Gradle build's platform name: NetBeans keeps {@code netbeans.hint.jdkPlatform} in the
     * {@code gradle.properties} of the build's root, the directory with {@code settings.gradle(.kts)}, not in
     * a subproject. So the search walks up from {@code root} to the nearest directory holding a settings file
     * and reads that one properties file. Null when there is no settings file above, or the root's properties
     * file does not set the key.
     */
    static String gradleRootHint(File root) {
        File buildRoot = GradleBuildRoot.rootOf(root);
        if (buildRoot == null) {
            return null;
        }
        String value = readProperty(new File(buildRoot, "gradle.properties"), HINT_KEY);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * An Ant project's {@code platform.active}: the private properties file wins over the shared one, as it
     * does for the IDE. Null when the project has neither file or neither sets it.
     */
    static String antActivePlatform(File root) {
        if (root == null) {
            return null;
        }
        for (String relative : List.of("nbproject/private/private.properties", "nbproject/project.properties")) {
            String value = readProperty(new File(root, relative), ANT_PLATFORM_KEY);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String readProperty(File file, String key) {
        if (!file.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file.toPath())) {
            properties.load(in);
        }
        catch (IOException | IllegalArgumentException ex) {
            LOG.log(Level.FINE, "Could not read " + file, ex);
            return null;
        }
        return properties.getProperty(key);
    }

    private static Set<String> projectBootRoots(Project project) {
        Set<String> roots = new LinkedHashSet<>();
        if (project == null) {
            return roots;
        }
        for (SourceGroup group : ProjectUtils.getSources(project).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)) {
            ClassPath boot = ClassPath.getClassPath(group.getRootFolder(), ClassPath.BOOT);
            roots.addAll(entryUrls(boot));
        }
        return roots;
    }

    private static JdkPlatformChooser.Platform describe(JavaPlatform platform) {
        if (platform == null) {
            return null;
        }
        String antName = platform.getProperties().get(ANT_NAME_PROPERTY);
        String installFolder = null;
        for (FileObject folder : platform.getInstallFolders()) {
            File file = FileUtil.toFile(folder);
            installFolder = file != null ? file.getPath() : folder.getPath();
            break;
        }
        return new JdkPlatformChooser.Platform(antName, platform.getDisplayName(), installFolder,
                entryUrls(platform.getBootstrapLibraries()));
    }

    private static Set<String> entryUrls(ClassPath path) {
        Set<String> urls = new LinkedHashSet<>();
        if (path != null) {
            for (ClassPath.Entry entry : path.entries()) {
                urls.add(entry.getURL().toExternalForm());
            }
        }
        return urls;
    }
}
