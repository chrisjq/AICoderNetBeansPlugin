package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildToolLocator.Inputs;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildToolLocator.Tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The executable-resolution rules for the Maven and Gradle build tools.
 *
 * <p>
 * Before this existed both providers ran a bare
 * {@code "mvn"} / {@code "gradle"} off the PATH, so the build tools failed
 * outright on a machine that had NetBeans (with its own bundled Maven) but no
 * separately installed Maven — {@code Cannot run program "mvn"}. The rules
 * below mirror what the IDE itself would launch.
 *
 * <p>
 * {@link BuildToolLocator#resolve} is pure on purpose: every fact the IDE would
 * supply arrives as a parameter, so the ordering can be pinned here without a
 * NetBeans runtime. The production wiring that reads NbPreferences and
 * InstalledFileLocator is the thin part, and is the only part that cannot be
 * exercised in a unit test.
 */
class BuildToolLocatorTest {

    private static File exe(Path home, String name) throws IOException {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path f = bin.resolve(name);
        Files.writeString(f, "#!/bin/sh\n");
        f.toFile().setExecutable(true);
        return f.toFile();
    }

    private static Inputs maven(Path root) {
        return new Inputs(root.toFile(), Tool.MAVEN, false, true, null, null, null);
    }

    // ---- 1. the wrapper ----
    @Test
    void wrapperWinsWhenThePreferenceAllowsIt(@TempDir Path root) throws IOException {
        Path w = root.resolve("mvnw");
        Files.writeString(w, "#!/bin/sh\n");
        assertEquals(w.toFile().getAbsolutePath(), BuildToolLocator.resolve(maven(root)));
    }

    /**
     * NetBeans has its own {@code preferWrapper} setting, and "behave like the
     * IDE" means honouring it. A user who turned it off wants the configured
     * runtime even in a project that ships mvnw.
     */
    @Test
    void wrapperIsSkippedWhenThePreferenceIsOff(@TempDir Path root, @TempDir Path home) throws IOException {
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        File mvn = exe(home, "mvn");
        String resolved = BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.MAVEN, false, false, home.toString(), null, null));
        assertEquals(mvn.getAbsolutePath(), resolved, "preferWrapper=false must fall through to the runtime");
    }

    /**
     * The absent-key default must be "prefer the wrapper", because that is what
     * this code did before the setting was consulted at all. Defaulting the
     * other way would silently stop honouring mvnw for every existing project.
     */
    @Test
    void wrapperIsPreferredByDefaultSoExistingProjectsDoNotChangeBehaviour(@TempDir Path root) throws IOException {
        Path w = root.resolve("mvnw");
        Files.writeString(w, "#!/bin/sh\n");
        assertTrue(BuildToolLocator.preferWrapperDefault(), "absent preferWrapper must mean true");
        assertEquals(w.toFile().getAbsolutePath(), BuildToolLocator.resolve(maven(root)));
    }

    // ---- 2. a configured runtime ----
    @Test
    void aConfiguredRuntimeIsUsedWhenThereIsNoWrapper(@TempDir Path root, @TempDir Path home) throws IOException {
        File mvn = exe(home, "mvn");
        String resolved = BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.MAVEN, false, true, home.toString(), null, null));
        assertEquals(mvn.getAbsolutePath(), resolved);
    }

    /**
     * NetBeans stores the runtimes as a {@code File.pathSeparator}-joined list,
     * so the first entry that actually has an executable wins and a stale entry
     * does not poison the rest.
     */
    @Test
    void theFirstRuntimeThatActuallyExistsWins(@TempDir Path root, @TempDir Path home) throws IOException {
        File mvn = exe(home, "mvn");
        String pref = "/does/not/exist" + File.pathSeparator + home;
        String resolved = BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.MAVEN, false, true, pref, null, null));
        assertEquals(mvn.getAbsolutePath(), resolved, "a dead entry must not stop the search");
    }

    @Test
    void blankRuntimeEntriesAreIgnored(@TempDir Path root, @TempDir Path home) throws IOException {
        File mvn = exe(home, "mvn");
        String pref = File.pathSeparator + "  " + File.pathSeparator + home;
        assertEquals(mvn.getAbsolutePath(), BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.MAVEN, false, true, pref, null, null)));
    }

    // ---- 3. the bundled copy ----
    @Test
    void theBundledCopyIsUsedWhenNothingIsConfigured(@TempDir Path root, @TempDir Path nb) throws IOException {
        File bundled = exe(nb, "mvn");
        String resolved = BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.MAVEN, false, true, null, bundled, null));
        assertEquals(bundled.getAbsolutePath(), resolved,
                "with NetBeans' own Maven present, a machine with no mvn on PATH must still build");
    }

    @Test
    void aConfiguredRuntimeOutranksTheBundledCopy(@TempDir Path root, @TempDir Path home, @TempDir Path nb)
            throws IOException {
        File mvn = exe(home, "mvn");
        File bundled = exe(nb, "mvn");
        String resolved = BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.MAVEN, false, true, home.toString(), bundled, null));
        assertEquals(mvn.getAbsolutePath(), resolved, "an explicit choice must beat the bundled default");
    }

    // ---- 4. the environment ----
    @Test
    void mavenHomeFromTheEnvironmentIsUsedBeforeGivingUp(@TempDir Path root, @TempDir Path home) throws IOException {
        File mvn = exe(home, "mvn");
        String resolved = BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.MAVEN, false, true, null, null, home.toString()));
        assertEquals(mvn.getAbsolutePath(), resolved);
    }

    /**
     * NetBeans' own default-runtime discovery tolerates a home given as
     * {@code .../bin}, so this must too.
     */
    @Test
    void anEnvironmentHomePointingAtBinIsAccepted(@TempDir Path root, @TempDir Path home) throws IOException {
        File mvn = exe(home, "mvn");
        String resolved = BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.MAVEN, false, true, null, null, home.resolve("bin").toString()));
        assertEquals(mvn.getAbsolutePath(), resolved);
    }

    // ---- 5. the last resort ----
    @Test
    void thePathNameIsTheLastResort(@TempDir Path root) {
        assertEquals("mvn", BuildToolLocator.resolve(maven(root)),
                "with nothing else available the old PATH behaviour must remain");
    }

    @Test
    void windowsUsesTheWindowsNames(@TempDir Path root) {
        assertEquals("mvn.cmd", BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.MAVEN, true, true, null, null, null)));
        assertEquals("gradle.bat", BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.GRADLE, true, true, null, null, null)));
    }

    // ---- gradle behaves the same way ----
    @Test
    void gradleFollowsTheSameRules(@TempDir Path root, @TempDir Path home) throws IOException {
        Path w = root.resolve("gradlew");
        Files.writeString(w, "#!/bin/sh\n");
        assertEquals(w.toFile().getAbsolutePath(), BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.GRADLE, false, true, null, null, null)));

        File gradle = exe(home, "gradle");
        assertEquals(gradle.getAbsolutePath(), BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.GRADLE, false, false, home.toString(), null, null)));
    }

    @Test
    void gradleFallsBackToItsOwnPathName(@TempDir Path root) {
        assertEquals("gradle", BuildToolLocator.resolve(new Inputs(
                root.toFile(), Tool.GRADLE, false, true, null, null, null)));
    }

    // ---- a wrapper must never be mistaken for the other tool's ----
    @Test
    void aGradleWrapperDoesNotSatisfyMaven(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n");
        assertEquals("mvn", BuildToolLocator.resolve(maven(root)));
    }
}
