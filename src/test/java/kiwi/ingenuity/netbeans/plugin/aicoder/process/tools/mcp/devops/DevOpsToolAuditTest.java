package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildAntProjectParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildAntProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildGradleProjectParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildGradleProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildMavenProjectParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildMavenProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildAntProjectParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildAntProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildGradleProjectParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildGradleProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildMavenProjectParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildMavenProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.DownloadMavenJavadocParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.DownloadMavenJavadocTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.DownloadMavenSourcesParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.DownloadMavenSourcesTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunAntTestsParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunAntTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunGradleTestsParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunGradleTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunMavenTestsParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunMavenTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves every advertised parameter of the devops/build/test MCP tools by invoking {@code handle()} against throwaway
 * temp directories that contain fake {@code mvnw} / {@code gradlew} wrapper scripts. The fake wrapper records its argv
 * to a file and exits zero, so the exact command line each tool builds is captured without ever spawning a real build —
 * the plugin's own repository is never built and nothing downloads.
 *
 * The {@code isFileAllowed} gate is exercised for real: a genuine {@code McpHookServer} is started and the test session
 * registered with only the maven/gradle/ant temp roots as allowed project directories.
 */
class DevOpsToolAuditTest {

    private static final String SESSION_ID = "devops-audit";

    private static final String NO_PROJECT = "No open project found";

    @TempDir
    Path tempDir;

    private Path mavenRoot;
    private Path gradleRoot;
    private Path antRoot;
    private Path outsideDir;
    private final AbstractAiSession session = newSession();

