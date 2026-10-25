package kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;

/**
 * Serialises all backend diagnostic logs through one daemon writer thread.
 */
public final class BackendDebugLogWriter {

    /**
     * The default shutdown drain bound, used until {@link #setShutdownDrainBound} changes it.
     */
    public static final Duration SHUTDOWN_DRAIN_BOUND = Duration.ofSeconds(2);
    private static volatile Duration shutdownDrainBound = SHUTDOWN_DRAIN_BOUND;

    private static final Logger LOG = Logger.getLogger(BackendDebugLogWriter.class.getName());
    private static final LinkedBlockingQueue<Command> COMMANDS = new LinkedBlockingQueue<>();
    private static final Map<BackendDebugLog, Boolean> LOGS = new ConcurrentHashMap<>();
    private static final Object PAUSE_MONITOR = new Object();
    private static final AtomicBoolean FAIL_NEXT_WRITE = new AtomicBoolean();
    private static volatile Thread writer;
    private static volatile boolean draining;
    private static volatile boolean paused;

    private BackendDebugLogWriter() {
    }

    public static BackendDebugLog open(Path file, DebugLogBudget budget) {
        if (file == null || budget == null) {
            throw new IllegalArgumentException("file and budget are required");
        }
        BackendDebugLog log = new BackendDebugLog(file, budget);
        if (draining) {
            log.closed = true;
            return log;
        }
        LOGS.put(log, Boolean.TRUE);
        ensureWriter();
        return log;
    }

    static void log(BackendDebugLog log, DebugDirectionEnum direction, String kind, String payload) {
        try {
            if (log.closed || log.failed || draining) {
                return;
            }
            String body = payload == null ? "null" : payload;
            int limit = Math.max(0, log.budget.maxEntryBytes());
            if (body.length() > limit) {
                int omitted = body.length() - limit;
                body = body.substring(0, limit) + " …[truncated " + omitted + " chars]";
            }
            String line = Instant.now() + " " + direction + " " + kind + " " + redact(log, body);
            long charge = (long) line.length() * 2;
            synchronized (log) {
                if (log.closed || log.finished || log.failed || draining) {
                    return;
                }
                if (log.queuedEntries.get() >= log.budget.maxQueueEntries()
                        || log.queuedBytes.get() + charge > log.budget.maxQueueBytes()) {
                    log.dropped.incrementAndGet();
                    return;
                }
                log.queuedEntries.incrementAndGet();
                log.queuedBytes.addAndGet(charge);
                ensureWriter();
                COMMANDS.offer(new Write(log, line, charge));
            }
        }
        catch (RuntimeException ex) {
            // Debug logging must never affect the backend caller.
        }
    }

