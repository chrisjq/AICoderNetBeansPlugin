package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the git executable for the partial-staging path ({@code git apply --cached}). Unlike {@code GitProvider} (which
 * uses NetBeans' {@code org.netbeans.libs.git} and can only stage whole files) this invokes the real git binary — the
 * one {@link GitExecutableLocator} found, i.e. the same JGit uses — so a hand-built patch of selected hunks can be
 * applied to the index.
 *
 * <p>
 * The output is drained on two daemon threads (one for stdout, one for stderr) while the main thread time-boxes the
 * call with {@code waitFor}, so a git that hangs can never fill a pipe buffer and deadlock; on the timeout the process
 * is destroyed and an {@link IOException} is thrown.
 */
final class GitCommandRunner {

    /**
     * The result of a git run: its exit code and the captured stdout and stderr (both fully drained).
     */
    record Result(int exitCode, String stdout, String stderr) {

    }

    private GitCommandRunner() {
    }

    /**
     * Runs {@code gitExe} with {@code args} in {@code workDir}, writing {@code stdin} (UTF-8, or nothing when null)
     * then closing it, and waits at most {@code timeoutSeconds} for it to finish.
     *
     * @param gitExe the git executable to run (from {@link GitExecutableLocator})
     * @param workDir the working directory (the git root)
     * @param stdin bytes to write to the process's standard input (e.g. a patch); null writes nothing
     * @param timeoutSeconds the maximum time to wait for the process to finish
     * @param args the git arguments (e.g. {@code apply --cached --whitespace=nowarn -})
     *
     * @return the exit code and captured stdout/stderr
     *
     * @throws IOException if the process cannot be started, a timeout occurs, or draining fails
     */
    static Result run(File gitExe, File workDir, String stdin, long timeoutSeconds, String... args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(gitExe.getAbsolutePath());
        for (String arg : args) {
            command.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir);
        }
        Process p = pb.start();

        // Drain stdout and stderr on daemon threads BEFORE writing stdin, so a large patch and a large output
        // can never deadlock: a pipe buffer filling while the other end is busy writing would otherwise stall.
        AtomicReference<byte[]> stdoutRef = new AtomicReference<>();
        AtomicReference<byte[]> stderrRef = new AtomicReference<>();
        Thread drainOut = startDrain(p.getInputStream(), stdoutRef, "git-runner-stdout");
        Thread drainErr = startDrain(p.getErrorStream(), stderrRef, "git-runner-stderr");

        // Then write the stdin and close it so the child sees EOF and reads it all. ISO_8859_1 is byte-
        // transparent, so the patch bytes round-trip unchanged for a file in any encoding.
        if (stdin != null) {
            try (OutputStream os = p.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        else {
            p.getOutputStream().close();
        }

        boolean finished;
        try {
            finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted waiting for: git " + String.join(" ", args), e);
        }
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Timed out after " + timeoutSeconds + "s running: git "
                    + String.join(" ", args));
        }
        // The process has exited, so both streams are at EOF and the drains finish promptly; join them so the
        // captured bytes are fully written before they are read.
        joinQuietly(drainOut, timeoutSeconds);
        joinQuietly(drainErr, timeoutSeconds);

        String stdout = stdoutRef.get() == null ? "" : new String(stdoutRef.get(), StandardCharsets.UTF_8);
        String stderr = stderrRef.get() == null ? "" : new String(stderrRef.get(), StandardCharsets.UTF_8);
        return new Result(p.exitValue(), stdout, stderr);
    }

    /**
     * Spawns a daemon thread that drains {@code in} into {@code sink} and returns it. Errors are swallowed: the stream
     * is closed when the process exits and there is nothing left to drain.
     */
    private static Thread startDrain(InputStream in, AtomicReference<byte[]> sink, String name) {
        Thread t = new Thread(() -> {
            try {
                byte[] bytes = in.readAllBytes();
                sink.set(bytes);
            }
            catch (IOException e) {
                // nothing left to drain once the process dies
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t, long timeoutSeconds) {
        try {
            t.join(timeoutSeconds * 1000);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
