package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import org.openide.modules.InstalledFileLocator;
import org.openide.util.NbPreferences;

/**
 * Decides which Maven or Gradle executable the build tools should launch,
 * following the same rules the IDE itself follows.
 *
 * <p>
 * Both providers used to run a bare {@code "mvn"} / {@code "gradle"} off the
 * PATH whenever the project had no wrapper. That fails outright —
 * {@code Cannot run program "mvn"} — on the very common setup of a machine with
 * NetBeans (which ships its own Maven) but no separately installed Maven, even
 * though the IDE's own Build action works fine on the same project.
 * {@link BuildAndTestAntProvider} already avoided this by locating NetBeans'
 * bundled Ant; this is the same idea for the other two.
 *
 * <p>
 * WHY PREFERENCES AND NOT THE MAVEN MODULE'S API. {@code MavenSettings} would
 * answer this exactly, but {@code org.netbeans.modules.maven} declares
 * {@code OpenIDE-Module-Friends}, which narrows its public packages to a fixed
 * list of NetBeans-internal modules. Depending on it would compile and then
 * fail module resolution in the IDE. Reading a preferences node is not a module
 * dependency at all, so it stays within public API and keeps working across
 * NetBeans versions: every key below is looked up by name and simply falls
 * through when absent, so an older or newer IDE degrades to the bundled copy
 * rather than breaking.
 *
 * <p>
 * {@link #resolve} is pure so the ordering can be tested without a NetBeans
 * runtime; {@link #forProject} is the thin wiring that reads the IDE.
 */
final class BuildToolLocator {

    private static final Logger LOG = Logger.getLogger(BuildToolLocator.class.getName());

    /**
     * Shared by both modules, and spelled the same in each:
     * {@code org.netbeans.modules.maven.options.MavenSettings} and
     * {@code org.netbeans.modules.gradle.spi.GradleSettings} both use this key.
     */
    private static final String PREFER_WRAPPER = "preferWrapper";

    enum Tool {
        /**
         * {@code mavenRuntimes} is a {@link File#pathSeparator}-joined list of
         * Maven home directories — confirmed from MavenSettings, which reads it
         * with a plain {@code Preferences.get} and splits on the path
         * separator.
         */
        MAVEN("mvnw", "mvnw.cmd", "mvn", "mvn.cmd", "org/netbeans/modules/maven", "maven",
                new String[]{"MAVEN_HOME", "M2_HOME"}),
        /**
         * Gradle stores a single home under {@code gradleHome}, gated by
         * {@code useCustomGradle}. NetBeans ships no bundled Gradle, so that
         * step simply finds nothing here and the wrapper or PATH answers
         * instead.
         */
        GRADLE("gradlew", "gradlew.bat", "gradle", "gradle.bat", "org/netbeans/modules/gradle", "gradle",
                new String[]{"GRADLE_HOME"});

        final String wrapperUnix;
        final String wrapperWindows;
        final String exeUnix;
        final String exeWindows;
        final String prefsNode;
        final String bundledDir;
        final String[] envNames;

        Tool(String wrapperUnix, String wrapperWindows, String exeUnix, String exeWindows,
                String prefsNode, String bundledDir, String[] envNames) {
            this.wrapperUnix = wrapperUnix;
            this.wrapperWindows = wrapperWindows;
            this.exeUnix = exeUnix;
            this.exeWindows = exeWindows;
            this.prefsNode = prefsNode;
            this.bundledDir = bundledDir;
            this.envNames = envNames;
        }

        String wrapper(boolean windows) {
            return windows ? wrapperWindows : wrapperUnix;
        }

        String executable(boolean windows) {
            return windows ? exeWindows : exeUnix;
        }

        String bundledRelativePath(boolean windows) {
            return bundledDir + "/bin/" + executable(windows);
        }
    }

    /**
     * Everything the IDE would tell us, passed in so the rules can be pinned by
     * a unit test.
     *
     * @param runtimesPref the raw preference value — a path-separated list for
     * Maven, a single home for Gradle; null when unset
     * @param bundled the executable NetBeans ships, or null when it ships none
     * @param envHome a Maven/Gradle home from the environment, or null
     */
    record Inputs(File projectRoot, Tool tool, boolean windows, boolean preferWrapper,
            String runtimesPref, File bundled, String envHome) {

    }

