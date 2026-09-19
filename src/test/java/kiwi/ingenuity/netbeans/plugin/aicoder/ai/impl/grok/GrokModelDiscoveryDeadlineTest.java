package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins GrokModelDiscovery's bounded discovery against the two hang shapes that
 * wedged the old inline-drain version forever: a CLI that never writes and
 * never exits, and one that drains its stdout fully and then refuses to exit.
 * Both must return an empty list within the (test- shortened) budget instead of
 * blocking the caller. Reverting {@code discover()} to the pre-fix inline-drain
 * body blocks the drain forever on the first two scripts; each test goes red
 * via its own preemptive timeout rather than hanging the suite.
 * <p>
 * The budget is set per test, not once for the class, because it means opposite
 * things to the two kinds of test here: for the hang shapes it is the runtime
 * and wants to be small, for the healthy shape it is unused headroom and wants
 * to be large. One shared value cannot be both, and the compromise value is
 * what made the healthy test flaky.
 */
class GrokModelDiscoveryDeadlineTest {

    private static final Duration TEST_CEILING = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    /**
     * Shortens the whole-attempt budget for the two HANG-shape tests only.
     * <p>
     * For those two the budget is not a safety margin, it is the runtime: their
     * fake CLI never reaches EOF and never exits, so discover() always waits
     * the budget out before giving up. That also makes a small value safe here
     * — the expected answer is "empty" no matter how slow the machine is,
     * because no amount of slowness turns a {@code sleep 30} into output. A
     * shared budget would have to be sized for the healthy test instead, and
     * every millisecond of it would be paid twice, on every run, for nothing.
     */
    private static void shortenBudget() {
        GrokModelDiscovery.discoveryBudgetMillisForTests = 400L;
    }

    @AfterEach
    void restoreBudget() {
        GrokModelDiscovery.discoveryBudgetMillisForTests = null;
    }

    private static void assumePosix() {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "fake executable scripts need POSIX permission bits");
    }

    private Path script(String name, String body) throws IOException {
        assumePosix();
        Path path = dir.resolve(name);
        Files.writeString(path, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE));
        return path;
    }

    @Test
    void hungSilentCli_returnsEmptyWithinBudget() throws Exception {
        shortenBudget();
        Path cli = script("grok-hang", "sleep 30");

        List<String> models = assertTimeoutPreemptively(TEST_CEILING,
                () -> GrokModelDiscovery.discover(cli.toString()));

        assertTrue(models.isEmpty(), "a CLI that hangs with the pipe open reports no models");
    }

    @Test
    void drainedThenHangingCli_returnsEmptyWithinBudget() throws Exception {
        shortenBudget();
        // Emits one valid bullet (so the old code would even look productive), closes stdout,
        // then never exits — the second half of the old wedge.
        Path cli = script("grok-drain-hang",
                "echo '  * grok-x-1 (default)'\nexec 1>&-\nsleep 30");

        List<String> models = assertTimeoutPreemptively(TEST_CEILING,
                () -> GrokModelDiscovery.discover(cli.toString()));

        assertTrue(models.isEmpty(), "drained-but-unexiting CLI reports no models");
    }

    @Test
    void healthyCli_bulletsParseAsModelIds() throws Exception {
        // Deliberately does NOT shorten the budget: this test is about what gets PARSED, not about the deadline, and
        // a healthy CLI never waits the budget out — discover() returns the moment stdout hits EOF and the process
        // exits, so a large budget costs nothing when the script behaves. Shortening it only invented a way to fail.
        // At the production 15s the margin over a real run (tens of ms) is ~1000x, well past any plausible spawn
        // stall, and TEST_CEILING is still the real guard: if discover() ever goes back to blocking, this fails at
        // 10s instead of hanging the suite.
        Path cli = script("grok-good",
                "echo 'You are logged in with grok.com.'\n"
                + "echo 'Default model: grok-x-1'\n"
                + "echo 'Available models:'\n"
                + "echo '  * grok-x-1 (default)'\n"
                + "echo '  - grok-y-2 fast'\n");

        List<String> models = assertTimeoutPreemptively(TEST_CEILING,
                () -> GrokModelDiscovery.discover(cli.toString()));

        assertEquals(List.of("grok-x-1", "grok-y-2"), models);
    }
}
