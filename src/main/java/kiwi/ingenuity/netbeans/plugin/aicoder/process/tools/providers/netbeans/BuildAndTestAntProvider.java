package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileUtil;
import org.openide.modules.InstalledFileLocator;

public class BuildAndTestAntProvider {

    private static final Logger LOG = Logger.getLogger(BuildAndTestAntProvider.class.getName());
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    public static String buildProject(String sessionId, String projectPath) {
        File root = resolveRoot(sessionId, projectPath);
        if (root == null) {
            return "No open project found";
        }
        return runAnt(sessionId, root, "jar");
    }

    public static String runTests(String sessionId, String testClass, String projectPath) {
        File root = resolveRoot(sessionId, projectPath);
        if (root == null) {
            return "No open project found";
        }
        if (testClass != null && !testClass.isBlank()) {
            return runAnt(sessionId, root, "test", "-Dtest.includes=" + testClass);
        }
        return runAnt(sessionId, root, "test");
    }

    private static File resolveRoot(String sessionId, String projectPath) {
        if (projectPath != null && !projectPath.isBlank()) {
            File dir = new File(projectPath);
            if (!dir.isDirectory()) {
                return null;
            }
            var server = McpServerRegistry.getServer();
            return server != null && server.isFileAllowed(sessionId, dir.getAbsolutePath()) ? dir : null;
        }
        File dir = getOpenProjectRoot();
        var server = McpServerRegistry.getServer();
        return dir != null && server != null && server.isFileAllowed(sessionId, dir.getAbsolutePath()) ? dir : null;
    }

    private static File getOpenProjectRoot() {
        OpenProjects op = OpenProjects.getDefault();
        Project main = op.getMainProject();
        if (main != null) {
            File dir = FileUtil.toFile(main.getProjectDirectory());
            if (dir != null && new File(dir, "build.xml").exists()) {
                return dir;
            }
        }
        for (Project p : op.getOpenProjects()) {
            File dir = FileUtil.toFile(p.getProjectDirectory());
            if (dir != null && new File(dir, "build.xml").exists()) {
                return dir;
            }
        }
        return null;
    }

    private static String resolveAnt() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String antBin = "ant" + File.separator + "bin" + File.separator + (windows ? "ant.bat" : "ant");
        File bundled = InstalledFileLocator.getDefault()
                .locate(antBin, "org.apache.tools.ant", false);
        if (bundled != null && bundled.exists()) {
            return bundled.getAbsolutePath();
        }
        return windows ? "ant.bat" : "ant";
    }

    private static String runAnt(String sessionId, File dir, String... targets) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveAnt());
        cmd.addAll(List.of(targets));
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
            }, "ant-output-reader");
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
                return BuildOutputFormatter.attachLog(sessionId, BuildOutputFormatter.Backend.ANT,
                        "Error reading Ant output: " + outputError.getMessage(), output);
            }
            if (!finished) {
                return BuildOutputFormatter.attachLog(sessionId, BuildOutputFormatter.Backend.ANT,
                        "Timed out after " + TimeUnit.MILLISECONDS.toSeconds(TimeoutEnum.BUILD_PROCESS_MILLIS.millis()) + "s",
                        output);
            }
            int exit = p.exitValue();
            return BuildOutputFormatter.formatResult(sessionId, BuildOutputFormatter.Backend.ANT,
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
            LOG.log(Level.WARNING, "runAnt error", e);
            return "Error running Ant: " + e.getMessage();
        }
    }

    private BuildAndTestAntProvider() {
    }
}