    @BeforeEach
    void setUp() throws Exception {
        mavenRoot = Files.createDirectories(tempDir.resolve("mvn-project"));
        gradleRoot = Files.createDirectories(tempDir.resolve("gradle-project"));
        antRoot = Files.createDirectories(tempDir.resolve("ant-project"));
        outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        fakeWrapper(mavenRoot, "mvnw");
        fakeWrapper(gradleRoot, "gradlew");
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("devops-audit-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        McpServerRegistry.getServer().registerSession(SESSION_ID, AiTypeEnum.CLAUDE,
                                                      List.of(mavenRoot.toFile(), gradleRoot.toFile(), antRoot.toFile()), true);
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    // ---- BuildAndTestMavenProvider: BuildMavenProject (projectPath -> "package -DskipTests") ----
    @Test
    void mavenBuild_withInScopeProjectPathRunsMvnwWithPackageGoals() throws Exception {
        String result = new BuildMavenProjectTool().handle(
                args(BuildMavenProjectParamEnum.PROJECT_PATH.key(), mavenRoot.toString()), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("package -DskipTests --no-transfer-progress", recordedArgs(mavenRoot),
                     "BuildMavenProject must run the wrapper in the supplied project dir with the package goal");
    }

    @Test
    void mavenBuild_outsideScopeProjectPathDenied() throws Exception {
        String result = new BuildMavenProjectTool().handle(
                args(BuildMavenProjectParamEnum.PROJECT_PATH.key(), outsideDir.toString()), session);

        assertEquals("Access denied: " + outsideDir, result, "projectPath outside the session scope must be refused before any build");
        assertNoWrapperRan(mavenRoot);
    }

    @Test
    void mavenBuild_missingProjectPathFindsNoOpenProject() throws Exception {
        String result = new BuildMavenProjectTool().handle(args(), session);

        assertEquals("projectPath is required", result, "missing projectPath must be rejected explicitly");
        assertNoWrapperRan(mavenRoot);
    }

    @Test
    void mavenBuild_nonexistentProjectPathDenied() throws Exception {
        String result = new BuildMavenProjectTool().handle(
                args(BuildMavenProjectParamEnum.PROJECT_PATH.key(), tempDir.resolve("missing").toString()), session);

        assertEquals("Not a project directory: " + tempDir.resolve("missing"), result, "a projectPath that is not an existing directory must be refused");
        assertNoWrapperRan(mavenRoot);
    }

    @Test
    void mavenBuild_noServerDeniesEvenInScopeDir() throws Exception {
        McpServerRegistry.stopAll();
        String result = new BuildMavenProjectTool().handle(
                args(BuildMavenProjectParamEnum.PROJECT_PATH.key(), mavenRoot.toString()), session);

        assertEquals("Access denied: " + mavenRoot, result, "the isFileAllowed gate fails closed when no server is registered");
        assertNoWrapperRan(mavenRoot);
    }

    // ---- CleanAndBuildMavenProject (projectPath -> "clean package -DskipTests") ----
    @Test
    void cleanAndBuildMaven_runsMvnwWithCleanPackageGoals() throws Exception {
        String result = new CleanAndBuildMavenProjectTool().handle(
                args(CleanAndBuildMavenProjectParamEnum.PROJECT_PATH.key(), mavenRoot.toString()), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("clean package -DskipTests --no-transfer-progress", recordedArgs(mavenRoot),
                     "CleanAndBuildMavenProject must differ from BuildMavenProject by the leading clean goal");
    }

    // ---- DownloadMavenSources / DownloadMavenJavadoc (projectPath -> dependency goals) ----
    @Test
    void downloadMavenSources_runsMvnwWithDependencySources() throws Exception {
        String result = new DownloadMavenSourcesTool().handle(
                args(DownloadMavenSourcesParamEnum.PROJECT_PATH.key(), mavenRoot.toString()), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("dependency:sources --no-transfer-progress", recordedArgs(mavenRoot),
                     "DownloadMavenSources must run the dependency:sources goal");
    }

    @Test
    void downloadMavenJavadoc_runsMvnwWithDependencyResolveClassifier() throws Exception {
        String result = new DownloadMavenJavadocTool().handle(
                args(DownloadMavenJavadocParamEnum.PROJECT_PATH.key(), mavenRoot.toString()), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("dependency:resolve -Dclassifier=javadoc --no-transfer-progress", recordedArgs(mavenRoot),
                     "DownloadMavenJavadoc must run the dependency:resolve goal with the javadoc classifier");
    }

    // ---- RunMavenTests (projectPath + testClass -> "test" / "test -Dtest=<class>") ----
    @Test
    void runMavenTests_withoutTestClassRunsWholeSuite() throws Exception {
        String result = new RunMavenTestsTool().handle(
                args(RunMavenTestsParamEnum.PROJECT_PATH.key(), mavenRoot.toString()), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("test --no-transfer-progress", recordedArgs(mavenRoot),
                     "RunMavenTests without testClass must run the plain test goal");
    }

    @Test
    void runMavenTests_blankTestClassRunsWholeSuite() throws Exception {
        String result = new RunMavenTestsTool().handle(args(
                RunMavenTestsParamEnum.PROJECT_PATH.key(), mavenRoot.toString(),
                RunMavenTestsParamEnum.TEST_CLASS.key(), ""), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("test --no-transfer-progress", recordedArgs(mavenRoot),
                     "a blank testClass means no filter, not a refused selector");
    }

    @Test
    void runMavenTests_withTestClassAddsDtestFilter() throws Exception {
        String result = new RunMavenTestsTool().handle(args(
                RunMavenTestsParamEnum.PROJECT_PATH.key(), mavenRoot.toString(),
                RunMavenTestsParamEnum.TEST_CLASS.key(), "com.example.MyServiceTest"), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("test -Dtest=com.example.MyServiceTest --no-transfer-progress", recordedArgs(mavenRoot),
                     "testClass must be forwarded as -Dtest=<class>");
    }

    // ---- BuildAndTestGradleProvider (projectPath -> "build -x test"; testClass -> "--tests") ----
    @Test
    void gradleBuild_withInScopeProjectPathRunsGradlew() throws Exception {
        String result = new BuildGradleProjectTool().handle(
                args(BuildGradleProjectParamEnum.PROJECT_PATH.key(), gradleRoot.toString()), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("build -x test --no-daemon", recordedArgs(gradleRoot),
                     "BuildGradleProject must run the gradle wrapper with the build task excluding tests");
    }

    @Test
    void gradleBuild_outsideScopeProjectPathDenied() throws Exception {
        String result = new BuildGradleProjectTool().handle(
                args(BuildGradleProjectParamEnum.PROJECT_PATH.key(), outsideDir.toString()), session);

        assertEquals("Access denied: " + outsideDir, result, "gradle build with an out-of-scope projectPath must be refused");
        assertNoWrapperRan(gradleRoot);
    }

    @Test
    void runGradleTests_withoutTestClassRunsTestTask() throws Exception {
        String result = new RunGradleTestsTool().handle(
                args(RunGradleTestsParamEnum.PROJECT_PATH.key(), gradleRoot.toString()), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("test --no-daemon", recordedArgs(gradleRoot),
                     "RunGradleTests without testClass must run the plain test task");
    }

    @Test
    void runGradleTests_withTestClassAddsTestsFilter() throws Exception {
        String result = new RunGradleTestsTool().handle(args(
                RunGradleTestsParamEnum.PROJECT_PATH.key(), gradleRoot.toString(),
                RunGradleTestsParamEnum.TEST_CLASS.key(), "com.example.MyServiceTest"), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("test --tests com.example.MyServiceTest --no-daemon", recordedArgs(gradleRoot),
                     "testClass must be forwarded as --tests <class>");
    }

    @Test
    void invalidTestSelectorsAreRefusedBeforeMavenGradleOrAntLaunch() throws Exception {
        for (String selector : List.of("-x", "--offline", "a b", "a\n b")) {
            String maven = new RunMavenTestsTool().handle(args(
                    RunMavenTestsParamEnum.PROJECT_PATH.key(), mavenRoot.toString(),
                    RunMavenTestsParamEnum.TEST_CLASS.key(), selector), session);
            assertTrue(maven.startsWith("Error:"), maven);
            assertNoWrapperRan(mavenRoot);
            // Gradle is the case that mattered: testClass is its own argv element after --tests, so an unvalidated
            // "--offline" would have reached gradlew as a real flag.
            String gradle = new RunGradleTestsTool().handle(args(
                    RunGradleTestsParamEnum.PROJECT_PATH.key(), gradleRoot.toString(),
                    RunGradleTestsParamEnum.TEST_CLASS.key(), selector), session);
            assertTrue(gradle.startsWith("Error:"), gradle);
            assertNoWrapperRan(gradleRoot);
        }
        for (String selector : List.of("-x", "--offline")) {
            String ant = new RunAntTestsTool().handle(args(
                    RunAntTestsParamEnum.PROJECT_PATH.key(), antRoot.toString(),
                    RunAntTestsParamEnum.TEST_CLASS.key(), selector), session);
            assertTrue(ant.startsWith("Error:") && ant.contains("testClass"), ant);
        }
    }

    @Test
    void validTestSelectorsReachMavenAndGradleWrappers() throws Exception {
        String maven = new RunMavenTestsTool().handle(args(
                RunMavenTestsParamEnum.PROJECT_PATH.key(), mavenRoot.toString(),
                RunMavenTestsParamEnum.TEST_CLASS.key(), "A,B"), session);
        assertTrue(maven.startsWith("BUILD"), maven);
        assertEquals("test -Dtest=A,B --no-transfer-progress", recordedArgs(mavenRoot));
        String gradle = new RunGradleTestsTool().handle(args(
                RunGradleTestsParamEnum.PROJECT_PATH.key(), gradleRoot.toString(),
                RunGradleTestsParamEnum.TEST_CLASS.key(), "Outer$Inner"), session);
        assertTrue(gradle.startsWith("BUILD"), gradle);
        assertEquals("test --tests Outer$Inner --no-daemon", recordedArgs(gradleRoot));
    }

    // ---- BuildAndTestAntProvider (projectPath gate only; the ant binary itself cannot be faked) ----
    @Test
    void antBuild_withInScopeProjectPathIsNotRefused() throws Exception {
        String result = new BuildAntProjectTool().handle(
                args(BuildAntProjectParamEnum.PROJECT_PATH.key(), antRoot.toString()), session);

        assertNotEquals(NO_PROJECT, result,
                        "an in-scope projectPath must pass the gate and reach the ant launcher, whatever ant then reports");
    }

    @Test
    void antBuild_outsideScopeProjectPathDenied() throws Exception {
        String result = new BuildAntProjectTool().handle(
                args(BuildAntProjectParamEnum.PROJECT_PATH.key(), outsideDir.toString()), session);

        assertEquals("Access denied: " + outsideDir, result, "ant build with an out-of-scope projectPath must be refused");
    }

    @Test
    void runAntTests_withInScopeProjectPathIsNotRefused() throws Exception {
        String result = new RunAntTestsTool().handle(args(
                RunAntTestsParamEnum.PROJECT_PATH.key(), antRoot.toString(),
                RunAntTestsParamEnum.TEST_CLASS.key(), "com.example.MyServiceTest"), session);

        assertNotEquals(NO_PROJECT, result,
                        "RunAntTests with an in-scope projectPath + testClass must reach the ant launcher");
    }

    @Test
    void allDevopsToolsAdvertiseRequiredProjectPath() {
        List<McpToolInterface> tools = List.of(
                new BuildAntProjectTool(),
                new BuildGradleProjectTool(),
                new BuildMavenProjectTool(),
                new CleanAndBuildMavenProjectTool(),
                new DownloadMavenJavadocTool(),
                new DownloadMavenSourcesTool(),
                new RunAntTestsTool(),
                new RunGradleTestsTool(),
                new RunMavenTestsTool());
        for (McpToolInterface tool : tools) {
            JsonObject schema = tool.schema(java.util.Set.of())
                    .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
            JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
            assertTrue(props.has(GitCommonParamEnum.PROJECT_PATH.key()));
            assertEquals(1, schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key()).size());
            assertEquals(GitCommonParamEnum.PROJECT_PATH.key(),
                         schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key()).get(0).getAsString());
        }
    }

    @Test
    void allDevopsToolsRejectMissingProjectPathAtRuntime() throws Exception {
        List<McpToolInterface> tools = List.of(
                new BuildAntProjectTool(),
                new BuildGradleProjectTool(),
                new BuildMavenProjectTool(),
                new CleanAndBuildMavenProjectTool(),
                new DownloadMavenJavadocTool(),
                new DownloadMavenSourcesTool(),
                new RunAntTestsTool(),
                new RunGradleTestsTool(),
                new RunMavenTestsTool());
        for (McpToolInterface tool : tools) {
            String result = tool.handle(args(), session);
            assertEquals("projectPath is required", result, tool.schema(java.util.Set.of())
                         .get(ToolSchemaKeyEnum.NAME.key()).getAsString()
                         + " must reject missing projectPath");
        }
    }

    // ---- #5 / F2: option coverage ----
    @Test
    void mavenBuild_customGoalsReplaceDefaultEntirely() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildMavenProjectParamEnum.PROJECT_PATH.key(), mavenRoot.toString());
        o.add(BuildMavenProjectParamEnum.GOALS.key(), stringArray("verify"));
        String result = new BuildMavenProjectTool().handle(new ToolRequestArguments(o), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("verify -DskipTests --no-transfer-progress", recordedArgs(mavenRoot),
                     "custom goals must replace \"package\", not merge with it, while skipTests keeps its own default");
    }

    @Test
    void mavenBuild_skipTestsFalseOmitsTheFlag() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildMavenProjectParamEnum.PROJECT_PATH.key(), mavenRoot.toString());
        o.addProperty(BuildMavenProjectParamEnum.SKIP_TESTS.key(), false);
        String result = new BuildMavenProjectTool().handle(new ToolRequestArguments(o), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("package --no-transfer-progress", recordedArgs(mavenRoot),
                     "skipTests=false must omit -DskipTests entirely, not pass it as false");
    }

    @Test
    void mavenBuild_everyOptionProducesItsExpectedFlag() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildMavenProjectParamEnum.PROJECT_PATH.key(), mavenRoot.toString());
        o.add(BuildMavenProjectParamEnum.PROJECT_LIST.key(), stringArray("module-a", "module-b"));
        o.addProperty(BuildMavenProjectParamEnum.ALSO_MAKE.key(), true);
        o.addProperty(BuildMavenProjectParamEnum.RESUME_FROM.key(), ":module-b");
        o.addProperty(BuildMavenProjectParamEnum.OFFLINE.key(), true);
        o.addProperty(BuildMavenProjectParamEnum.UPDATE_SNAPSHOTS.key(), true);
        o.add(BuildMavenProjectParamEnum.PROFILES.key(), stringArray("ci"));
        JsonObject props = new JsonObject();
        props.addProperty("my.prop", "hello");
        o.add(BuildMavenProjectParamEnum.PROPERTIES.key(), props);
        o.addProperty(BuildMavenProjectParamEnum.THREADS.key(), "4");
        o.addProperty(BuildMavenProjectParamEnum.FAIL_AT_END.key(), true);

        String result = new BuildMavenProjectTool().handle(new ToolRequestArguments(o), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("package -pl module-a,module-b -am -rf :module-b -DskipTests -o -U -P ci -Dmy.prop=hello -T 4 -fae --no-transfer-progress",
                     recordedArgs(mavenRoot), "every Maven option must translate to its documented flag, in order");
    }

    @Test
    void mavenBuild_goalStartingWithDashIsRejectedBeforeAnyProcessRuns() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildMavenProjectParamEnum.PROJECT_PATH.key(), mavenRoot.toString());
        o.add(BuildMavenProjectParamEnum.GOALS.key(), stringArray("--settings=/tmp/evil.xml"));

        String result = new BuildMavenProjectTool().handle(new ToolRequestArguments(o), session);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("must not start with '-'"), result);
        assertNoWrapperRan(mavenRoot);
    }

    @Test
    void mavenBuild_propertyKeyWithBadCharactersIsRejected() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildMavenProjectParamEnum.PROJECT_PATH.key(), mavenRoot.toString());
        JsonObject props = new JsonObject();
        props.addProperty("bad key=x", "value");
        o.add(BuildMavenProjectParamEnum.PROPERTIES.key(), props);

        String result = new BuildMavenProjectTool().handle(new ToolRequestArguments(o), session);

        assertTrue(result.startsWith("Error:"), result);
        assertNoWrapperRan(mavenRoot);
    }

    @Test
    void mavenBuild_projectListEntryWithShellMetacharacterIsRejected() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildMavenProjectParamEnum.PROJECT_PATH.key(), mavenRoot.toString());
        o.add(BuildMavenProjectParamEnum.PROJECT_LIST.key(), stringArray("module; rm -rf /"));

