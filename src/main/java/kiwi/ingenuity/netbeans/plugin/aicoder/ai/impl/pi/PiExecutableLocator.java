package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;

/**
 * Finds and tests the {@code pi} executable. Mirrors {@code ClaudeExecutableLocator}'s trusted-dirs/PATH order and
 * Flatpak host-path handling. {@link #testExecutable(String)}'s stdout draining differs: it runs on a bounded helper
 * thread (see its own doc comment) rather than the calling thread, so a process that keeps stdout open without exiting
 * cannot block past the configured deadline.
 */
public final class PiExecutableLocator {

    /**
     * Test seam: when non-null, replaces {@link PiTimeoutEnum#PI_EXECUTABLE_TEST_MILLIS} as
     * {@link #testExecutable(String)}'s whole-attempt budget so a hung fake CLI fails a test in milliseconds instead
     * of seconds. Null in production.
     */
    static volatile Long testExecutableBudgetMillisForTests = null;

    private static final List<String> TRUSTED_DIRS = List.of(
            "/usr/bin", "/usr/local/bin",
            System.getProperty("user.home") + "/.local/bin",
            System.getProperty("user.home") + "/bin",
            System.getProperty("user.home") + "/.npm-global/bin",
            System.getProperty("user.home") + "/Library/pnpm",
            "/opt/homebrew/bin", "/usr/local/homebrew/bin"
    );

    /**
     * True when running inside a Flatpak sandbox (e.g. NetBeans from Flathub). Inside Flatpak, host binaries are not at
     * their normal paths but are accessible via the /run/host/ mount point.
     */
    public static boolean isRunningInFlatpak() {
        return System.getenv("FLATPAK_ID") != null;
    }

    /**
     * Resolve the actual path to use when executing a binary. In a Flatpak sandbox, absolute host paths (e.g.
     * /usr/bin/pi) are not directly executable, but the host filesystem is mounted at /run/host/, so
     * /run/host/usr/bin/pi can be executed directly without flatpak-spawn (which requires org.freedesktop.Flatpak D-Bus
     * access that NetBeans lacks).
     */
    public static String resolveExecutable(String path) {
        if (isRunningInFlatpak() && path != null && path.startsWith("/")) {
            File hostPath = new File("/run/host" + path);
            if (hostPath.exists()) {
                return hostPath.getAbsolutePath();
            }
        }
        return path;
    }

    /**
     * Build a command list for executing a binary, resolving the path for the current environment (Flatpak or normal).
     */
    public static List<String> buildHostCommand(String executable, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveExecutable(executable));
        cmd.addAll(Arrays.asList(args));
        return cmd;
    }

    /**
     * Locate the pi binary. Returns the path on success, or null if not found. Order: stored preference → PATH →
     * well-known locations.
     */
    public static String locate() {
        String stored = PiPluginSettings.getExecutable();
        if (!stored.isBlank() && isExecutableFile(stored)) {
            return stored;
        }

        String fromPath = findOnPath();
        if (fromPath != null) {
            return fromPath;
        }

        return findInCandidates(platformCandidates());
    }

    private static String findOnPath() {
        String pathVar = System.getenv("PATH");
        if (pathVar == null) {
            return null;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = os.contains("win");
        String name = isWindows ? "pi.exe" : "pi";
        for (String dir : pathVar.split(File.pathSeparator)) {
            // On Windows, trust all PATH entries — common install locations
            // (%APPDATA%\npm, winget, scoop, etc.) are not in TRUSTED_DIRS.
            if (dir.isBlank() || (!isWindows && !TRUSTED_DIRS.contains(dir))) {
                continue;
            }
            String candidate = dir + File.separator + name;
            if (isExecutableFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static String[] platformCandidates() {
        String home = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> c = new ArrayList<>();
        c.add(home + "/.local/bin/pi");
        c.add(home + "/pi");
        c.add("/usr/local/bin/pi");
        c.add("/usr/bin/pi");
        if (os.contains("mac")) {
            c.add("/opt/homebrew/bin/pi");
            c.add("/usr/local/homebrew/bin/pi");
        }
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                c.add(local + "\\Programs\\pi\\pi.exe");
            }
        }
        return c.toArray(String[]::new);
    }

    public static String findInCandidates(String[] candidates) {
        for (String c : candidates) {
            if (isExecutableFile(c)) {
                return c;
            }
        }
        return null;
    }

    public static boolean isExecutableFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        // Inside a Flatpak sandbox, host paths like /usr/bin/ are not directly
        // visible. The host filesystem is exposed under /run/host/, so check there.
        if (isRunningInFlatpak() && path.startsWith("/")) {
            File hostFile = new File("/run/host" + path);
            if (hostFile.exists()) {
                return true;
            }
        }
        File f = new File(path);
        return f.isFile() && f.canExecute();
    }

    /**
     * Run {@code pi --version} with the given executable. Returns the version string on success (e.g. {@code "0.85.1"}
     * — verified as the bare version with no surrounding text, unlike Claude's banner-style output), or throws
     * IOException on failure or timeout.
     */
    public static String testExecutable(String path) throws IOException, InterruptedException {
        List<String> cmd = buildHostCommand(path, "--version");
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        // readNBytes only returns at EOF, so draining on THIS thread would block forever on a process that keeps
        // stdout open without exiting — the waitFor deadline below would never even be reached (this previously
        // let a hung `pi` wedge both the Options probe and the session-start version check indefinitely). Drain on
        // a helper daemon thread and bound the WHOLE attempt (drain + process exit) against one deadline,
        // mirroring PiModelDiscovery.discover()'s watchdog shape.
        byte[][] outputHolder = new byte[1][];
        Thread drainer = new Thread(() -> {
            try (java.io.InputStream is = p.getInputStream()) {
                // Explicit UTF-8, not the platform default — see ClaudeExecutableLocator's identical comment.
                outputHolder[0] = is.readNBytes(64 * 1024);
            }
            catch (IOException e) {
                // Left null; treated as empty output below.
            }
        }, "pi-executable-test-drain");
        drainer.setDaemon(true);
        drainer.start();

        long budgetMs = testExecutableBudgetMillisForTests != null
                        ? testExecutableBudgetMillisForTests
                        : PiTimeoutEnum.PI_EXECUTABLE_TEST_MILLIS.millis();
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        try {
            drainer.join(budgetMs);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw e;
        }
        if (drainer.isAlive()) {
            // Stdout never reached EOF within the budget: hung CLI. Kill it so the drain thread's stream closes.
            p.destroyForcibly();
            throw new IOException("Timed out waiting for: " + path + " --version");
        }
        long remainingMs = Math.max(0L, (deadline - System.nanoTime()) / 1_000_000L);
        boolean finished = p.waitFor(remainingMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Timed out waiting for: " + path + " --version");
        }
        String output = outputHolder[0] != null ? new String(outputHolder[0], StandardCharsets.UTF_8).strip() : "";
        if (p.exitValue() != 0) {
            throw new IOException("Exit code " + p.exitValue() + ": " + output);
        }
        return output;
    }

    private PiExecutableLocator() {
    }
}
