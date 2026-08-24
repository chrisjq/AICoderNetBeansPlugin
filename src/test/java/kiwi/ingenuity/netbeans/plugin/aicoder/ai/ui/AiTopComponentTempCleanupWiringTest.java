package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@code AiTopComponent.componentClosed()} — the tab closing, NOT {@code componentHidden()}, which fires on
 * ordinary tab-switching too, and NOT permanent session deletion, which is a separate path entirely — sweeps the
 * session's temp directory.
 * <p>
 * {@code AiTopComponent}'s constructor eagerly builds a real per-{@code AiTypeEnum} backend
 * ({@code AiTypeRegistry().create(...)}), so constructing one in a unit test would pull in process managers and
 * executable lookups far beyond what belongs in a fast test — there is no lightweight way to instantiate the real class
 * and call {@code componentClosed()} on it. {@link TempFileRegistryAsyncCleanupTest} (in process.tools) already covers
 * {@code cleanupSessionAsync} itself behaviourally; what is missing without this test is a check that the METHOD IS
 * STILL CALLED FROM the lifecycle hook, which is exactly the class of bug this whole investigation started from
 * (cleanupSession existed and worked, but nothing on the tab-close path called it). This test fails if that wiring is
 * ever removed, even though nothing about TempFileRegistry itself would need to change for that to happen.
 */
class AiTopComponentTempCleanupWiringTest {

    @Test
    void componentClosed_sweepsTheSessionsTempDirectoryAsynchronously() throws IOException {
        Path source = Path.of("src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/ui/AiTopComponent.java");
        String content = Files.readString(source);

        int start = content.indexOf("public void componentClosed()");
        assertTrue(start >= 0, "componentClosed() method not found in AiTopComponent.java");
        int end = content.indexOf("\n    @Override", start + 1);
        String body = end > 0 ? content.substring(start, end) : content.substring(start);

        assertTrue(body.contains("TempFileRegistry.cleanupSessionAsync("),
                "componentClosed() must sweep the session's temp directory via "
                + "TempFileRegistry.cleanupSessionAsync — this is the tab-close event, "
                + "distinct from componentHidden (fires on ordinary tab-switching) and from "
                + "permanent session deletion (SessionPersistenceManager.delete, which already "
                + "calls the synchronous cleanupSession). If this fails, the wiring for the bug "
                + "this test was written for (\"session tmp dir survives session close\") has "
                + "regressed: " + body);
    }
}
