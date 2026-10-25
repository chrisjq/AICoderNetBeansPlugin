package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.time.Duration;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

/**
 * Every limit the OpenAI Compatible backend needs, in one injected object: the ten durations (request timeouts, probe
 * cadence, retry pacing), the three byte size caps and the transport retry count.
 *
 * <p>
 * The compact constructor applies the spec §8.1 fixed safety floors: connect &ge; 1 s; first-byte &gt; 0 else 1 s;
 * total cap &gt; max(first-byte, idle) else max + 1 s; probe timeout &lt; re-detect interval else interval &minus; 1 s;
 * size caps &ge; 1 MB; retries &ge; 0. A clamped value is logged at FINE, once per field, only when
 * {@code PluginSettings.isDebugJson()} is on.
 *
 * <p>
 * The idle timeout is special: zero means "no idle timeout" (see {@link #idleTimeoutOff()}), so it has no floor. The
 * check-task interval is not a user setting (spec §8.2 note) but is a field so tests can shorten it. The plugin-wide
 * shutdown drain bound is deliberately not in this record — it is backend-neutral and lives on {@code BackendDebugLogWriter}.
 *
 * <p>
 * {@link #defaults()} returns the spec §8.2 defaults. Later plans add fields (context, estimator, per-model probes) the
 * same way: a component, a floor in the compact constructor, a default in {@link #defaults()} and a {@code withX(...)}
 * copy.
 */
public record OpenAiCompatibleLimits(
        Duration connectTimeout,
        Duration firstByteTimeout,
        Duration idleTimeout,
        Duration totalCap,
        Duration checkInterval,
        Duration abandonedToolWait,
        Duration probeTimeout,
        Duration redetectInterval,
        Duration retryBackoff,
        Duration retryAfterCap,
        long probeBodyCapBytes,
        long transcriptCapBytes,
        long toolArgumentCapBytes,
        int transportRetries) {

    private static final Logger logger = Logger.getLogger(OpenAiCompatibleLimits.class.getName());

    private static final Duration CONNECT_FLOOR = Duration.ofSeconds(1);
    private static final Duration FIRST_BYTE_FALLBACK = Duration.ofSeconds(1);
    private static final long SIZE_CAP_FLOOR = 1L << 20;

    private static final long PROBE_BODY_CAP_DEFAULT = 1L << 20;
    private static final long TRANSCRIPT_CAP_DEFAULT = 64L << 20;
    private static final long TOOL_ARGUMENT_CAP_DEFAULT = 1L << 20;

    public OpenAiCompatibleLimits {
        if (connectTimeout.compareTo(CONNECT_FLOOR) < 0) {
            connectTimeout = clamp("connectTimeout", connectTimeout, CONNECT_FLOOR);
        }
        if (firstByteTimeout.isZero() || firstByteTimeout.isNegative()) {
            firstByteTimeout = clamp("firstByteTimeout", firstByteTimeout, FIRST_BYTE_FALLBACK);
        }
        Duration longer = firstByteTimeout.compareTo(idleTimeout) >= 0 ? firstByteTimeout : idleTimeout;
        Duration totalFloor = longer.plusSeconds(1);
        if (totalCap.compareTo(totalFloor) <= 0) {
            totalCap = clamp("totalCap", totalCap, totalFloor);
        }
        if (probeTimeout.compareTo(redetectInterval) >= 0) {
            probeTimeout = clamp("probeTimeout", probeTimeout, redetectInterval.minusSeconds(1));
        }
        if (probeBodyCapBytes < SIZE_CAP_FLOOR) {
            probeBodyCapBytes = clamp("probeBodyCapBytes", probeBodyCapBytes, SIZE_CAP_FLOOR);
        }
        if (transcriptCapBytes < SIZE_CAP_FLOOR) {
            transcriptCapBytes = clamp("transcriptCapBytes", transcriptCapBytes, SIZE_CAP_FLOOR);
        }
        if (toolArgumentCapBytes < SIZE_CAP_FLOOR) {
            toolArgumentCapBytes = clamp("toolArgumentCapBytes", toolArgumentCapBytes, SIZE_CAP_FLOOR);
        }
        if (transportRetries < 0) {
            transportRetries = clamp("transportRetries", transportRetries, 0);
        }
    }

    /**
     * The spec §8.2 defaults.
     */
    public static OpenAiCompatibleLimits defaults() {
        return new OpenAiCompatibleLimits(
                Duration.ofSeconds(10),
                Duration.ofSeconds(600),
                Duration.ofSeconds(120),
                Duration.ofMinutes(60),
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofSeconds(3),
                Duration.ofSeconds(60),
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                PROBE_BODY_CAP_DEFAULT,
                TRANSCRIPT_CAP_DEFAULT,
                TOOL_ARGUMENT_CAP_DEFAULT,
                1);
    }

    /**
     * A zero idle timeout disables the idle timeout (spec §8.2: "120 s (0 = off)").
     */
    public boolean idleTimeoutOff() {
        return idleTimeout.isZero();
    }

    public OpenAiCompatibleLimits withConnectTimeout(Duration connectTimeout) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withFirstByteTimeout(Duration firstByteTimeout) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withIdleTimeout(Duration idleTimeout) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withTotalCap(Duration totalCap) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withCheckInterval(Duration checkInterval) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withAbandonedToolWait(Duration abandonedToolWait) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withProbeTimeout(Duration probeTimeout) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withRedetectInterval(Duration redetectInterval) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withRetryBackoff(Duration retryBackoff) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withRetryAfterCap(Duration retryAfterCap) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withProbeBodyCapBytes(long probeBodyCapBytes) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withTranscriptCapBytes(long transcriptCapBytes) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withToolArgumentCapBytes(long toolArgumentCapBytes) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    public OpenAiCompatibleLimits withTransportRetries(int transportRetries) {
        return new OpenAiCompatibleLimits(
                connectTimeout, firstByteTimeout, idleTimeout, totalCap, checkInterval, abandonedToolWait,
                probeTimeout, redetectInterval, retryBackoff, retryAfterCap,
                probeBodyCapBytes, transcriptCapBytes, toolArgumentCapBytes, transportRetries);
    }

    /**
     * Reports the clamp at FINE, only while the debug-JSON flag is on (spec §8.1 "with a warning"; plugin constraint:
     * NetBeans-log diagnostics only behind the debug flag), and yields the clamped value.
     */
    private static <T> T clamp(String field, T original, T clamped) {
        if (PluginSettings.isDebugJson()) {
            logger.fine(() -> "OpenAiCompatibleLimits: clamped " + field + " from " + original + " to " + clamped);
        }
        return clamped;
    }
}
