package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1.V1CopilotSdk;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v2.V2CopilotSdk;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Selects the SDK matching the protocol spoken by the installed Copilot CLI: protocols 2 and 3 use v1, protocol 4 and
 * above use v2 (a CLI or SDK handshake that cannot cope with a newer protocol rejects it when the session starts).
 *
 * <p>
 * The probe result is cached per real CLI file together with its modification time and size, so replacing the CLI
 * probes again. A path that cannot be resolved to a file is probed every time and never cached. Concurrent callers for
 * the same CLI share one probe, and a failed probe is not cached.
 */
public final class CopilotSdkSelector {

    private static final String DEFAULT_COMMAND = "copilot";
    private static final int FIRST_V1_PROTOCOL = 2;
    private static final int FIRST_V2_PROTOCOL = 4;
    /**
     * How much longer than the probe itself a waiter waits for it, so a probe that never completes its future cannot
     * park a caller for good.
     */
    private static final long PROBE_WAIT_MARGIN_MILLIS = 5_000L;
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Integer>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static volatile Function<String, Integer> protocolProbe = CopilotRawRpc::probeProtocol;
    // Lambdas, not constructor references: a reference would resolve the adapter class while this class initialises, and
    // a broken SDK jar would then fail the selector itself instead of only that version.
    private static volatile Supplier<CopilotSdk> v1Factory = () -> new V1CopilotSdk();
    private static volatile Supplier<CopilotSdk> v2Factory = () -> new V2CopilotSdk();

    private CopilotSdkSelector() {
    }

    /**
     * The SDK for the CLI at {@code executablePath}, or for {@code copilot} on the PATH when it is null or blank.
     *
     * @throws CopilotSdkException when the protocol cannot be determined, is not supported, or the SDK fails to load
     */
    public static CopilotSdk sdkFor(String executablePath) throws CopilotSdkException {
        int protocol = protocolFor(executablePath);
        if (protocol >= FIRST_V2_PROTOCOL) {
            return load(v2Factory, "v2");
        }
        if (protocol >= FIRST_V1_PROTOCOL) {
            return load(v1Factory, "v1");
        }
        throw unsupported(protocol);
    }

    /**
     * Forgets the probe result for this CLI, so the next {@link #sdkFor} probes it again.
     */
    public static void invalidate(String executablePath) {
        CACHE.remove(locate(executablePath).key());
    }

    /**
     * The stamp the probe cache uses for this CLI, {@code modification-time-millis:size} of the real file, or null when
     * the path cannot be resolved to a file. A caller that remembers the stamp can tell later whether the CLI was
     * replaced. A null or blank path means {@code copilot} on the PATH.
     */
    public static String stampFor(String executablePath) {
        return locate(executablePath).stamp();
    }

    /**
     * Stores a protocol learned from a ping the raw RPC client made anyway, so the probe is not repeated.
     */
    static void recordProtocol(String executablePath, int protocol) {
        Location location = locate(executablePath);
        if (location.stamp() != null) {
            CACHE.put(location.key(), new CacheEntry(location.stamp(), protocol));
        }
    }

    static void setProtocolProbeForTests(Function<String, Integer> probe) {
        protocolProbe = probe == null ? CopilotRawRpc::probeProtocol : probe;
        CACHE.clear();
    }

    static void setSdkFactoriesForTests(Supplier<CopilotSdk> v1, Supplier<CopilotSdk> v2) {
        v1Factory = v1 == null ? () -> new V1CopilotSdk() : v1;
        v2Factory = v2 == null ? () -> new V2CopilotSdk() : v2;
    }

    private static CopilotSdk load(Supplier<CopilotSdk> factory, String version) throws CopilotSdkException {
        try {
            return factory.get();
        }
        catch (LinkageError error) {
            // Only the class name: the error's own text can carry paths or jar names that mean nothing to the user.
            throw new CopilotSdkException("Copilot SDK " + version + " failed to load: " + error.getClass().getName(),
                                          error);
        }
    }

