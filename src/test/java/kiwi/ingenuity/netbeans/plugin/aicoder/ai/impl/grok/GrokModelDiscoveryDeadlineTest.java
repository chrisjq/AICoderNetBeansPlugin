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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins GrokModelDiscovery's bounded discovery against the two hang shapes that wedged the old inline-drain version
 * forever: a CLI that never writes and never exits, and one that drains its stdout fully and then refuses to exit. Both
 * must return an empty list within the (test- shortened) budget instead of blocking the caller. Reverting
 * {@code discover()} to the pre-fix inline-drain body blocks the drain forever on the first two scripts; each test goes
 * red via its own preemptive timeout rather than hanging the suite.
 */
class GrokModelDiscoveryDeadlineTest {

    private static final Duration TEST_CEILING = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    @BeforeEach
    void shortenBudget() {
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
        Path cli = script("grok-hang", "sleep 30");

        List<String> models = assertTimeoutPreemptively(TEST_CEILING,
                () -> GrokModelDiscovery.discover(cli.toString()));

        assertTrue(models.isEmpty(), "a CLI that hangs with the pipe open reports no models");
    }

    @Test
    void drainedThenHangingCli_returnsEmptyWithinBudget() throws Exception {
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
