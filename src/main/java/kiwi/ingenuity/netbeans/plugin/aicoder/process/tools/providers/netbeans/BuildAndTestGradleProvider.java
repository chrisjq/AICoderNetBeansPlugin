package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;

public class BuildAndTestGradleProvider {

    private static final Logger LOG = Logger.getLogger(BuildAndTestGradleProvider.class.getName());
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    /**
     * Options shared by BuildGradleProject, CleanAndBuildGradleProject and RunGradleTests (#5 / F2). {@code tasks}
     * carries the calling tool's own default ({@code build -x test}, {@code clean build -x test}, or {@code test}) when
     * the caller omitted it — see {@link BuildAndTestMavenProvider.MavenBuildOptions} for why defaulting lives in the
     * tool, not here.
     */
    public record GradleBuildOptions(
            List<String> tasks, boolean skipTests, boolean offline, boolean refreshDependencies,
            JsonObject properties, JsonObject systemProperties, boolean parallel, boolean continueOnFailure) {

    }

    public static String buildProject(String sessionId, String projectPath, GradleBuildOptions opts) {
        return runBuild(sessionId, projectPath, opts, null);
    }

    public static String cleanAndBuildProject(String sessionId, String projectPath, GradleBuildOptions opts) {
        return runBuild(sessionId, projectPath, opts, null);
    }

    public static String runTests(String sessionId, String testClass, String projectPath, GradleBuildOptions opts) {
        return runBuild(sessionId, projectPath, opts, testClass);
    }

    private static String runBuild(String sessionId, String projectPath, GradleBuildOptions opts, String testClass) {
        String error = validate(opts);
        if (error != null) {
            return "Error: " + error;
        }
        if (testClass != null && !testClass.isBlank()) {
            error = BuildOptionValidator.validateTestSelector(McpToolPropertyEnum.TEST_CLASS.key(), testClass);
            if (error != null) {
                return "Error: " + error;
            }
        }
        RootResult resolved = resolveRoot(sessionId, projectPath);
        if (resolved.error() != null) {
            return resolved.error();
        }
        return runGradle(sessionId, resolved.root(), argsFor(opts, testClass));
    }

    private static String validate(GradleBuildOptions opts) {
        if (opts.tasks() == null || opts.tasks().isEmpty()) {
            return McpToolPropertyEnum.TASKS.key() + " must not be empty";
        }
        String error = BuildOptionValidator.validateTokens(McpToolPropertyEnum.TASKS.key(), opts.tasks());
        if (error != null) {
            return error;
        }
        String[] errorOut = new String[1];
        BuildOptionValidator.validateProperties(McpToolPropertyEnum.PROPERTIES.key(), opts.properties(), errorOut);
        if (errorOut[0] != null) {
            return errorOut[0];
        }
        BuildOptionValidator.validateProperties(McpToolPropertyEnum.SYSTEM_PROPERTIES.key(), opts.systemProperties(), errorOut);
        return errorOut[0];
    }

    private static String[] argsFor(GradleBuildOptions opts, String testClass) {
        List<String> args = new ArrayList<>(opts.tasks());
        if (opts.skipTests()) {
            args.add("-x");
            args.add("test");
        }
        if (opts.offline()) {
            args.add("--offline");
        }
        if (opts.refreshDependencies()) {
            args.add("--refresh-dependencies");
        }
        String[] errorOut = new String[1];
        Map<String, String> props = BuildOptionValidator.validateProperties(
                McpToolPropertyEnum.PROPERTIES.key(), opts.properties(), errorOut);
        args.addAll(BuildOptionValidator.toDashPArgs(props));
        Map<String, String> sysProps = BuildOptionValidator.validateProperties(
                McpToolPropertyEnum.SYSTEM_PROPERTIES.key(), opts.systemProperties(), errorOut);
        args.addAll(BuildOptionValidator.toDefineArgs(sysProps));
        if (opts.parallel()) {
            args.add("--parallel");
        }
        if (opts.continueOnFailure()) {
            args.add("--continue");
        }
        if (testClass != null && !testClass.isBlank()) {
            args.add("--tests");
            args.add(testClass);
        }
        return args.toArray(new String[0]);
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
        // the process's working directory, so a symlink spelling of an open project is treated identically to the
        // canonical one throughout.
        File real = FileUtils.toRealPath(dir);
        var server = McpServerRegistry.getServer();
        if (server == null || !server.isFileAllowed(sessionId, real.getAbsolutePath())) {
            return new RootResult(null, "Access denied: " + projectPath);
        }
        return new RootResult(real, null);
    }

    private static String runGradle(String sessionId, File dir, String... tasks) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File wrapper = new File(dir, windows ? "gradlew.bat" : "gradlew");
        List<String> cmd = new ArrayList<>();
        if (wrapper.exists()) {
            cmd.add(wrapper.getAbsolutePath());
        }
        else {
            cmd.add("gradle");
        }
        cmd.addAll(List.of(tasks));
        cmd.add("--no-daemon");
        Process p = null;
        Thread reader = null;
        AtomicReference<String> outputRef = new AtomicReference<>("");
        AtomicReference<Exception> readerFailure = new AtomicReference<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process proc = p;
            reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int n;
                    InputStream is = proc.getInputStream();
                    while ((n = is.read(buf)) != -1) {
                        if (baos.size() < MAX_OUTPUT_BYTES) {
                            baos.write(buf, 0, Math.min(n, MAX_OUTPUT_BYTES - baos.size()));
                        }
                    }
                    outputRef.set(baos.toString(StandardCharsets.UTF_8));
                }
                catch (Exception e) {
                    readerFailure.set(e);
                }
            }, "gradle-output-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(TimeoutEnum.BUILD_PROCESS_MILLIS.millis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
            }
            try {
                reader.join(5_000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String output = outputRef.get();
            Exception outputError = readerFailure.get();
            if (outputError != null) {
                return BuildOutputFormatter.attachLog(sessionId, BuildOutputFormatter.Backend.GRADLE,
                                                      "Error reading Gradle output: " + outputError.getMessage(), output);
            }
            if (!finished) {
                return BuildOutputFormatter.attachLog(sessionId, BuildOutputFormatter.Backend.GRADLE,
                                                      "Timed out after " + TimeUnit.MILLISECONDS.toSeconds(TimeoutEnum.BUILD_PROCESS_MILLIS.millis()) + "s",
                                                      output);
            }
            int exit = p.exitValue();
            return BuildOutputFormatter.formatResult(sessionId, BuildOutputFormatter.Backend.GRADLE,
                                                     exit == 0, exit, output);
        }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            if (reader != null) {
                try {
                    reader.join(2_000);
                }
                catch (InterruptedException ignored) {
                }
            }
            return "Interrupted waiting for build";
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "runGradle error", e);
            return "Error running Gradle: " + e.getMessage();
        }
    }

    private BuildAndTestGradleProvider() {
    }

    private record RootResult(File root, String error) {}
}