        String result = new BuildMavenProjectTool().handle(new ToolRequestArguments(o), session);

        assertTrue(result.startsWith("Error:"), result);
        assertNoWrapperRan(mavenRoot);
    }

    @Test
    void gradleBuild_everyOptionProducesItsExpectedFlag() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildGradleProjectParamEnum.PROJECT_PATH.key(), gradleRoot.toString());
        o.addProperty(BuildGradleProjectParamEnum.OFFLINE.key(), true);
        o.addProperty(BuildGradleProjectParamEnum.REFRESH_DEPENDENCIES.key(), true);
        JsonObject props = new JsonObject();
        props.addProperty("env", "ci");
        o.add(BuildGradleProjectParamEnum.PROPERTIES.key(), props);
        JsonObject sysProps = new JsonObject();
        sysProps.addProperty("file.encoding", "UTF-8");
        o.add(BuildGradleProjectParamEnum.SYSTEM_PROPERTIES.key(), sysProps);
        o.addProperty(BuildGradleProjectParamEnum.PARALLEL.key(), true);
        o.addProperty(BuildGradleProjectParamEnum.CONTINUE_ON_FAILURE.key(), true);

        String result = new BuildGradleProjectTool().handle(new ToolRequestArguments(o), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("build -x test --offline --refresh-dependencies -Penv=ci -Dfile.encoding=UTF-8 --parallel --continue --no-daemon",
                     recordedArgs(gradleRoot), "every Gradle option must translate to its documented flag, in order");
    }

    @Test
    void gradleBuild_taskStartingWithDashIsRejected() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildGradleProjectParamEnum.PROJECT_PATH.key(), gradleRoot.toString());
        o.add(BuildGradleProjectParamEnum.TASKS.key(), stringArray("-x"));

        String result = new BuildGradleProjectTool().handle(new ToolRequestArguments(o), session);

        assertTrue(result.startsWith("Error:"), result);
        assertNoWrapperRan(gradleRoot);
    }

    @Test
    void antBuild_targetStartingWithDashIsRejected() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildAntProjectParamEnum.PROJECT_PATH.key(), antRoot.toString());
        o.add(BuildAntProjectParamEnum.TARGETS.key(), stringArray("-k"));

        String result = new BuildAntProjectTool().handle(new ToolRequestArguments(o), session);

        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void antBuild_keepGoingOptionIsAcceptedAndReachesTheLauncher() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty(BuildAntProjectParamEnum.PROJECT_PATH.key(), antRoot.toString());
        o.addProperty(BuildAntProjectParamEnum.KEEP_GOING.key(), true);

        String result = new BuildAntProjectTool().handle(new ToolRequestArguments(o), session);

        assertFalse(result.startsWith("Error:"), result);
    }

    // ---- #5 / F2: the two new clean-and-build tools ----
    @Test
    void cleanAndBuildGradle_runsGradlewWithCleanBuildGoals() throws Exception {
        String result = new CleanAndBuildGradleProjectTool().handle(
                args(CleanAndBuildGradleProjectParamEnum.PROJECT_PATH.key(), gradleRoot.toString()), session);

        assertTrue(result.startsWith("BUILD"), result);
        assertEquals("clean build -x test --no-daemon", recordedArgs(gradleRoot),
                     "CleanAndBuildGradleProject must differ from BuildGradleProject by the leading clean task");
    }

    @Test
    void cleanAndBuildAnt_withInScopeProjectPathIsNotRefused() throws Exception {
        String result = new CleanAndBuildAntProjectTool().handle(
                args(CleanAndBuildAntProjectParamEnum.PROJECT_PATH.key(), antRoot.toString()), session);

        assertNotEquals(NO_PROJECT, result,
                        "an in-scope projectPath must pass the gate and reach the ant launcher, whatever ant then reports");
    }

    @Test
    void newCleanAndBuildToolsAreRegisteredWithMcpToolEnum() {
        assertTrue(McpToolEnum.of(McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT.toolName()) != null);
        assertTrue(McpToolEnum.of(McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT.toolName()) != null);
    }

    private static JsonArray stringArray(String... values) {
        JsonArray array = new JsonArray();
        for (String v : values) {
            array.add(v);
        }
        return array;
    }

    // ---- helpers ----
    private static ToolRequestArguments args(String... nameValuePairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            object.addProperty(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return new ToolRequestArguments(object);
    }

    private static void fakeWrapper(Path dir, String name) throws Exception {
        Path script = dir.resolve(name);
        Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"$*\" > wrapper-args.txt\n");
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        catch (UnsupportedOperationException ignored) {
        }
    }

    private static String recordedArgs(Path dir) throws Exception {
        return Files.readString(dir.resolve("wrapper-args.txt")).strip();
    }

    private static void assertNoWrapperRan(Path dir) {
        assertTrue(!Files.exists(dir.resolve("wrapper-args.txt")),
                   "no wrapper must have executed when the request was refused");
    }

    private static AbstractAiSession newSession() {
        return new AbstractAiSession(AiSession.create(null, AiTypeEnum.CLAUDE)) {
            @Override
            public String getId() {
                return SESSION_ID;
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }

            @Override
            public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
                return Map.of();
            }
        };
    }

    private static final class NoopRegistrar extends AiMcpRegistrar {

        NoopRegistrar(String sessionId) {
            super(sessionId, AiTypeEnum.CLAUDE);
        }

        @Override
        public void addMcpEndpoint(String endpointUrl) {
        }

        @Override
        public void removeMcpEndpoint() {
        }

        @Override
        public boolean registerHooks(String serverBaseUrl) {
            return true;
        }

        @Override
        public void unregisterHooks() {
        }
    }
}