    private static int protocolFor(String executablePath) throws CopilotSdkException {
        Location location = locate(executablePath);
        Integer cached = cachedProtocol(location);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<Integer> mine = new CompletableFuture<>();
        CompletableFuture<Integer> running = IN_FLIGHT.putIfAbsent(location.key(), mine);
        if (running != null) {
            return await(running);
        }
        try {
            // Another caller may have finished (and left the in-flight map) between the first cache check and here.
            Integer finished = cachedProtocol(location);
            int protocol = finished != null ? finished : protocolProbe.apply(executablePath);
            if (finished == null && location.stamp() != null) {
                CACHE.put(location.key(), new CacheEntry(location.stamp(), protocol));
            }
            mine.complete(protocol);
            return protocol;
        }
        catch (Throwable thrown) {
            // Anything the probe throws, an Error included, must reach the parked waiters: a future that is never
            // completed would block them for good.
            if (causedByInterrupt(thrown)) {
                Thread.currentThread().interrupt();
            }
            CopilotSdkException failure = new CopilotSdkException("Could not determine Copilot CLI protocol", thrown);
            mine.completeExceptionally(failure);
            if (thrown instanceof Error error) {
                throw error;
            }
            throw failure;
        }
        finally {
            IN_FLIGHT.remove(location.key(), mine);
        }
    }

    private static boolean causedByInterrupt(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private static Integer cachedProtocol(Location location) {
        if (location.stamp() == null) {
            return null;
        }
        CacheEntry entry = CACHE.get(location.key());
        return entry != null && entry.stamp().equals(location.stamp()) ? entry.protocol() : null;
    }

    private static int await(CompletableFuture<Integer> running) throws CopilotSdkException {
        try {
            return running.get(TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis() + PROBE_WAIT_MARGIN_MILLIS,
                               TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException timedOut) {
            throw new CopilotSdkException("Timed out waiting for the Copilot CLI protocol probe", timedOut);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CopilotSdkException("Interrupted while waiting for the Copilot CLI protocol probe", interrupted);
        }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof CopilotSdkException shared) {
                throw shared;
            }
            throw new CopilotSdkException("Could not determine Copilot CLI protocol", failed.getCause());
        }
    }

    private static CopilotSdkException unsupported(int protocol) {
        return new CopilotSdkException("This Copilot CLI speaks protocol " + protocol
                + "; the plugin supports " + FIRST_V1_PROTOCOL + " and above. Update the plugin or the Copilot CLI.");
    }

    /**
     * The identity of the CLI file: its real path and a stamp of modification time and size. A name that cannot be
     * resolved to a file keeps the name as its key and has no stamp, which means it is never cached.
     */
    static Location locate(String executablePath) {
        String name = executablePath == null || executablePath.isBlank() ? DEFAULT_COMMAND : executablePath;
        Path resolved = resolve(name, System.getenv("PATH"));
        if (resolved == null) {
            return new Location(name, null);
        }
        try {
            return new Location(resolved.toString(),
                                Files.getLastModifiedTime(resolved).toMillis() + ":" + Files.size(resolved));
        }
        catch (IOException exception) {
            return new Location(resolved.toString(), null);
        }
    }

    /**
     * The real file for a CLI name: a path with a directory part is used as it is, a bare name is searched for in
     * {@code pathVariable} (the PATH environment variable). Null when no regular file is found.
     */
    static Path resolve(String name, String pathVariable) {
        try {
            Path direct = Path.of(name);
            if (direct.isAbsolute() || direct.getNameCount() > 1) {
                return Files.isRegularFile(direct) ? direct.toRealPath() : null;
            }
            if (pathVariable == null) {
                return null;
            }
            for (String entry : pathVariable.split(File.pathSeparator)) {
                Path found = searchDirectory(unquote(entry), name);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * The CLI in one PATH entry, or null. A bad entry (an illegal character, a directory that cannot be read) is
     * skipped, so one of them never hides the entries after it. Where the file system has an execute bit, a file
     * without it is not the CLI.
     */
    private static Path searchDirectory(String directory, String name) {
        if (directory.isBlank()) {
            return null;
        }
        try {
            Path base = Path.of(directory);
            for (String candidate : candidateNames(name)) {
                Path path = base.resolve(candidate);
                if (Files.isRegularFile(path) && (isWindows() || Files.isExecutable(path))) {
                    return path.toRealPath();
                }
            }
        }
        catch (IOException | RuntimeException exception) {
            // skipped: the next PATH entry is tried
        }
        return null;
    }

    /**
     * A PATH entry without the double quotes Windows allows around it.
     */
    private static String unquote(String entry) {
        String trimmed = entry.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<String> candidateNames(String name) {
        if (isWindows()) {
            return List.of(name, name + ".exe", name + ".cmd", name + ".bat");
        }
        return List.of(name);
    }

    record Location(String key, String stamp) {

    }

    private record CacheEntry(String stamp, int protocol) {

    }
}
