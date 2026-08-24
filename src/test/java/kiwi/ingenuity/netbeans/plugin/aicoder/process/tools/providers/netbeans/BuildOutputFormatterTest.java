package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pure-formatting tests for {@link BuildOutputFormatter}. These run without an MCP server registered, so formatResult()
 * exercises its no-log-file fallback branch (full output returned unchanged) — the summarisers themselves are asserted
 * directly via the package-private summarize().
 */
class BuildOutputFormatterTest {

    private static final String MAVEN_SUCCESS_WITH_TESTS = String.join("\n",
            "[INFO] Scanning for projects...",
            "[INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) @ app ---",
            "[INFO] Nothing to compile - all classes are up to date",
            "[INFO] --- maven-surefire-plugin:3.1.2:test (default-test) @ app ---",
            "[INFO] Running com.example.AppTest",
            "[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.example.AppTest",
            "[INFO]",
            "[INFO] Results:",
            "[INFO]",
            "[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0",
            "[INFO]",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] BUILD SUCCESS",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] Total time:  01:01 min",
            "[INFO] Finished at: 2026-08-23T21:53:57+12:00",
            "[INFO] ------------------------------------------------------------------------");

    private static final String MAVEN_SUCCESS_NO_TESTS = String.join("\n",
            "[INFO] Scanning for projects...",
            "[INFO] --- maven-resources-plugin:3.3.1:resources (default-resources) @ app ---",
            "[INFO] Copying 3 resources",
            "[INFO] --- maven-jar-plugin:3.3.0:jar (default-jar) @ app ---",
            "[INFO] Building jar: target/app-1.3.22.jar",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] BUILD SUCCESS",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] Total time:  15.655 s",
            "[INFO] Finished at: 2026-08-23T21:40:12+12:00",
            "[INFO] ------------------------------------------------------------------------");

    private static final String MAVEN_FAILURE_COMPILE = String.join("\n",
            "[INFO] Scanning for projects...",
            "[INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) @ app ---",
            "[INFO] Changes detected - recompiling the module!",
            "[ERROR] COMPILATION ERROR :",
            "[ERROR] /src/main/java/com/example/App.java:[12,30] ';' expected",
            "[ERROR] /src/main/java/com/example/App.java:[14,9] cannot find symbol",
            "[INFO] 2 errors",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] BUILD FAILURE",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] Total time:  4.102 s",
            "[INFO] Finished at: 2026-08-23T22:00:00+12:00",
            "[INFO] ------------------------------------------------------------------------",
            "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile"
            + " (default-compile) on project app: Compilation failure -> [Help 1]",
            "[ERROR]",
            "[ERROR] Re-run Maven using the -X switch to enable full debug logging.");

    private static final String MAVEN_FAILURE_TESTS = String.join("\n",
            "[INFO] Running com.example.CalculatorTest",
            "[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in com.example.CalculatorTest",
            "[INFO] Running com.example.BrokenTest",
            "[INFO]",
            "[INFO] Results:",
            "[INFO]",
            "[ERROR] Failures:",
            "[ERROR]   BrokenTest.testAdd:23 expected:<1> but was:<2>",
            "[ERROR]   BrokenTest.testSub:31 Arrays first differed at element [0]; expected:<5> but was:<6>",
            "[INFO]",
            "[ERROR] Errors:",
            "[ERROR]   BrokenTest.testBoom:40 NullPointer",
            "[INFO]",
            "[ERROR] Tests run: 12, Failures: 2, Errors: 1, Skipped: 0",
            "[INFO]",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] BUILD FAILURE",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] Total time:  8.500 s",
            "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.1.2:test"
            + " (default-test) on project app: There are test failures.");

    private static final String GRADLE_SUCCESS = String.join("\n",
            "> Task :compileJava UP-TO-DATE",
            "> Task :processResources UP-TO-DATE",
            "> Task :classes UP-TO-DATE",
            "> Task :jar",
            "> Task :assemble",
            "> Task :check",
            "> Task :build",
            "Deprecated Gradle features were used in this build.",
            "BUILD SUCCESSFUL in 12s",
            "5 actionable tasks: 1 executed, 4 up-to-date");

    private static final String GRADLE_FAILURE = String.join("\n",
            "> Task :compileJava UP-TO-DATE",
            "> Task :test FAILED",
            "",
            "FAILURE: Build failed with an exception.",
            "",
            "* What went wrong:",
            "Execution failed for task ':test'.",
            "> There were failing tests. See the report at: file:///proj/build/reports/tests/test/index.html",
            "",
            "* Try:",
            "> Run with --stacktrace option to get the stack trace.",
            "> Run with --info or --debug option to get more log output.",
            "",
            "* Exception is:",
            "org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':test'.",
            "        at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.lambda$executeIfValid$1(ExecuteActionsTaskExecuter.java:130)",
            "",
            "BUILD FAILED in 9s",
            "4 actionable tasks: 3 executed, 1 up-to-date");

    private static final String ANT_SUCCESS = String.join("\n",
            "init:",
            "compile:",
            "jar:",
            "Building jar: dist/plugin.jar",
            "BUILD SUCCESSFUL",
            "Total time: 3 seconds");

    private static final String ANT_FAILURE = String.join("\n",
            "init:",
            "deps-clean:",
            "compile:",
            "    [javac] Compiling 42 source files to /proj/build/classes",
            "    [javac] /proj/src/com/example/App.java:12: error: ';' expected",
            "    [javac] /proj/src/com/example/Util.java:88: error: cannot find symbol",
            "    [javac]   symbol:   variable missing",
            "/proj/build.xml:77: The following error occurred while executing this line:",
            "BUILD FAILED",
            "/proj/build.xml:77: Compile failed; see the compiler error output for details.",
            "	at org.apache.tools.ant.ProjectHelper.addLocationToBuildException(ProjectHelper.java:586)",
            "	at org.apache.tools.ant.taskdefs.Javac.compile(Javac.java:1424)",
            "Total time: 2 seconds");

    // ---- Maven ----
    @Test
    void summarizeMaven_successWithTests_tailFromResultsMarker() {
        String s = BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.MAVEN, true,
                MAVEN_SUCCESS_WITH_TESTS);
        assertTrue(s.startsWith("[INFO] Results:"));
        assertTrue(s.contains("[INFO] Tests run: 4, Failures: 0"));
        assertTrue(s.contains("[INFO] BUILD SUCCESS"));
        assertTrue(s.contains("[INFO] Total time:  01:01 min"));
        assertFalse(s.contains("Scanning for projects"));
        assertFalse(s.contains("Running com.example.AppTest"));
    }

    @Test
    void summarizeMaven_successNoTests_resultBannerThroughEnd() {
        String s = BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.MAVEN, true,
                MAVEN_SUCCESS_NO_TESTS);
        assertTrue(s.startsWith("[INFO] BUILD SUCCESS"));
        assertTrue(s.contains("[INFO] Total time:  15.655 s"));
        assertFalse(s.contains("Copying 3 resources"));
        assertFalse(s.contains("Building jar"), "no test results ran, so only banner + timings");
    }

    @Test
    void summarizeMaven_failureCompile_allErrorLinesPlusTail_verbatim() {
        String s = BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.MAVEN, false,
                MAVEN_FAILURE_COMPILE);
        assertTrue(s.startsWith("[ERROR] COMPILATION ERROR :"));
        assertTrue(s.contains("[ERROR] /src/main/java/com/example/App.java:[12,30] ';' expected"),
                "every [ERROR] line must be present verbatim");
        assertTrue(s.contains("[ERROR] /src/main/java/com/example/App.java:[14,9] cannot find symbol"));
        assertTrue(s.contains("[INFO] BUILD FAILURE"));
        assertTrue(s.endsWith("Re-run Maven using the -X switch to enable full debug logging."));
        assertFalse(s.contains("Scanning for projects"), "passing-class noise stays out");
    }

    @Test
    void summarizeMaven_failureTests_completeFailuresSectionNeverTruncated() {
        String s = BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.MAVEN, false,
                MAVEN_FAILURE_TESTS);
        assertTrue(s.startsWith("[INFO] Results:"));
        assertTrue(s.contains("BrokenTest.testAdd:23 expected:<1> but was:<2>"));
        assertTrue(s.contains("BrokenTest.testSub:31 Arrays first differed at element [0]; expected:<5> but was:<6>"));
        assertTrue(s.contains("BrokenTest.testBoom:40 NullPointer"));
        assertTrue(s.contains("[ERROR] Tests run: 12, Failures: 2, Errors: 1, Skipped: 0"));
        assertTrue(s.contains("There are test failures."));
        assertFalse(s.contains("CalculatorTest"));
    }

    @Test
    void summarizeMaven_unrecognisedShape_fallsBackToNull() {
        assertNull(BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.MAVEN, true, "garbage"));
        assertNull(BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.MAVEN, false, "garbage"));
    }

    // ---- Gradle ----
    @Test
    void summarizeGradle_success_justResultLineOnward() {
        String s = BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.GRADLE, true, GRADLE_SUCCESS);
        assertEquals("BUILD SUCCESSFUL in 12s\n5 actionable tasks: 1 executed, 4 up-to-date", s);
    }

    @Test
    void summarizeGradle_failure_wholeFailureBlockFromHeading() {
        String s = BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.GRADLE, false, GRADLE_FAILURE);
        assertTrue(s.startsWith("FAILURE: Build failed with an exception."));
        assertTrue(s.contains("> There were failing tests."));
        assertTrue(s.contains("reports/tests/test/index.html"));
        assertTrue(s.contains("TaskExecutionException"));
        assertTrue(s.endsWith("4 actionable tasks: 3 executed, 1 up-to-date"));
        assertFalse(s.contains("> Task :compileJava UP-TO-DATE"));
    }

    @Test
    void summarizeGradle_failureNoHeading_startsAtBuildFailedBanner() {
        String s = BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.GRADLE, false,
                "some noise\nBUILD FAILED in 2s");
        assertEquals("BUILD FAILED in 2s", s);
    }

    @Test
    void summarizeGradle_unrecognisedShape_fallsBackToNull() {
        assertNull(BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.GRADLE, true, "garbage"));
        assertNull(BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.GRADLE, false, "garbage"));
    }

    // ---- Ant ----
    @Test
    void summarizeAnt_success_trailerOnly() {
        String s = BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.ANT, true, ANT_SUCCESS);
        assertEquals("BUILD SUCCESSFUL\nTotal time: 3 seconds", s);
    }

    @Test
    void summarizeAnt_failure_javacDiagnosticsPlusTrailer_verbatim() {
        String s = BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.ANT, false, ANT_FAILURE);
        assertTrue(s.contains("/proj/src/com/example/App.java:12: error: ';' expected"));
        assertTrue(s.contains("/proj/src/com/example/Util.java:88: error: cannot find symbol"));
        assertTrue(s.contains("BUILD FAILED"));
        assertTrue(s.contains("Compile failed; see the compiler error output for details."));
        assertTrue(s.contains("ProjectHelper.addLocationToBuildException"));
        assertFalse(s.contains("[javac] Compiling 42 source files"), "routine progress lines stay out");
        assertTrue(s.endsWith("Total time: 2 seconds"));
    }

    @Test
    void summarizeAnt_unrecognisedShape_fallsBackToNull() {
        assertNull(BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.ANT, true, "garbage"));
        assertNull(BuildOutputFormatter.summarize(BuildOutputFormatter.Backend.ANT, false, "garbage"));
    }

    // ---- formatResult glue (no MCP server registered here -> full-output fallback) ----
    @Test
    void formatResult_withoutServer_returnsHeaderPlusFullOutput_noPathLine() {
        String out = BuildOutputFormatter.formatResult("ses_x", BuildOutputFormatter.Backend.MAVEN,
                true, 0, MAVEN_SUCCESS_WITH_TESTS);
        assertTrue(out.startsWith("BUILD SUCCESS\n\n"));
        assertTrue(out.contains(MAVEN_SUCCESS_WITH_TESTS));
        assertFalse(out.contains("Complete log written to:"));
    }

    @Test
    void formatResult_failureWithoutServer_keepsExitCodeAndFullOutput() {
        String out = BuildOutputFormatter.formatResult("ses_x", BuildOutputFormatter.Backend.GRADLE,
                false, 1, GRADLE_FAILURE);
        assertTrue(out.startsWith("BUILD FAILED (exit 1)\n\n"));
        assertTrue(out.endsWith(GRADLE_FAILURE));
        assertFalse(out.contains("Complete log written to:"));
    }

    @Test
    void attachLog_withoutServer_keepsMessageAndFullOutput() {
        String out = BuildOutputFormatter.attachLog("ses_x", BuildOutputFormatter.Backend.ANT,
                "Timed out after 600s", ANT_SUCCESS);
        assertTrue(out.startsWith("Timed out after 600s\n\n"));
        assertTrue(out.endsWith(ANT_SUCCESS));
        assertFalse(out.contains("Complete log written to:"));
    }
}
