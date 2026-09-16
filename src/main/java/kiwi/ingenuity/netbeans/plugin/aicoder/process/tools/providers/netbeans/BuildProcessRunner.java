package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildControl;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildOutcome;

/**
 * Runs a prepared build with one bounded daemon output reader and cancellable timeout handling.
 */
public final class BuildProcessRunner {

    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    public static BuildOutcome run(PreparedBuild prepared, BuildControl control) {
        if (prepared == null) {
            return BuildOutcome.failed("Error running build: no prepared build");
        }
        if (prepared.error() != null) {
            return BuildOutcome.failed(prepared.error());
        }
        if (control == null) {
            return BuildOutcome.failed("Error running " + label(prepared) + ": no build control");
        }
        Process process = null;
        Thread reader = null;
        AtomicReference<String> output = new AtomicReference<>("");
        AtomicReference<Exception> readerFailure = new AtomicReference<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(prepared.command());
            pb.directory(prepared.root());
            pb.redirectErrorStream(true);
            process = pb.start();
            control.attach(process);
            final Process running = process;
            reader = new Thread(() -> readOutput(running, output, readerFailure),
                                prepared.backend().logTag() + "-output-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(control.timeoutMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            join(reader, 5_000L);
            String text = output.get();
            if (readerFailure.get() != null) {
                return BuildOutcome.failed(BuildOutputFormatter.attachLog(prepared.sessionId(), prepared.backend(),
                                                                          "Error reading " + label(prepared) + " output: " + readerFailure.get().getMessage(), prepared.command(), text));
            }
            if (!finished) {
                return BuildOutcome.timedOut(BuildOutputFormatter.attachLog(prepared.sessionId(), prepared.backend(),
                                                                            "Timed out after " + TimeUnit.MILLISECONDS.toSeconds(control.timeoutMillis()) + "s", prepared.command(), text));
            }
            int exit = process.exitValue();
            return BuildOutcome.completed(exit == 0, BuildOutputFormatter.formatResult(prepared.sessionId(),
                                                                                       prepared.backend(), exit == 0, exit, prepared.command(), text));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            join(reader, 2_000L);
            return BuildOutcome.failed("Interrupted waiting for build");
        }
        catch (Exception e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return BuildOutcome.failed("Error running " + label(prepared) + ": " + e.getMessage());
        }
    }

    private static void readOutput(Process process, AtomicReference<String> output, AtomicReference<Exception> failure) {
        try (InputStream in = process.getInputStream()) {
            byte[] buf = new byte[8192];
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                if (bytes.size() < MAX_OUTPUT_BYTES) {
                    bytes.write(buf, 0, Math.min(n, MAX_OUTPUT_BYTES - bytes.size()));
                }
            }
            output.set(bytes.toString(StandardCharsets.UTF_8));
        }
        catch (Exception e) {
            failure.set(e);
        }
    }

    private static void join(Thread thread, long millis) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String label(PreparedBuild prepared) {
        return prepared.backend() == null ? "build" : prepared.backend().name().charAt(0)
                + prepared.backend().name().substring(1).toLowerCase();
    }

    private BuildProcessRunner() {
    }
}
