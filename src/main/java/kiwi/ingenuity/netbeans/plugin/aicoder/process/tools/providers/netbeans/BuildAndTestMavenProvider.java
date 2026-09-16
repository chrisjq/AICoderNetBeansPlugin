package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildControl;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;

public class BuildAndTestMavenProvider {

    private static final Logger LOG = Logger.getLogger(BuildAndTestMavenProvider.class.getName());
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    /**
     * Options shared by BuildMavenProject, CleanAndBuildMavenProject and RunMavenTests (#5 / F2). Every field arrives
     * already resolved by the calling tool — {@code goals} carries that tool's own default ({@code package},
     * {@code clean package}, or {@code test}) when the caller omitted it, and {@code skipTests} carries that tool's own
     * default (true for the two build tools, preserving today's {@code -DskipTests}; false for RunMavenTests, which has
     * never passed it — skipping tests on the tool whose entire purpose is running them would be a confusing default).
     * This record makes no decisions of its own; {@link #argsFor} only translates already- resolved values into CLI
     * flags, and {@link #validate} checks them.
     */
    public record MavenBuildOptions(
            List<String> goals, List<String> projectList, boolean alsoMake, String resumeFrom,
            boolean skipTests, boolean offline, boolean updateSnapshots, List<String> profiles,
            JsonObject properties, String threads, boolean failAtEnd) {

    }

    public static String buildProject(String sessionId, String projectPath, MavenBuildOptions opts) {
        return BuildProcessRunner.run(prepareBuildProject(sessionId, projectPath, opts),
                                      new BuildControl(TimeoutEnum.BUILD_PROCESS_MILLIS.millis())).result();
    }

    public static String cleanAndBuildProject(String sessionId, String projectPath, MavenBuildOptions opts) {
        return BuildProcessRunner.run(prepareCleanAndBuildProject(sessionId, projectPath, opts),
                                      new kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildControl(TimeoutEnum.BUILD_PROCESS_MILLIS.millis())).result();
    }

    public static PreparedBuild prepareBuildProject(String sessionId, String projectPath, MavenBuildOptions opts) {
        return prepareBuild(sessionId, projectPath, opts, null);
    }

    public static PreparedBuild prepareCleanAndBuildProject(String sessionId, String projectPath, MavenBuildOptions opts) {
        return prepareBuild(sessionId, projectPath, opts, null);
    }

    public static PreparedBuild prepareDownloadSources(String sessionId, String projectPath) {
        RootResult resolved = resolveRoot(sessionId, projectPath);
        return resolved.error() != null ? PreparedBuild.error(resolved.error())
               : prepareDownload(sessionId, resolved.root(), List.of("dependency:sources"));
    }

    public static PreparedBuild prepareDownloadJavadoc(String sessionId, String projectPath) {
        RootResult resolved = resolveRoot(sessionId, projectPath);
        return resolved.error() != null ? PreparedBuild.error(resolved.error())
               : prepareDownload(sessionId, resolved.root(), List.of("dependency:resolve", "-Dclassifier=javadoc"));
    }

    public static String runTests(String sessionId, String testClass, String projectPath, MavenBuildOptions opts) {
        return BuildProcessRunner.run(prepareRunTests(sessionId, testClass, projectPath, opts),
                                      new kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildControl(TimeoutEnum.BUILD_PROCESS_MILLIS.millis())).result();
    }

    public static PreparedBuild prepareRunTests(String sessionId, String testClass, String projectPath, MavenBuildOptions opts) {
        return prepareBuild(sessionId, projectPath, opts, testClass);
    }

    private static PreparedBuild prepareBuild(String sessionId, String projectPath, MavenBuildOptions opts, String testClass) {
        String error = validate(opts);
        if (error != null) {
            return PreparedBuild.error("Error: " + error);
        }
        if (testClass != null && !testClass.isBlank()) {
            error = BuildOptionValidator.validateTestSelector(McpToolPropertyEnum.TEST_CLASS.key(), testClass);
            if (error != null) {
                return PreparedBuild.error("Error: " + error);
            }
        }
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return PreparedBuild.error(resolved.error());
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File wrapper = new File(resolved.root(), windows ? "mvnw.cmd" : "mvnw");
        List<String> command = new ArrayList<>();
        command.add(wrapper.exists() ? wrapper.getAbsolutePath() : "mvn");
        command.addAll(List.of(argsFor(opts, testClass)));
        command.add("--no-transfer-progress");
        return new PreparedBuild(null, sessionId, resolved.root(), command, BuildOutputFormatter.Backend.MAVEN);
    }

