package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileUtil;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

public class BuildAndTestMavenProvider {

    private static final Logger LOG = Logger.getLogger(BuildAndTestMavenProvider.class.getName());
    private static final int TIMEOUT_SECONDS = 180;
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    public static String buildProject(String projectPath) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return "No open project found";
        }
        return runMaven(root, "package", "-DskipTests");
    }

    public static String cleanAndBuildProject(String projectPath) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return "No open project found";
        }
        return runMaven(root, "clean", "package", "-DskipTests");
    }

    public static String downloadSources(String projectPath) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return "No open project found";
        }
        return runMaven(root, "dependency:sources");
    }

    public static String downloadJavadoc(String projectPath) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return "No open project found";
        }
        return runMaven(root, "dependency:resolve", "-Dclassifier=javadoc");
    }

    public static String runTests(String testClass, String projectPath) {
        File root = resolveRoot(projectPath);
        if (root == null) {
            return "No open project found";
        }
        if (testClass != null && !testClass.isBlank()) {
            return runMaven(root, "test", "-Dtest=" + testClass);
        }
        return runMaven(root, "test");
    }

    private static File resolveRoot(String projectPath) {
        if (projectPath != null && !projectPath.isBlank()) {
            File dir = new File(projectPath);
            return dir.isDirectory() ? dir : null;
        }
        return getOpenProjectRoot();
    }

    private static File getOpenProjectRoot() {
        OpenProjects op = OpenProjects.getDefault();
        Project main = op.getMainProject();
        if (main != null) {
            File dir = FileUtil.toFile(main.getProjectDirectory());
            if (dir != null && new File(dir, "pom.xml").exists()) {
                return dir;
            }
        }
        for (Project p : op.getOpenProjects()) {
            File dir = FileUtil.toFile(p.getProjectDirectory());
            if (dir != null && new File(dir, "pom.xml").exists()) {
                return dir;
            }
        }
        return null;
    }

    private static String runMaven(File dir, String... goals) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File wrapper = new File(dir, windows ? "mvnw.cmd" : "mvnw");
        List<String> cmd = new ArrayList<>();
        if (wrapper.exists()) {
            cmd.add(wrapper.getAbsolutePath());
        }
        else {
            cmd.add("mvn");
        }
        cmd.addAll(List.of(goals));
        cmd.add("--no-transfer-progress");
        Process p = null;
        Thread reader = null;
        AtomicReference<String> outputRef = new AtomicReference<>("");
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
                catch (Exception ignored) {
                }
            }, "mvn-output-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
            if (!finished) {
                return "Timed out after " + TIMEOUT_SECONDS + "s\n\n" + output;
            }
            int exit = p.exitValue();
            String header = exit == 0 ? "BUILD SUCCESS\n\n" : "BUILD FAILED (exit " + exit + ")\n\n";
            return header + output;
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
            LOG.log(Level.WARNING, "runMaven error", e);
            return "Error running Maven: " + e.getMessage();
        }
    }

    private BuildAndTestMavenProvider() {
    }
}
