package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PiAiProcessManager#buildLaunchCommand} — the {@code pi --mode rpc ...} argument list — against the spec's
 * *Process lifecycle* launch command: {@code --mode rpc --session-id <id> -e <extension path> [--model
 * <provider/id>] [--thinking <level>]}. Pure logic, no process spawned; see {@link PiAiProcessManagerStateTest} for the
 * threading/lifecycle behaviour that does need a live (fake) process.
 */
class PiAiProcessManagerTest {

    private PiAiProcessManager manager;

    @BeforeEach
    void setup() {
        manager = new PiAiProcessManager(event -> {
        });
        manager.setExecutablePathForTests("/usr/bin/pi");
    }

    @Test
    void baseCommandHasModeSessionIdAndExtensionFlag() {
        manager.setModel(null);
        manager.configureThinkingLevel(null);
        List<String> cmd = manager.buildLaunchCommand("sid-1", "/tmp/aicoder-pi-sid-1.ts");
        assertEquals(List.of("/usr/bin/pi", "--mode", "rpc", "--session-id", "sid-1", "-e", "/tmp/aicoder-pi-sid-1.ts"), cmd);
    }

    @Test
    void addsModelFlagWhenModelSet() {
        manager.setModel("github-copilot/claude-sonnet-5");
        List<String> cmd = manager.buildLaunchCommand("sid-1", "/tmp/ext.ts");
        int idx = cmd.indexOf("--model");
        assertTrue(idx >= 0, cmd.toString());
        assertEquals("github-copilot/claude-sonnet-5", cmd.get(idx + 1));
    }

    @Test
    void omitsModelFlagWhenModelBlank() {
        manager.setModel("");
        List<String> cmd = manager.buildLaunchCommand("sid-1", "/tmp/ext.ts");
        assertFalse(cmd.contains("--model"), cmd.toString());
    }

    @Test
    void omitsModelFlagWhenModelNull() {
        manager.setModel(null);
        List<String> cmd = manager.buildLaunchCommand("sid-1", "/tmp/ext.ts");
        assertFalse(cmd.contains("--model"), cmd.toString());
    }

    @Test
    void addsThinkingFlagWhenConfigured() {
        manager.configureThinkingLevel("high");
        List<String> cmd = manager.buildLaunchCommand("sid-1", "/tmp/ext.ts");
        int idx = cmd.indexOf("--thinking");
        assertTrue(idx >= 0, cmd.toString());
        assertEquals("high", cmd.get(idx + 1));
    }

    @Test
    void omitsThinkingFlagWhenNotConfigured() {
        manager.configureThinkingLevel(null);
        List<String> cmd = manager.buildLaunchCommand("sid-1", "/tmp/ext.ts");
        assertFalse(cmd.contains("--thinking"), cmd.toString());
    }

    @Test
    void configureThinkingLevelTreatsBlankAsUnset() {
        manager.configureThinkingLevel("   ");
        List<String> cmd = manager.buildLaunchCommand("sid-1", "/tmp/ext.ts");
        assertFalse(cmd.contains("--thinking"), cmd.toString());
    }

    @Test
    void extensionFlagAlwaysPresent() {
        List<String> cmd = manager.buildLaunchCommand("sid-1", "/tmp/ext.ts");
        int idx = cmd.indexOf("-e");
        assertTrue(idx >= 0, cmd.toString());
        assertEquals("/tmp/ext.ts", cmd.get(idx + 1));
    }

    @Test
    void sessionIdFlagUsesGivenId() {
        List<String> cmd = manager.buildLaunchCommand("some-pi-session-uuid", "/tmp/ext.ts");
        int idx = cmd.indexOf("--session-id");
        assertTrue(idx >= 0, cmd.toString());
        assertEquals("some-pi-session-uuid", cmd.get(idx + 1));
    }

    // ---- resolvePiSessionId (Review round 2, BigP_2: pins start()'s id-selection so a regression to
    // "always mint" — the exact bug Review A's fix corrected — cannot slip back in silently through
    // PiAiImplementation.afterStart()'s later resumeSession() call quietly papering over it). ----
    @Test
    void resolvePiSessionId_prefersStoredOverCurrentAndMinted() {
        assertEquals("stored-id", PiAiProcessManager.resolvePiSessionId("stored-id", "current-id"));
    }

    @Test
    void resolvePiSessionId_prefersStoredEvenWithNoCurrent() {
        assertEquals("stored-id", PiAiProcessManager.resolvePiSessionId("stored-id", null));
    }

    @Test
    void resolvePiSessionId_blankStoredFallsBackToCurrent() {
        assertEquals("current-id", PiAiProcessManager.resolvePiSessionId("   ", "current-id"));
        assertEquals("current-id", PiAiProcessManager.resolvePiSessionId(null, "current-id"));
    }

    @Test
    void resolvePiSessionId_mintsOnlyWhenNeitherStoredNorCurrentExist() {
        String minted = PiAiProcessManager.resolvePiSessionId(null, null);
        assertTrue(minted != null && !minted.isBlank(), "must mint a fresh id rather than return null/blank");

        String mintedAgain = PiAiProcessManager.resolvePiSessionId("", "  ");
        assertTrue(mintedAgain != null && !mintedAgain.isBlank(), "blank inputs never satisfy either preference, so this mints too");
        assertFalse(minted.equals(mintedAgain), "each mint is a fresh random id, never reused across calls");
    }
}
