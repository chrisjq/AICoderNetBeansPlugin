package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class DateUtilTest {

    @Test
    void formatsKnownInstantInLocalZoneWithOffset() {
        Instant instant = Instant.parse("2026-08-22T09:10:12Z");
        String expectedDate = DateTimeFormatter.ISO_LOCAL_DATE
                .withZone(ZoneId.systemDefault())
                .format(instant);

        String formatted = DateUtil.format(instant);

        assertTrue(formatted.startsWith(expectedDate), formatted);
        // Offset then zone ID, e.g. "... +12:00 (Pacific/Auckland)". Matched as
        // a shape rather than a fixed string so the test travels: it must pass
        // wherever it is run, including at UTC where XXX emits a literal Z.
        assertTrue(formatted.matches(".*(?:Z|[+-][0-9]{2}:[0-9]{2}) \\(.+\\)$"), formatted);
    }

    @Test
    void rendersTheWholeTimestampAndPicksUpAZoneChangeBetweenCalls() {
        // Two things the shape assertion above cannot catch: that the time
        // itself is right (a pattern losing seconds, or rendering the clock in
        // UTC while the date is local, still matches the shape), and the
        // javadoc's claim that the zone is resolved per call rather than frozen
        // at class load. Both are asserted here against exact strings.
        Instant instant = Instant.parse("2026-08-22T09:10:12Z");
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            assertEquals("2026-08-22 21:10:12 +12:00 (Pacific/Auckland)", DateUtil.format(instant));

            // Same JVM, same formatter instance, zone changed underneath it.
            // A cached zone would keep printing Auckland here.
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals("2026-08-22 09:10:12 Z (UTC)", DateUtil.format(instant));
        }
        finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void formatsEpochMillisThroughSameDisplayFormatter() {
        long epochMillis = 0L;
        assertEquals(DateUtil.format(Instant.ofEpochMilli(epochMillis)), DateUtil.format(epochMillis));
    }
}
