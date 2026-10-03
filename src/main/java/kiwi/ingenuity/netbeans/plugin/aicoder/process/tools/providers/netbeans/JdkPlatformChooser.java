package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which Java platform a project builds with. No NetBeans types, so the rules are testable without an
 * IDE; {@link ProjectJavaPlatform} gathers the inputs from the NetBeans API.
 * <p>
 * Order of preference:
 * <ol>
 * <li>The platform the project names, matched to an installed platform by its ant name. An explicit name
 * cannot be ambiguous.</li>
 * <li>With no name at all, the one installed platform whose bootstrap classpath equals the project's. Two or
 * more matches give no result, because platforms can present identical bootstrap roots.</li>
 * <li>Otherwise the IDE's default platform, which is also where a named platform that is not installed, and
 * {@value #DEFAULT_PLATFORM}, end up.</li>
 * </ol>
 * A platform without an install folder is never chosen, since there is nothing to point {@code JAVA_HOME} at;
 * when even the default platform has none, there is no choice and the environment is left as it was.
 */
public final class JdkPlatformChooser {

    /**
     * The value an Ant project stores in {@code platform.active} when it uses the IDE's own platform.
     */
    public static final String DEFAULT_PLATFORM = "default_platform";

    /**
     * One installed platform. {@code antName} is its {@code platform.ant.name} property,
     * {@code installFolder} its first install folder, and {@code bootRoots} the URLs of its bootstrap
     * classpath entries.
     */
    public record Platform(String antName, String displayName, String installFolder, Set<String> bootRoots) {

        public Platform {
            bootRoots = bootRoots == null ? Set.of() : Set.copyOf(bootRoots);
        }
    }

    /**
     * What was chosen and what it was chosen by, for the log. {@code namedButMissing} is the project's
     * platform name when it named one that is not installed and the default platform was used instead,
     * otherwise null.
     */
    public record Choice(Platform platform, String source, String namedButMissing) {

    }

    private JdkPlatformChooser() {
    }

    /**
     * @param installed        every installed platform
     * @param defaultPlatform  the IDE's default platform, or null when there is none
     * @param namedPlatform    the platform name the project declares, or null/blank when it declares none
     * @param projectBootRoots the URLs of the project's bootstrap classpath entries, empty when unknown
     *
     * @return the platform to build with, or null to leave the environment unchanged
     */
    public static Choice choose(List<Platform> installed, Platform defaultPlatform, String namedPlatform,
                                Set<String> projectBootRoots) {
        String name = namedPlatform == null ? "" : namedPlatform.trim();
        if (!name.isEmpty() && !DEFAULT_PLATFORM.equals(name)) {
            if (installed != null) {
                for (Platform platform : installed) {
                    if (name.equals(platform.antName()) && hasInstallFolder(platform)) {
                        return new Choice(platform, "named platform " + name, null);
                    }
                }
            }
            return defaultChoice(defaultPlatform, name);
        }
        if (name.isEmpty()) {
            Platform byBoot = uniqueBootMatch(installed, projectBootRoots);
            if (byBoot != null) {
                return new Choice(byBoot, "boot classpath", null);
            }
        }
        return defaultChoice(defaultPlatform, null);
    }

    private static Choice defaultChoice(Platform defaultPlatform, String namedButMissing) {
        if (defaultPlatform == null || !hasInstallFolder(defaultPlatform)) {
            return null;
        }
        return new Choice(defaultPlatform, "default platform", namedButMissing);
    }

    private static Platform uniqueBootMatch(List<Platform> installed, Set<String> projectBootRoots) {
        if (installed == null || projectBootRoots == null || projectBootRoots.isEmpty()) {
            return null;
        }
        List<Platform> matches = new ArrayList<>();
        for (Platform platform : installed) {
            if (hasInstallFolder(platform) && !platform.bootRoots().isEmpty()
                && platform.bootRoots().equals(projectBootRoots)) {
                matches.add(platform);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean hasInstallFolder(Platform platform) {
        return platform.installFolder() != null && !platform.installFolder().isBlank();
    }
}