    /**
     * Validates every option BEFORE any file resolution or process launch — same principle as #17's targetProjectPath
     * fix: a malformed argument must not be masked by a later, unrelated failure.
     */
    private static String validate(MavenBuildOptions opts) {
        if (opts.goals() == null || opts.goals().isEmpty()) {
            return McpToolPropertyEnum.GOALS.key() + " must not be empty";
        }
        String error = BuildOptionValidator.validateTokens(McpToolPropertyEnum.GOALS.key(), opts.goals());
        if (error != null) {
            return error;
        }
        error = BuildOptionValidator.validateTokens(McpToolPropertyEnum.PROJECT_LIST.key(), opts.projectList());
        if (error != null) {
            return error;
        }
        error = BuildOptionValidator.validateTokens(McpToolPropertyEnum.PROFILES.key(), opts.profiles());
        if (error != null) {
            return error;
        }
        if (opts.resumeFrom() != null && !opts.resumeFrom().isBlank()) {
            error = BuildOptionValidator.validateToken(McpToolPropertyEnum.RESUME_FROM.key(), opts.resumeFrom());
            if (error != null) {
                return error;
            }
        }
        if (opts.threads() != null) {
            error = BuildOptionValidator.validateToken(McpToolPropertyEnum.THREADS.key(), opts.threads());
            if (error != null) {
                return error;
            }
            error = BuildOptionValidator.validateMavenThreads(opts.threads());
            if (error != null) {
                return error;
            }
        }
        String[] errorOut = new String[1];
        BuildOptionValidator.validateProperties(McpToolPropertyEnum.PROPERTIES.key(), opts.properties(), errorOut);
        return errorOut[0];
    }

    private static String[] argsFor(MavenBuildOptions opts, String testClass) {
        List<String> args = new ArrayList<>(opts.goals());
        if (opts.projectList() != null && !opts.projectList().isEmpty()) {
            args.add("-pl");
            args.add(String.join(",", opts.projectList()));
        }
        if (opts.alsoMake()) {
            args.add("-am");
        }
        if (opts.resumeFrom() != null && !opts.resumeFrom().isBlank()) {
            args.add("-rf");
            args.add(opts.resumeFrom());
        }
        if (opts.skipTests()) {
            args.add("-DskipTests");
        }
        if (opts.offline()) {
            args.add("-o");
        }
        if (opts.updateSnapshots()) {
            args.add("-U");
        }
        if (opts.profiles() != null && !opts.profiles().isEmpty()) {
            args.add("-P");
            args.add(String.join(",", opts.profiles()));
        }
        String[] errorOut = new String[1];
        Map<String, String> props = BuildOptionValidator.validateProperties(
                McpToolPropertyEnum.PROPERTIES.key(), opts.properties(), errorOut);
        args.addAll(BuildOptionValidator.toDefineArgs(props));
        if (opts.threads() != null && !opts.threads().isBlank()) {
            args.add("-T");
            args.add(opts.threads());
        }
        if (opts.failAtEnd()) {
            args.add("-fae");
        }
        if (testClass != null && !testClass.isBlank()) {
            args.add("-Dtest=" + testClass);
        }
        return args.toArray(new String[0]);
    }

    private static PreparedBuild prepareDownload(String sessionId, File root, List<String> goals) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File wrapper = new File(root, windows ? "mvnw.cmd" : "mvnw");
        List<String> command = new ArrayList<>();
        command.add(wrapper.exists() ? wrapper.getAbsolutePath() : "mvn");
        command.addAll(goals);
        command.add("--no-transfer-progress");
        // A download is not a build: it must not raise the project's inline time limit (item 3, 2026-09-17).
        return new PreparedBuild(null, sessionId, root, command, BuildOutputFormatter.Backend.MAVEN, false);
    }

    private static RootResult resolveRoot(String sessionId, String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return new RootResult(null, GitCommonParamEnum.PROJECT_PATH.key() + " is required");
        }
        File dir = new File(projectPath);
        if (!dir.isDirectory()) {
            return new RootResult(null, "Not a project directory: " + projectPath);
        }
        // #3's fix, applied here: resolve to the real/canonical path before the scope check and before it becomes
        // the process's working directory, so a symlink spelling of an open project (e.g. /share/code/... aliasing
        // /Users/chris/.SyncShare/...) is treated identically to the canonical one throughout.
        File real = FileUtils.toRealPath(dir);
        var server = McpServerRegistry.getServer();
        if (server == null || !server.isFileAllowed(sessionId, real.getAbsolutePath())) {
            return new RootResult(null, "Access denied: " + projectPath);
        }
        return new RootResult(real, null);
    }

    private BuildAndTestMavenProvider() {
    }

    private record RootResult(File root, String error) {}
}
