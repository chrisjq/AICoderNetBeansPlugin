package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Locates the git executable the same way JGit does, so a partial-stage operation (the {@code git apply --cached} step
 * of partial staging) runs the EXACT git binary that NetBeans' {@code org.netbeans.libs.git} client uses — never a
 * different git on the machine.
 *
 * <p>
 * This mirrors {@code org.eclipse.jgit.util.FS_POSIX#discoverGitExe()} and {@code FS_Win32#discoverGitExe()} (and the
 * {@code FS#searchPath} / {@code FS#resolve} helpers they rely on) from JGit {@value #JGIT_VERSION}. JGit exposes none
 * of these publicly — all are {@code protected}, and {@code org.eclipse.jgit.util} is JGit's internal,
 * non-OSGi-exported package — so rather than reaching into JGit internals (or violating the public-NetBeans-API rule)
 * we re-implement the same discovery algorithm here. Discovery is a deterministic function of (platform, the
 * {@code PATH} environment variable, a couple of fixed locations and a login-shell probe), so replicating it returns
 * the same binary JGit finds.
 *
 * <p>
 * JGit 7.6 discovery, per platform:
 * <ul>
 * <li><b>POSIX (Linux &amp; macOS):</b> the first executable {@code git} on {@code $PATH}. On macOS only, if that is
 * the XCode shim ({@code /usr/bin/git}) or absent, a login shell ({@code bash --login -c "which git"}) is asked for the
 * terminal's fuller PATH; and if the result is still the XCode shim, {@code xcode-select -p} confirms a real
 * command-line-tools backend exists (otherwise null, to avoid the "install Xcode tools" prompt).</li>
 * <li><b>Windows:</b> the first executable {@code git.exe} then {@code git.cmd} on {@code $PATH} (a closer directory
 * wins over a nearer name); if neither is found but {@code bash.exe} is on the PATH, a login-shell {@code which git} is
 * tried and its possibly cygwin/msys-style output is resolved to a native path.</li>
 * </ul>
 *
 * <p>
 * The result of {@link #find()} is cached for the life of the JVM after the first lookup, matching JGit, which
 * discovers its binary once into a singleton {@code FS.DETECTED}.
 */
public final class GitExecutableLocator {

    /**
     * The exact JGit release whose discovery algorithm this class mirrors. Bump this if the mirrored algorithm below is
     * ever re-verified against a different JGit.
     */
    public static final String JGIT_VERSION = "7.6.0.202603022253-r";

    /**
     * macOS: the XCode command-line-tools shim location. JGit treats a git at this path as the "prompt to install the
     * CLT tools" wrapper and re-resolves it (see the macOS branch of {@link #discoverPosix}).
     */
    private static final String DEFAULT_GIT_LOCATION = "/usr/bin/git";

    /**
     * Matches a {@code PATH} value with no real entries (blank or path-separator-only).
     */
    private static final Pattern EMPTY_PATH
            = Pattern.compile("^[\\p{javaWhitespace}" + File.pathSeparator + "]*$");

    /**
     * Backstop for a probe that never exits; JGit simply blocks until the command ends.
     */
    private static final long PROBE_TIMEOUT_SECONDS = 10;

    /**
     * The cached discovery result. {@code null} means "not computed yet"; an {@link Optional#empty()} after the first
     * lookup means "git was not found" and is not retried (matching JGit's once-per-JVM discovery).
     */
    private static volatile Optional<File> cache;

    private final Function<String, String> getenv;
    private final boolean windows;
    private final boolean macOS;
    private final File userHome;
    private final CommandRunner commandRunner;
    private final Predicate<File> isExecutableFile;
    private final Predicate<File> exists;

    /**
     * Runs a command and returns its first output line (stdout and stderr merged). A non-zero exit, a timeout, or an
     * I/O failure throws {@link IOException}.
     */
    @FunctionalInterface
    public interface CommandRunner {

        String firstLine(File dir, String... command) throws IOException;
    }

    /**
     * Test seam. Every collaborator is injected so {@link #locate()} can be exercised without touching the real
     * environment, filesystem or any child process.
     *
     * @param getenv supplies environment variables (only {@code PATH} is read)
     * @param windows select the Windows discovery branch
     * @param macOS select the macOS-only part of the POSIX branch
     * @param userHome the directory probes are run from (JGit uses {@code userHome()})
     * @param commandRunner runs the login-shell / xcode-select probes
     * @param isExecutableFile the "is an executable file" test (JGit: {@code isFile() && canExecute()})
     * @param exists the "file exists" test (JGit: {@code exists()}) used for the macOS CLT-backend check
     */
    GitExecutableLocator(Function<String, String> getenv, boolean windows, boolean macOS,
                         File userHome, CommandRunner commandRunner, Predicate<File> isExecutableFile,
                         Predicate<File> exists) {
        this.getenv = getenv;
        this.windows = windows;
        this.macOS = macOS;
        this.userHome = userHome;
        this.commandRunner = commandRunner;
        this.isExecutableFile = isExecutableFile;
        this.exists = exists;
    }

    /**
     * The git executable the plugin should run — the same one JGit uses. Computes on the first call from the live
     * environment and caches the result for the JVM's life. Never returns null.
     */
    public static Optional<File> find() {
        Optional<File> c = cache;
        if (c != null) {
            return c;
        }
        synchronized (GitExecutableLocator.class) {
            c = cache;
            if (c == null) {
                c = real().locate();
                cache = c;
            }
        }
        return c;
    }

    /**
     * Clears the cached lookup. Test-only.
     */
    static void resetCacheForTest() {
        cache = null;
    }

    /**
     * The discovery algorithm itself, driven entirely by the injected collaborators.
     */
    Optional<File> locate() {
        String path = getenv.apply("PATH");
        File gitExe = windows ? discoverWindows(path) : discoverPosix(path);
        return Optional.ofNullable(gitExe);
    }

    /**
     * The POSIX branch (Linux and macOS). Mirrors {@code FS_POSIX#discoverGitExe()}.
     */
    private File discoverPosix(String path) {
        File gitExe = searchPath(path, "git");

        if (macOS) {
            // On macOS a GUI-launched JVM has a shorter PATH than a terminal, so when we only
            // found (or didn't find) the XCode shim, ask a login shell for the real location.
            if (gitExe == null || DEFAULT_GIT_LOCATION.equals(gitExe.getPath())) {
                if (searchPath(path, "bash") != null) {
                    try {
                        String w = commandRunner.firstLine(userHome, "bash", "--login", "-c", "which git");
                        if (!isEmptyOrNull(w)) {
                            gitExe = new File(w);
                        }
                    }
                    catch (IOException e) {
                        // JGit: warn and keep whatever the PATH search already found.
                    }
                }
            }
            // Still on the XCode shim? Confirm a real CLT backend exists, else drop it.
            if (gitExe != null && DEFAULT_GIT_LOCATION.equals(gitExe.getPath())) {
                try {
                    String w = commandRunner.firstLine(userHome, "xcode-select", "-p");
                    if (isEmptyOrNull(w)) {
                        gitExe = null;
                    }
                    else {
                        File realGitExe = new File(new File(w), DEFAULT_GIT_LOCATION.substring(1));
                        if (!isRealBackend(realGitExe)) {
                            gitExe = null;
                        }
                    }
                }
                catch (IOException e) {
                    gitExe = null;
                }
            }
        }

        return gitExe;
    }

    /**
     * The Windows branch. Mirrors {@code FS_Win32#discoverGitExe()}.
     */
    private File discoverWindows(String path) {
        File gitExe = searchPath(path, "git.exe", "git.cmd");

        if (gitExe == null && searchPath(path, "bash.exe") != null) {
            String w;
            try {
                w = commandRunner.firstLine(userHome, "bash", "--login", "-c", "which git");
            }
            catch (IOException e) {
                return null;
            }
            if (!isEmptyOrNull(w)) {
                // The path may be in cygwin/msys notation, so resolve it to a native path.
                gitExe = resolve(null, w);
            }
        }

        return gitExe;
    }

    /**
     * Walks {@code path} in JGit's order — directories outer, candidate names inner — returning the first entry that is
     * an executable file as an absolute {@link File}. Returns null when {@code path} is blank/separator-only or nothing
     * matches. Mirrors {@code FS#searchPath}.
     */
    private File searchPath(String path, String... lookFor) {
        if (isEmptyOrNull(path) || EMPTY_PATH.matcher(path).find()) {
            return null;
        }
        for (String p : path.split(File.pathSeparator)) {
            for (String command : lookFor) {
                File file = new File(p, command);
                if (isExecutableFile.test(file)) {
                    return file.getAbsoluteFile();
                }
            }
        }
        return null;
    }

    /**
     * Whether a real CLT backend exists at {@code realGitExe} — JGit checks {@code exists()} here (not "is
     * executable"); the check is routed through the injected {@code exists} test so the macOS XCode-shim branch is
     * unit-testable.
     */
    private boolean isRealBackend(File realGitExe) {
        return exists.test(realGitExe);
    }

    /**
     * Translates {@code name} to a file: the path itself when absolute, else relative to {@code dir}. Mirrors
     * {@code FS#resolve}. A null {@code dir} with a relative {@code name} stays relative, exactly as JGit's
     * {@code new File(null, name)} does.
     */
    private File resolve(File dir, String name) {
        File abspn = new File(name);
        if (abspn.isAbsolute()) {
            return abspn;
        }
        return new File(dir, name);
    }

    private static boolean isEmptyOrNull(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * The live-environment collaborators: the real environment, the running OS, the user home, a real child-process
     * probe, and the real "is an executable file" test.
     */
    private static GitExecutableLocator real() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        File userHome = new File(System.getProperty("user.home", "."));
        return new GitExecutableLocator(
                System::getenv,
                os.startsWith("windows"),
                os.startsWith("mac"),
                userHome,
                GitExecutableLocator::runFirstLine,
                f -> f.isFile() && f.canExecute(),
                File::exists);
    }

    /**
     * The production {@link CommandRunner}: runs {@code command} in {@code dir} (stderr merged into stdout) and returns
     * its first output line. The output is drained on a separate daemon thread while the main thread time-boxes the
     * call with {@code waitFor}, so a process that hangs with its stdout still open is destroyed on the timeout rather
     * than blocking the read forever; the thread is joined before the result is returned so the captured line is fully
     * written. Fails on a non-zero exit, a timeout, or an I/O error. Decoded with the platform default charset,
     * matching JGit's {@code SystemReader.getDefaultCharset()}.
     */
    private static String runFirstLine(File dir, String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (dir != null) {
            pb.directory(dir);
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();

        AtomicReference<String> firstLine = new AtomicReference<>();
        Thread drain = new Thread(() -> {
            try (BufferedReader r
                    = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    firstLine.compareAndSet(null, line);
                }
            }
            catch (IOException e) {
                // The stream is closed when the process dies; there is nothing left to drain.
            }
        }, "git-locator-probe-drain");
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted waiting for: " + String.join(" ", command), e);
        }
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Timed out after " + PROBE_TIMEOUT_SECONDS
                    + "s running: " + String.join(" ", command));
        }
        // The process has exited, so its stdout is at EOF and the drain thread will finish promptly. Join it so
        // the captured line is visible here (a plain read of the reference could otherwise race the writer).
        try {
            drain.join(PROBE_TIMEOUT_SECONDS * 1000);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (p.exitValue() != 0) {
            throw new IOException("Exit code " + p.exitValue() + " running: "
                    + String.join(" ", command));
        }
        return firstLine.get();
    }
}