    static CompletableFuture<Void> close(BackendDebugLog log, boolean delete) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (log) {
            if (log.closed) {
                return CompletableFuture.completedFuture(null);
            }
            log.closed = true;
            ensureWriter();
            COMMANDS.offer(new Close(log, future, delete));
        }
        return future;
    }

    /**
     * Closes and deletes every open log whose file is inside {@code dir}. Already-closed logs complete at once. An
     * empty match completes immediately.
     */
    public static CompletableFuture<Void> closeAndDeleteUnder(Path dir) {
        if (dir == null) {
            return CompletableFuture.completedFuture(null);
        }
        Path root = dir.toAbsolutePath().normalize();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (BackendDebugLog log : new ArrayList<>(LOGS.keySet())) {
            Path file = log.file;
            if (file != null && file.toAbsolutePath().normalize().startsWith(root)) {
                pending.add(log.closeAndDelete());
            }
        }
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    /**
     * Stops accepting new entries and waits no longer than {@code bound} for the writer.
     */
    public static boolean drain(Duration bound) {
        draining = true;
        Thread current = writer;
        if (current == null || !current.isAlive()) {
            closeRemaining();
            return true;
        }
        CountDownLatch finished = new CountDownLatch(1);
        COMMANDS.offer(new Poison(finished));
        boolean completed;
        try {
            completed = finished.await(Math.max(0, bound.toNanos()), TimeUnit.NANOSECONDS);
            if (!completed) {
                current.interrupt();
                current.join(200);
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        if (!completed) {
            closeRemaining();
        }
        return completed;
    }

    /**
     * Sets how long {@link #drain} waits for queued entries at shutdown. Null is rejected; a negative bound counts as
     * zero.
     */
    public static void setShutdownDrainBound(Duration bound) {
        if (bound == null) {
            throw new IllegalArgumentException("bound is required");
        }
        shutdownDrainBound = bound.isNegative() ? Duration.ZERO : bound;
    }

    /**
     * The bound the shutdown drain currently waits for.
     */
    public static Duration shutdownDrainBound() {
        return shutdownDrainBound;
    }

    static void pauseForTest(boolean value) {
        synchronized (PAUSE_MONITOR) {
            paused = value;
            if (!value) {
                PAUSE_MONITOR.notifyAll();
            }
        }
    }

    static void failNextWriteForTest() {
        FAIL_NEXT_WRITE.set(true);
    }

    static synchronized void resetForTest() {
        pauseForTest(false);
        draining = false;
        FAIL_NEXT_WRITE.set(false);
        Thread old = writer;
        if (old != null && old.isAlive()) {
            old.interrupt();
            try {
                old.join(200);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        COMMANDS.clear();
        writer = null;
        LOGS.clear();
    }

    private static synchronized void ensureWriter() {
        if (draining || (writer != null && writer.isAlive())) {
            return;
        }
        writer = new Thread(BackendDebugLogWriter::run, "aicoder-debuglog-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private static void run() {
        try {
            for (;;) {
                awaitUnpaused();
                Command command = COMMANDS.take();
                try {
                    if (command instanceof Write write) {
                        write(write);
                    }
                    else if (command instanceof Close close) {
                        close(close);
                    }
                    else if (command instanceof Poison poison) {
                        closeRemaining();
                        poison.finished.countDown();
                        return;
                    }
                }
                catch (Throwable t) {
                    diagnostic("Backend debug-log writer command failed", t);
                    if (command instanceof Write write) {
                        fail(write.log, write.charge);
                    }
                    else if (command instanceof Close close) {
                        close.future.complete(null);
                    }
                }
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        finally {
            closeRemaining();
        }
    }

    private static void awaitUnpaused() throws InterruptedException {
        synchronized (PAUSE_MONITOR) {
            while (paused) {
                PAUSE_MONITOR.wait();
            }
        }
    }

    private static void write(Write write) throws IOException {
        BackendDebugLog log = write.log;
        if (log.finished) {
            release(log, write.charge);
            return;
        }
        try {
            if (!log.failed) {
                if (FAIL_NEXT_WRITE.compareAndSet(true, false)) {
                    throw new IOException("Injected debug-log write failure");
                }
                writeDropped(log);
                append(log, write.line);
            }
        }
        catch (IOException ex) {
            fail(log, write.charge);
            return;
        }
        release(log, write.charge);
    }

    private static void append(BackendDebugLog log, String line) throws IOException {
        Files.createDirectories(log.file.toAbsolutePath().getParent());
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (Files.exists(log.file) && Files.size(log.file) > 0
                && Files.size(log.file) + bytes.length > log.budget.fileCapBytes()) {
            roll(log);
        }
        Files.write(log.file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void writeDropped(BackendDebugLog log) throws IOException {
        long count = log.dropped.getAndSet(0);
        if (count > 0) {
            append(log, count + " entries dropped");
        }
    }

    private static Path rolled(Path file, int generation) {
        return file.resolveSibling(file.getFileName() + "." + generation);
    }

    /**
     * Makes room for the next entry. With rolled files kept, shifts .1 up to .N (the oldest is discarded) and moves the
     * live file to .1; with none kept, truncates the live file.
     */
    private static void roll(BackendDebugLog log) throws IOException {
        int keep = log.budget.rolledFiles();
        Path file = log.file;
        if (keep == 0) {
            Files.write(file, new byte[0]);
            return;
        }
        Files.deleteIfExists(rolled(file, keep));
        for (int generation = keep - 1; generation >= 1; generation--) {
            Path older = rolled(file, generation);
            if (Files.exists(older)) {
                Files.move(older, rolled(file, generation + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, rolled(file, 1), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void fail(BackendDebugLog log, long charge) {
        log.failed = true;
        release(log, charge);
        diagnostic("Backend debug-log write failed for " + log.file, null);
    }

    private static void release(BackendDebugLog log, long charge) {
        log.queuedBytes.updateAndGet(value -> Math.max(0, value - charge));
        log.queuedEntries.updateAndGet(value -> Math.max(0, value - 1));
    }

    private static void close(Close close) {
        try {
            BackendDebugLog log = close.log;
            if (!log.failed) {
                writeDropped(log);
            }
            if (close.delete) {
                Files.deleteIfExists(log.file);
                // Always clear .1, in case an earlier run kept rolled files under a larger setting.
                for (int generation = 1; generation <= Math.max(1, log.budget.rolledFiles()); generation++) {
                    Files.deleteIfExists(rolled(log.file, generation));
                }
            }
            LOGS.remove(log);
        }
        catch (IOException ex) {
            diagnostic("Backend debug-log close failed", ex);
        }
        finally {
            freeRemaining(close.log);
            close.log.finished = true;
            close.future.complete(null);
        }
    }

    private static void closeRemaining() {
        for (BackendDebugLog log : new ArrayList<>(LOGS.keySet())) {
            synchronized (log) {
                log.closed = true;
            }
            try {
                if (!log.failed) {
                    writeDropped(log);
                }
            }
            catch (IOException ex) {
                diagnostic("Backend debug-log drain close failed", ex);
            }
            finally {
                freeRemaining(log);
                LOGS.remove(log);
            }
        }
    }

    private static void freeRemaining(BackendDebugLog log) {
        log.queuedBytes.set(0);
        log.queuedEntries.set(0);
    }

    private static String redact(BackendDebugLog log, String value) {
        String redacted = McpHookServerUtil.redactAllSecrets(value);
        List<String> secrets = new ArrayList<>(log.secrets);
        secrets.sort(Comparator.comparingInt(String::length).reversed());
        for (String secret : secrets) {
            redacted = redacted.replace(secret, "[REDACTED]");
        }
        return redacted;
    }

    private static void diagnostic(String message, Throwable error) {
        if (PluginSettings.isDebugJson()) {
            if (error == null) {
                LOG.log(Level.FINE, message);
            }
            else {
                LOG.log(Level.FINE, message, error);
            }
        }
    }

    private sealed interface Command permits Write, Close, Poison {
    }

    private record Write(BackendDebugLog log, String line, long charge) implements Command {

    }

    private record Close(BackendDebugLog log, CompletableFuture<Void> future, boolean delete) implements Command {

    }

    private record Poison(CountDownLatch finished) implements Command {

    }
}
