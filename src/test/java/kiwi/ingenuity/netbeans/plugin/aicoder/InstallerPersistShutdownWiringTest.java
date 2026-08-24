package kiwi.ingenuity.netbeans.plugin.aicoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@code Installer.shutdownPluginState()} — the single teardown path shared by normal IDE exit
 * ({@code close()}) and plugin disable/uninstall ({@code uninstalled()}) — retires the shared session-persist pool via
 * {@code AiTopComponent.shutdownPersistExecutor()}.
 * <p>
 * Companion to {@code AiTopComponentTempCleanupWiringTest}: {@code Installer} cannot be exercised in a unit test
 * either, because {@code shutdownPluginState()} walks the {@code TopComponent} registry and shuts down the inbox
 * broker, {@code LockManager} and MCP-supervisor singletons — poisoning every later test sharing the JVM.
 * {@code AiTopComponentPersistExecutorTest} already covers {@code shutdownPersistExecutor()} itself behaviourally; what
 * is missing without this test is a check that the METHOD IS STILL CALLED FROM the plugin shutdown path — the exact bug
 * class this fix addressed (four never-timing-out pool threads pinning the module classloader after a
 * disable/uninstall, because nothing called a shutdown). This test fails if that wiring is ever removed, even though
 * nothing about the executor itself would need to change for that to happen.
 */
class InstallerPersistShutdownWiringTest {

    @Test
    void shutdownPluginState_retiresTheSessionPersistExecutor() throws IOException {
        Path source = Path.of("src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/Installer.java");
        String content = Files.readString(source);

        int start = content.indexOf("private void shutdownPluginState()");
        assertTrue(start >= 0, "shutdownPluginState() method not found in Installer.java");
        // Scope to this one method body: the next member declaration at 4-space indent ends it, so a
        // call mentioned only in a comment or another method elsewhere in the file cannot satisfy the check.
        int end = content.indexOf("\n    private ", start + 1);
        String body = end > 0 ? content.substring(start, end) : content.substring(start);

        assertTrue(body.contains("AiTopComponent.shutdownPersistExecutor("),
                "shutdownPluginState() must retire the shared session-persist pool via "
                + "AiTopComponent.shutdownPersistExecutor — it is the only caller, on the one path "
                + "(close()/uninstalled()) that runs at IDE exit and plugin disable/uninstall. If this "
                + "fails, the wiring for the bug this test was written for (\"idle ai-session-persist "
                + "threads pin the module classloader after disable/uninstall\") has regressed: " + body);
    }
}
