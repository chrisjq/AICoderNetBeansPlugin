package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class DurationTimeLoggerTest {

    @Test
    void measuresFromCreationAgainstItsClock() {
        Instant start = Instant.parse("2026-09-16T01:00:00Z");
        MutableClock clock = new MutableClock(start);
        DurationTimeLogger logger = new DurationTimeLogger(clock);
        clock.advance(Duration.ofMinutes(2).plusSeconds(3).plusMillis(45));

        assertEquals(start, logger.getStartTime());
        assertEquals(Duration.ofMillis(123_045), logger.getDuration());
        assertEquals(123_045, logger.getDurationMS());
        assertEquals("2 mins, 3 secs, 45 ms", logger.getDurationString());
        assertEquals("Build[2 mins, 3 secs, 45 ms]", logger.getDurationString("Build"));
    }

    @Test
    void noElapsedTimeRendersAsZero() {
        DurationTimeLogger logger = new DurationTimeLogger(new MutableClock(Instant.parse("2026-09-16T01:00:00Z")));

        assertEquals("0 ms", logger.getDurationString());
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
