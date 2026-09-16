package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

class BuildProviderPrepareTest {

    @Test
    void mavenRejectsBadOptionsBeforeResolvingProject() {
        var options = new BuildAndTestMavenProvider.MavenBuildOptions(List.of(), List.of(), false, null, true, false,
                                                                  false, List.of(), null, null, false);
        PreparedBuild prepared = BuildAndTestMavenProvider.prepareBuildProject("s", "/does/not/exist", options);
        assertEquals("Error: goals must not be empty", prepared.error());
        assertNotNull(prepared.command());
    }

    @Test
    void gradleRejectsBadOptionsBeforeResolvingProject() {
        var options = new BuildAndTestGradleProvider.GradleBuildOptions(List.of(), true, false, false, null, null, false, false);
        PreparedBuild prepared = BuildAndTestGradleProvider.prepareBuildProject("s", "/does/not/exist", options);
        assertEquals("Error: tasks must not be empty", prepared.error());
    }

    @Test
    void antRejectsBadOptionsBeforeResolvingProject() {
        var options = new BuildAndTestAntProvider.AntBuildOptions(List.of(), null, false);
        PreparedBuild prepared = BuildAndTestAntProvider.prepareBuildProject("s", "/does/not/exist", options);
        assertEquals("Error: targets must not be empty", prepared.error());
    }
}
