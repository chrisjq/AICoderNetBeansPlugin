package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the Gradle wrapper is looked for: a build started in a subproject uses the wrapper of the build root
 * above it, the way NetBeans runs {@code ../gradlew} from the subproject.
 */
class GradleBuildRootTest {

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * The executable a build started in {@code dir} runs.
     */
    private static String executableFor(File dir) {
        return BuildToolLocator.resolve(new BuildToolLocator.Inputs(GradleBuildRoot.wrapperDir(dir),
                BuildToolLocator.Tool.GRADLE, false, true, null, null, null));
    }

    @Test
    void aRootBuildUsesItsOwnWrapper(@TempDir Path root) throws IOException {
        write(root, "settings.gradle", "rootProject.name = 'x'");
        write(root, "gradlew", "#!/bin/sh");

        assertEquals(root.toFile(), GradleBuildRoot.wrapperDir(root.toFile()));
        assertEquals(root.resolve("gradlew").toString(), executableFor(root.toFile()));
    }

    @Test
    void aSubprojectUsesTheRootsWrapper(@TempDir Path root) throws IOException {
        write(root, "settings.gradle.kts", "include(\"app\")");
        write(root, "gradlew", "#!/bin/sh");
        write(root, "app/build.gradle", "plugins { id 'java' }");
        File app = root.resolve("app").toFile();

        assertEquals(root.toFile(), GradleBuildRoot.wrapperDir(app));
        assertEquals(root.resolve("gradlew").toString(), executableFor(app));
        assertEquals("gradle", BuildToolLocator.resolve(new BuildToolLocator.Inputs(app, BuildToolLocator.Tool.GRADLE,
                false, true, null, null, null)), "what a lookup in the subproject directory alone finds");
    }

    @Test
    void aDeeperNestedSubprojectUsesTheRootsWrapper(@TempDir Path root) throws IOException {
        write(root, "settings.gradle", "include('libs:core')");
        write(root, "gradlew", "#!/bin/sh");
        write(root, "libs/core/build.gradle", "");
        File core = root.resolve("libs/core").toFile();

        assertEquals(root.toFile(), GradleBuildRoot.wrapperDir(core));
        assertEquals(root.resolve("gradlew").toString(), executableFor(core));
    }

    @Test
    void theNearestSettingsFileIsTheRoot(@TempDir Path root) throws IOException {
        write(root, "settings.gradle", "include('inner')");
        write(root, "gradlew", "#!/bin/sh");
        write(root, "inner/settings.gradle", "rootProject.name = 'inner'");
        write(root, "inner/gradlew", "#!/bin/sh");
        write(root, "inner/app/build.gradle", "");
        File app = root.resolve("inner/app").toFile();

        assertEquals(root.resolve("inner").toFile(), GradleBuildRoot.wrapperDir(app));
        assertEquals(root.resolve("inner/gradlew").toString(), executableFor(app));
    }

    @Test
    void noWrapperAnywhereFallsBackToGradleOnThePath(@TempDir Path root) throws IOException {
        write(root, "settings.gradle", "include('app')");
        write(root, "app/build.gradle", "");

        assertEquals("gradle", executableFor(root.resolve("app").toFile()));
    }

    @Test
    void aDirectoryUnderNoSettingsFileLooksInItself(@TempDir Path dir) throws IOException {
        write(dir, "build.gradle", "");
        write(dir, "gradlew", "#!/bin/sh");

        assertNull(GradleBuildRoot.rootOf(dir.toFile()));
        assertNull(GradleBuildRoot.rootOf(null));
        assertEquals(dir.toFile(), GradleBuildRoot.wrapperDir(dir.toFile()));
        assertEquals(dir.resolve("gradlew").toString(), executableFor(dir.toFile()));
    }
}
