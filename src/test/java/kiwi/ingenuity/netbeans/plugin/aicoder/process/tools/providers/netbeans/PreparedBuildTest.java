package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PreparedBuildTest {

    private static PreparedBuild build() {
        return new PreparedBuild(null, "s", new File("."), List.of("/opt/maven/bin/mvn", "clean", "install"),
                BuildOutputFormatter.Backend.MAVEN);
    }

    @Test
    void aBuildWithoutAnEnvironmentShowsItsCommandUnchanged() {
        PreparedBuild build = build();

        assertTrue(build.environment().isEmpty());
        assertEquals(List.of("/opt/maven/bin/mvn", "clean", "install"), build.displayCommand());
    }

    @Test
    void theEnvironmentIsShownAheadOfTheExecutableAsNameEqualsValue() {
        PreparedBuild build = build().withEnvironment(Map.of("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk"));

        assertEquals(List.of("JAVA_HOME=/usr/lib/jvm/java-17-openjdk", "/opt/maven/bin/mvn", "clean", "install"),
                build.displayCommand());
        assertEquals(List.of("/opt/maven/bin/mvn", "clean", "install"), build.command(),
                "the command that is run is not changed, only its display");
    }

    @Test
    void anErrorBuildIgnoresAnEnvironment() {
        PreparedBuild error = PreparedBuild.error("Error: nope");

        assertSame(error, error.withEnvironment(Map.of("JAVA_HOME", "/x")));
    }

    @Test
    void withEnvironmentKeepsEveryOtherField() {
        PreparedBuild download = new PreparedBuild(null, "s", new File("."), List.of("mvn"),
                BuildOutputFormatter.Backend.MAVEN, false);

        PreparedBuild with = download.withEnvironment(Map.of("JAVA_HOME", "/x"));

        assertEquals(false, with.countsTowardLongestSuccess());
        assertEquals(download.root(), with.root());
        assertEquals(download.backend(), with.backend());
    }
}
