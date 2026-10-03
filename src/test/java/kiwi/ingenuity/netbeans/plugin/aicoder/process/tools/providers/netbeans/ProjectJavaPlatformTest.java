package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parts of platform resolution that need no IDE: where an Ant project keeps its platform name, and that
 * resolution can never throw into a build.
 */
class ProjectJavaPlatformTest {

    private static void write(Path root, String relative, String... lines) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(lines));
    }

    @Test
    void anAntProjectNamesItsPlatformInProjectProperties(@TempDir Path root) throws IOException {
        write(root, "nbproject/project.properties", "javac.source=17", "platform.active=JDK_17");

        assertEquals("JDK_17", ProjectJavaPlatform.antActivePlatform(root.toFile()));
    }

    @Test
    void thePrivatePropertiesOverrideTheSharedOnes(@TempDir Path root) throws IOException {
        write(root, "nbproject/project.properties", "platform.active=JDK_17");
        write(root, "nbproject/private/private.properties", "platform.active=JDK_21");

        assertEquals("JDK_21", ProjectJavaPlatform.antActivePlatform(root.toFile()));
    }

    @Test
    void aBlankPrivateValueFallsBackToTheSharedOne(@TempDir Path root) throws IOException {
        write(root, "nbproject/project.properties", "platform.active=JDK_17");
        write(root, "nbproject/private/private.properties", "platform.active=");

        assertEquals("JDK_17", ProjectJavaPlatform.antActivePlatform(root.toFile()));
    }

    @Test
    void theDefaultPlatformValueIsReturnedAsWritten(@TempDir Path root) throws IOException {
        write(root, "nbproject/project.properties", "platform.active=default_platform");

        assertEquals(JdkPlatformChooser.DEFAULT_PLATFORM, ProjectJavaPlatform.antActivePlatform(root.toFile()));
    }

    @Test
    void aProjectWithoutAntFilesOrTheKeyHasNoName(@TempDir Path root) throws IOException {
        assertNull(ProjectJavaPlatform.antActivePlatform(root.toFile()), "no nbproject directory at all");
        write(root, "nbproject/project.properties", "javac.source=17");
        assertNull(ProjectJavaPlatform.antActivePlatform(root.toFile()), "no platform.active key");
        assertNull(ProjectJavaPlatform.antActivePlatform(null));
    }

    @Test
    void aGradleBuildNamesItsPlatformInTheRootGradleProperties(@TempDir Path root) throws IOException {
        write(root, "settings.gradle", "rootProject.name = 'gradleproject1'", "include('app')");
        write(root, "gradle.properties", "org.gradle.configuration-cache=true", "", "netbeans.hint.jdkPlatform=JDK_17");

        assertEquals("JDK_17", ProjectJavaPlatform.gradleRootHint(root.toFile()));
    }

    @Test
    void aGradleSubprojectResolvesTheRootsPlatform(@TempDir Path root) throws IOException {
        write(root, "settings.gradle.kts", "rootProject.name = \"gradleproject1\"", "include(\"app\")");
        write(root, "gradle.properties", "netbeans.hint.jdkPlatform=JDK_17");
        write(root, "app/build.gradle", "plugins { id 'java' }");

        assertEquals("JDK_17", ProjectJavaPlatform.gradleRootHint(root.resolve("app").toFile()));
        assertEquals("JDK_17", ProjectJavaPlatform.gradleRootHint(root.resolve("app/src/main").toFile()));
    }

    @Test
    void theNearestSettingsFileDecidesWhichGradlePropertiesIsRead(@TempDir Path root) throws IOException {
        write(root, "settings.gradle", "include('inner')");
        write(root, "gradle.properties", "netbeans.hint.jdkPlatform=JDK_17");
        write(root, "inner/settings.gradle", "rootProject.name = 'inner'");
        write(root, "inner/gradle.properties", "netbeans.hint.jdkPlatform=JDK_21");

        assertEquals("JDK_21", ProjectJavaPlatform.gradleRootHint(root.resolve("inner").toFile()));
    }

    @Test
    void aGradleBuildWithoutTheHintOrAPropertiesFileHasNoName(@TempDir Path root) throws IOException {
        write(root, "settings.gradle", "rootProject.name = 'x'");
        assertNull(ProjectJavaPlatform.gradleRootHint(root.toFile()), "no gradle.properties");
        write(root, "gradle.properties", "org.gradle.parallel=true", "netbeans.hint.jdkPlatform=");
        assertNull(ProjectJavaPlatform.gradleRootHint(root.toFile()), "blank value");
        assertNull(ProjectJavaPlatform.gradleRootHint(null));
    }

    @Test
    void aDirectoryUnderNoGradleBuildHasNoGradleName(@TempDir Path root) throws IOException {
        write(root, "gradle.properties", "netbeans.hint.jdkPlatform=JDK_17");

        assertNull(ProjectJavaPlatform.gradleRootHint(root.toFile()),
                "without a settings file this is not a Gradle build root, so its properties are not read");
    }

    @Test
    void resolvingAProjectNeverThrowsAndAlwaysReturnsAMap(@TempDir Path root) {
        Map<String, String> environment = assertDoesNotThrow(() -> ProjectJavaPlatform.environmentFor(root.toFile()));
        assertNotNull(environment);
        assertDoesNotThrow(() -> ProjectJavaPlatform.environmentFor(new File("/no/such/project")));
        assertDoesNotThrow(() -> ProjectJavaPlatform.environmentFor(null));
    }
}