    /**
     * What an absent {@code preferWrapper} means.
     *
     * <p>
     * TRUE, deliberately: before this class existed a project's wrapper always
     * won, so defaulting the other way would silently stop honouring
     * {@code mvnw}/{@code gradlew} for every existing project — a behaviour
     * change nobody asked for, on the projects most likely to care which build
     * tool version runs.
     */
    static boolean preferWrapperDefault() {
        return true;
    }

    /**
     * The executable to launch, in the IDE's own order of preference: the
     * project's wrapper, then a runtime the user configured in Tools &gt;
     * Options, then the copy NetBeans bundles, then the environment, and only
     * then a bare name left to the PATH.
     */
    static String resolve(Inputs in) {
        Tool tool = in.tool();
        boolean windows = in.windows();

        if (in.preferWrapper()) {
            File wrapper = new File(in.projectRoot(), tool.wrapper(windows));
            if (wrapper.isFile()) {
                return wrapper.getAbsolutePath();
            }
        }

        String configured = in.runtimesPref();
        if (configured != null && !configured.isBlank()) {
            for (String entry : configured.split(Pattern.quote(File.pathSeparator))) {
                File exe = executableIn(entry, tool, windows);
                if (exe != null) {
                    return exe.getAbsolutePath();
                }
            }
        }

        File bundled = in.bundled();
        if (bundled != null && bundled.isFile()) {
            return bundled.getAbsolutePath();
        }

        File fromEnv = executableIn(in.envHome(), tool, windows);
        if (fromEnv != null) {
            return fromEnv.getAbsolutePath();
        }

        // Unchanged last resort: the bare name, resolved by the PATH exactly as before.
        return tool.executable(windows);
    }

    /**
     * The executable inside a tool home, or null when the home is blank or
     * holds no such executable.
     *
     * <p>
     * A home given as {@code .../bin} is accepted: NetBeans' own
     * default-runtime discovery strips a trailing {@code bin} before using a
     * PATH entry, and a user who pasted the bin directory into Options means
     * the same installation.
     */
    private static File executableIn(String home, Tool tool, boolean windows) {
        if (home == null || home.isBlank()) {
            return null;
        }
        File dir = new File(home.trim());
        if ("bin".equals(dir.getName())) {
            dir = dir.getParentFile();
        }
        if (dir == null) {
            return null;
        }
        File exe = new File(new File(dir, "bin"), tool.executable(windows));
        return exe.isFile() ? exe : null;
    }

    /**
     * Reads what the running IDE has configured and applies {@link #resolve}.
     *
     * <p>
     * Every lookup is defensive. {@code NbPreferences} needs a NetBeans runtime
     * and its lazy initialisation can throw — {@code PluginSettings} documents
     * the same hazard and guards it the same way — and a build tool must never
     * fail because a settings node could not be read. Anything unavailable
     * simply leaves the corresponding step to find nothing.
     */
    static String forProject(File projectRoot, Tool tool) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        boolean preferWrapper = preferWrapperDefault();
        String configured = null;
        try {
            Preferences prefs = NbPreferences.root().node(tool.prefsNode);
            preferWrapper = prefs.getBoolean(PREFER_WRAPPER, preferWrapperDefault());
            configured = tool == Tool.MAVEN
                    ? prefs.get("mavenRuntimes", null)
                    : prefs.getBoolean("useCustomGradle", false) ? prefs.get("gradleHome", null) : null;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "NetBeans " + tool + " settings unavailable; falling back to discovery", t);
        }

        File bundled = null;
        try {
            bundled = InstalledFileLocator.getDefault().locate(tool.bundledRelativePath(windows), null, false);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Bundled " + tool + " lookup unavailable", t);
        }

        String envHome = null;
        for (String name : tool.envNames) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                envHome = value;
                break;
            }
        }

        return resolve(new Inputs(projectRoot, tool, windows, preferWrapper, configured, bundled, envHome));
    }

    private BuildToolLocator() {
    }
}
