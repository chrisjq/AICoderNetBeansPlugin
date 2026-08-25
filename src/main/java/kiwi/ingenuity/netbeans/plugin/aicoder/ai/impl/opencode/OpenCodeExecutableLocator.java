package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;

public final class OpenCodeExecutableLocator {

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
     * /usr/bin/opencode) are not directly executable, but the host filesystem is mounted at /run/host/, so
     * /run/host/usr/bin/opencode can be executed directly without flatpak-spawn (which requires org.freedesktop.Flatpak
     * D-Bus access that NetBeans lacks).
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
     * Locate the opencode binary. Returns the path on success, or null if not found. Order: stored preference → PATH →
     * well-known locations.
     */
    public static String locate() {
        String stored = OpenCodePluginSettings.getExecutable();
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
        String name = isWindows ? "opencode.exe" : "opencode";
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
        c.add(home + "/.local/bin/opencode");
        c.add(home + "/opencode");
        c.add(home + "/.opencode/bin/opencode");
        c.add("/usr/local/bin/opencode");
        c.add("/usr/bin/opencode");
        if (os.contains("mac")) {
            c.add("/opt/homebrew/bin/opencode");
            c.add("/usr/local/homebrew/bin/opencode");
        }
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                c.add(local + "\\Programs\\opencode\\opencode.exe");
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
     * Run `opencode --version` with the given executable. Returns the version string on success, or throws IOException
     * on failure or timeout (see OpenCodeTimeoutEnum.OPENCODE_EXECUTABLE_TEST_MILLIS).
     */
    public static String testExecutable(String path) throws IOException, InterruptedException {
        List<String> cmd = buildHostCommand(path, "--version");
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        // Drain stdout BEFORE waitFor so a process that fills the pipe buffer can't
        // deadlock; readNBytes returns at EOF (when the process exits and closes
        // stdout). --version output is tiny. try-with-resources closes the stream.
        String output;
        try (java.io.InputStream is = p.getInputStream()) {
            // Explicit UTF-8, not the platform default. JDK 17 decodes with the host default
            // charset (a legacy codepage on Windows); JDK 18+ uses UTF-8 by specification. This
            // plugin is compiled for 17 but NetBeans 22 runs on 17 through 21, so leaving it
            // implicit means the same probe output decodes differently depending on the JVM.
            output = new String(is.readNBytes(64 * 1024), StandardCharsets.UTF_8).strip();
        }
        boolean finished = p.waitFor(OpenCodeTimeoutEnum.OPENCODE_EXECUTABLE_TEST_MILLIS.millis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Timed out waiting for: " + path + " --version");
        }
        if (p.exitValue() != 0) {
            throw new IOException("Exit code " + p.exitValue() + ": " + output);
        }
        return output;
    }

    private OpenCodeExecutableLocator() {
    }
}
