package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Measures the time elapsed since it was created, for reporting how long something took, e.g. a build.
 */
public final class DurationTimeLogger {

    private final Clock clock;
    private final Instant startTime;

    public DurationTimeLogger() {
        this(Clock.systemUTC());
    }

    /**
     * Measures against {@code clock} instead of the system clock, so callers with their own clock (and tests) agree on
     * times.
     */
    public DurationTimeLogger(Clock clock) {
        this.clock = clock;
        this.startTime = clock.instant();
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Duration getDuration() {
        return Duration.between(startTime, clock.instant());
    }

    public long getDurationMS() {
        return getDuration().toMillis();
    }

    /**
     * The elapsed time for display, e.g. {@code 2 mins, 3 secs, 45 ms}.
     */
    public String getDurationString() {
        return DateUtil.formatDuration(getDuration());
    }

    /**
     * The elapsed time for display after a label, e.g. {@code Build[2 mins, 3 secs, 45 ms]}.
     */
    public String getDurationString(String label) {
        return label + "[" + getDurationString() + "]";
    }
}
