package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Formats a moment for DISPLAY TO AN AI, in the machine's local timezone.
 * <p>
 * Display only. Nothing here may be persisted, parsed back, or compared — saved formats use {@code Instant.toString()}
 * or epoch millis and must stay that way, because reformatting them silently breaks files already on disk. The output
 * deliberately carries a zone ID, which no standard parser accepts, so an accidental round trip fails loudly rather
 * than quietly.
 */
public final class DateUtil {

    /**
     * Renders e.g. {@code 2026-08-22 21:28:48 +12:00 (Pacific/Auckland)}.
     * <p>
     * XXX gives an ISO-8601 offset with a colon, and a literal {@code Z} at zero offset. The pattern letter {@code Z}
     * would NOT do that — it produces RFC-822 {@code +1200} and never prints {@code Z}. VV adds the zone ID, which
     * {@code z} ("NZST") cannot safely replace: those abbreviations collide, CST being US Central, China Standard and
     * Cuba Standard at once.
     * <p>
     * Only the pattern is cached. The zone is resolved per call so a timezone change during a long-running IDE session
     * is picked up — caching it would mean every time we show an AI is silently in the old zone while carrying an
     * offset that claims otherwise.
     */
    private static final DateTimeFormatter DISPLAY_FORMATTER
            = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX (VV)", Locale.ROOT);

    /**
     * Formats an instant for display only — never use for anything persisted or compared.
     */
    public static String format(Instant instant) {
        return DISPLAY_FORMATTER.withZone(ZoneId.systemDefault()).format(instant);
    }

    /**
     * Formats epoch milliseconds for display only — never use for anything persisted or compared.
     */
    public static String format(long epochMillis) {
        return format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * Returns the current local time for display only — never use for anything persisted or compared.
     */
    public static String now() {
        return format(Instant.now());
    }

    /**
     * Every unit, days down to milliseconds, e.g. {@code 1 hour, 2 mins, 3 secs, 45 ms}.
     */
    public static final Set<DurationStringOptionEnum> DURATION_ALL_UNITS
            = Collections.unmodifiableSet(EnumSet.allOf(DurationStringOptionEnum.class));
    /**
     * Days down to seconds.
     */
    public static final Set<DurationStringOptionEnum> DURATION_TO_SECONDS = Collections.unmodifiableSet(
            EnumSet.range(DurationStringOptionEnum.SECONDS, DurationStringOptionEnum.DAYS));
    /**
     * Days down to minutes.
     */
    public static final Set<DurationStringOptionEnum> DURATION_TO_MINUTES = Collections.unmodifiableSet(
            EnumSet.range(DurationStringOptionEnum.MINUTES, DurationStringOptionEnum.DAYS));

    /**
     * Formats a duration for display using every unit, e.g. {@code 2 mins, 3 secs, 45 ms}.
     */
    public static String formatDuration(Duration duration) {
        return formatDuration(DURATION_ALL_UNITS, duration);
    }

    /**
     * Formats a duration for display as its non-zero parts in {@code units}, largest first and joined by commas, e.g.
     * {@code 2 days, 1 hour, 5 mins}. A unit left out of {@code units} is dropped, not carried into another unit. A
     * zero, negative or null duration renders as zero of the smallest unit requested, e.g. {@code 0 secs}.
     */
    public static String formatDuration(Set<DurationStringOptionEnum> units, Duration duration) {
        Duration d = duration == null || duration.isNegative() ? Duration.ZERO : duration;
        List<String> parts = new ArrayList<>();
        DurationStringOptionEnum[] smallestFirst = DurationStringOptionEnum.values();
        for (int i = smallestFirst.length - 1; i >= 0; i--) {
            DurationStringOptionEnum unit = smallestFirst[i];
            long value = unit.partOf(d);
            if (units.contains(unit) && value > 0) {
                parts.add(unit.label(value));
            }
        }
        if (parts.isEmpty()) {
            return units.stream().min(Comparator.naturalOrder()).map(unit -> unit.label(0)).orElse("");
        }
        return String.join(", ", parts);
    }

    private DateUtil() {
    }
}
