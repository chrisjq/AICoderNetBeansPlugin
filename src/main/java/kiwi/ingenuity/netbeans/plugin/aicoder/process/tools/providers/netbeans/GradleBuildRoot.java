package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;

/**
 * Where a Gradle build lives. The build root is the directory with {@code settings.gradle(.kts)}; it holds
 * the wrapper and the {@code gradle.properties} NetBeans writes its settings to, and a subproject has
 * neither. Everything here is plain file logic so it can be tested without an IDE.
 */
final class GradleBuildRoot {

    private GradleBuildRoot() {
    }

    /**
     * The nearest directory, starting at {@code dir} itself and going up, that holds a settings file; null
     * when there is none, meaning {@code dir} is not part of a multi-project layout this can see.
     */
    static File rootOf(File dir) {
        for (File candidate = dir; candidate != null; candidate = candidate.getParentFile()) {
            if (new File(candidate, "settings.gradle").isFile() || new File(candidate, "settings.gradle.kts").isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The directory to look for the Gradle wrapper in when a build is run from {@code dir}: its build root,
     * or {@code dir} itself under no settings file.
     */
    static File wrapperDir(File dir) {
        File root = rootOf(dir);
        return root == null ? dir : root;
    }
}
