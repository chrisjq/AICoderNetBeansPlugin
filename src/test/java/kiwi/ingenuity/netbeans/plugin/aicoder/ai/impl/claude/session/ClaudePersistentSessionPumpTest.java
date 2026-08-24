package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins the pump listener guard in {@code ClaudePersistentSession}: the process stays alive behind the stream, so a
 * downstream consumer throwing on one line must cost that one line only — never the reader loop itself. Reverting the
 * per-line try/catch lets the consumer's exception escape {@code pump} and strands every later line; both assertions
 * then go red together.
 */
class ClaudePersistentSessionPumpTest {

    @Test
    void throwingListener_costsOnlyItsOwnLine_streamKeepsFlowing() {
        ByteArrayInputStream in = new ByteArrayInputStream(
                "one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8));
        List<String> seen = new ArrayList<>();

        assertDoesNotThrow(() -> ClaudePersistentSession.pump(in, line -> {
            seen.add(line);
            if (line.equals("two")) {
                throw new RuntimeException("listener exploded");
            }
        }));

        assertEquals(List.of("one", "two", "three"), seen,
                "a throwing listener must not kill the reader mid-stream");
    }

    @Test
    void emptyStream_yieldsNothing_andReturnsCleanly() {
        List<String> seen = new ArrayList<>();

        ClaudePersistentSession.pump(new ByteArrayInputStream(new byte[0]), seen::add);

        assertTrue(seen.isEmpty());
    }
}
