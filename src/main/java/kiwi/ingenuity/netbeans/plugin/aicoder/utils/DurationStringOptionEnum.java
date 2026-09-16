package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.time.Duration;

/**
 * The units {@link DateUtil#formatDuration(java.util.Set, Duration)} may render, smallest first. Declaration order is
 * relied on: {@link java.util.EnumSet#range} builds the unit sets from it.
 */
public enum DurationStringOptionEnum {

    MILLISECONDS("ms", "ms"),
    SECONDS("sec", "secs"),
    MINUTES("min", "mins"),
    HOURS("hour", "hours"),
    DAYS("day", "days");

    private final String singular;
    private final String plural;

    DurationStringOptionEnum(String singular, String plural) {
        this.singular = singular;
        this.plural = plural;
    }

    /**
     * This unit's part of {@code duration}, e.g. 5 for the minutes of 2 hours 5 minutes. Days are not capped.
     */
    long partOf(Duration duration) {
        return switch (this) {
            case MILLISECONDS ->
                duration.toMillisPart();
            case SECONDS ->
                duration.toSecondsPart();
            case MINUTES ->
                duration.toMinutesPart();
            case HOURS ->
                duration.toHoursPart();
            case DAYS ->
                duration.toDaysPart();
        };
    }

    /**
     * {@code value} with this unit's label, e.g. {@code 1 min} or {@code 2 mins}.
     */
    String label(long value) {
        return value + " " + (value == 1 ? singular : plural);
    }
}
