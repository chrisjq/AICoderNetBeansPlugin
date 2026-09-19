package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session.PiPersistentSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * PiAiImplementation had zero test coverage. Mirrors ClaudeAiImplementationTest's shape (a currentSession-seeded
 * anonymous subclass, no real start()/MCP registration) and covers: afterStart()'s resumeSession +
 * effective-thinking-level wiring, onStarted()'s persistence of a freshly minted pi session id, isStoredSessionValid(),
 * and compact()'s running guard.
 */
class PiAiImplementationTest {

    private static PiAiImplementation implFor(AiSession session) {
        return implFor(session, e -> {
               });
    }

    private static PiAiImplementation implFor(AiSession session, AiProcessEventListener listener) {
        return new PiAiImplementation(listener, null) {
            {
                currentSession = session;
            }
        };
    }

    private static AiSession newSession(String id, PiSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.PI, null, settings, Instant.now(), Instant.now());
    }

    // ---- getCurrentModel(): the reopen path's model resolution (Boss's pre-live-pass check). startWithDiscovery(null)
    // — the only caller on the reopen path, from AiTopComponent — resolves effectiveModel via this exact method before
    // calling start(execPath, effectiveModel), so this is the one untested link in "does a resumed session relaunch
    // with its stored model": start()'s own `this.model = model` assignment is a single unconditional line at the top
    // of PiAiProcessManager.start() (before any validation/MCP/version-check work), already exercised generically by
    // PiAiProcessManagerTest's buildLaunchCommand tests, and startWithDiscovery itself cannot be driven directly here
    // without either a real executable on PATH or triggering start()'s real MCP registration/`pi --version` subprocess
    // — disproportionate for pinning a resolution rule this simple. ----
    @Test
    void getCurrentModel_returnsTheSessionsStoredModelWhenPresent() {
        String globalBefore = PiPluginSettings.getModel();
        try {
            PiPluginSettings.setModel("github-copilot/gpt-5-mini"); // deliberately different from the session's own
            PiSessionSettings settings = new PiSessionSettings();
            settings.setModel("anthropic/claude-sonnet-4-6");
            PiAiImplementation impl = implFor(newSession("pi-model-1", settings));

            assertEquals("anthropic/claude-sonnet-4-6", impl.getCurrentModel(),
                         "a reopened session with its own stored model must win over the global default, not fall back to it");
        }
        finally {
            PiPluginSettings.setModel(globalBefore);
        }
    }

    @Test
    void getCurrentModel_fallsBackToTheGlobalDefaultWhenTheSessionHasNoModel() {
        String globalBefore = PiPluginSettings.getModel();
        try {
            PiPluginSettings.setModel("github-copilot/gpt-5-mini");
            PiAiImplementation impl = implFor(newSession("pi-model-2", new PiSessionSettings()));

            assertEquals("github-copilot/gpt-5-mini", impl.getCurrentModel(),
                         "only a session with no stored model at all should fall back to the global default");
        }
        finally {
            PiPluginSettings.setModel(globalBefore);
        }
    }

    // ---- afterStart(): resumeSession from a stored piSessionId + effective thinking level ----
    @Test
    void afterStart_resumesFromStoredPiSessionIdAndAppliesItsThinkingLevel() {
        PiSessionSettings settings = new PiSessionSettings();
        settings.setPiSessionId("stored-pi-id");
        settings.setThinkingLevel("high");
        PiAiImplementation impl = implFor(newSession("pi-afterstart-1", settings));

        impl.afterStart();

        assertEquals("stored-pi-id", impl.delegate().getPiSessionId(),
                     "afterStart() must call resumeSession() with the settings' own stored id");
        List<String> cmd = impl.delegate().buildLaunchCommand("sid", "/tmp/ext.ts");
        int idx = cmd.indexOf("--thinking");
        assertTrue(idx >= 0, cmd.toString());
        assertEquals("high", cmd.get(idx + 1), "the session's own thinking level must win over any global default");
    }

    @Test
    void afterStart_fallsBackToTheGlobalThinkingLevelWhenTheSessionHasNone() {
        String globalBefore = PiPluginSettings.getThinkingLevel();
        try {
            PiPluginSettings.setThinkingLevel("medium");
            PiAiImplementation impl = implFor(newSession("pi-afterstart-2", new PiSessionSettings()));

            impl.afterStart();

            List<String> cmd = impl.delegate().buildLaunchCommand("sid", "/tmp/ext.ts");
            int idx = cmd.indexOf("--thinking");
            assertTrue(idx >= 0, cmd.toString());
            assertEquals("medium", cmd.get(idx + 1));
        }
        finally {
            PiPluginSettings.setThinkingLevel(globalBefore);
        }
    }

    @Test
    void afterStart_doesNotResumeWhenNoPiSessionIdIsStored() {
        PiAiImplementation impl = implFor(newSession("pi-afterstart-3", new PiSessionSettings()));

        impl.afterStart();

        assertNull(impl.delegate().getPiSessionId(), "nothing stored means nothing to resume from");
    }

    // ---- onStarted(): persist a freshly minted pi session id back into the session's settings ----
    @Test
    void onStarted_persistsAFreshlyMintedPiSessionIdBackIntoSettings() {
        PiSessionSettings settings = new PiSessionSettings();
        PiAiImplementation impl = implFor(newSession("pi-onstarted-1", settings));
        // Simulates what start() resolves piSessionId to when nothing was stored yet — see PiAiProcessManager's own
        // resolvePiSessionId(), pinned separately in PiAiProcessManagerTest.
        impl.delegate().resumeSession("freshly-minted-id");
        FakeHost host = new FakeHost();

        impl.onStarted(host);

        assertEquals("freshly-minted-id", settings.piSessionId());
        assertEquals(1, host.updateCalls);
    }

    @Test
    void onStarted_isANoOpWhenSettingsAlreadyMatchTheLiveId() {
        PiSessionSettings settings = new PiSessionSettings();
        settings.setPiSessionId("already-current");
        PiAiImplementation impl = implFor(newSession("pi-onstarted-2", settings));
        impl.delegate().resumeSession("already-current");
        FakeHost host = new FakeHost();

        impl.onStarted(host);

        assertEquals(0, host.updateCalls, "no update needed when the stored id already matches the live one");
    }

    // ---- onStarted(): pushes the version check into the info bar ----
    @Test
    void onStarted_pushesTheVersionCheckIntoTheInfoBarAndShowsTheWarningButtonForAnUntestedVersion() throws Exception {
        AiSession session = newSession("pi-version-1", new PiSessionSettings());
        PiAiImplementation impl = implFor(session);
        FakeHost host = new FakeHost();
        AiInfoBarExtension bar = impl.createInfoBarExtension(session, host);
        JButton versionBtn = (JButton) bar.createComponents().get(3);
        assertFalse(versionBtn.isVisible(), "no version check yet at construction time — the button must start hidden");

        impl.delegate().setVersionCheckForTests(new PiVersionCheck("0.1.0")); // untested major.minor (tested: 0.85)
        SwingUtilities.invokeAndWait(() -> impl.onStarted(host));

        assertTrue(versionBtn.isVisible(),
                   "onStarted must hand the version check to the info bar it built, or the warning button can never appear");
    }

    // ---- isStoredSessionValid(): pi's --session-id creates-or-resumes transparently, so always true ----
    @Test
    void isStoredSessionValid_alwaysTrue() {
        PiAiImplementation impl = implFor(newSession("pi-valid-1", new PiSessionSettings()));

        assertTrue(impl.isStoredSessionValid("any-id"));
        assertTrue(impl.isStoredSessionValid(null));
    }

    // ---- compact()'s running guard, exercised through the info bar's real Compact button ----
    @Test
    void compact_refusesWhileNotRunningWithoutEverSuppressingTheTurn() {
        List<AiProcessEvent> events = new ArrayList<>();
        AiSession session = newSession("pi-compact-1", new PiSessionSettings());
        PiAiImplementation impl = implFor(session, events::add);
        FakeHost host = new FakeHost();

        AiInfoBarExtension bar = impl.createInfoBarExtension(session, host);
        clickCompact(bar);

        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                && se.type() == StatusEventTypeEnum.INFO && se.text().contains("Wait for pi to finish")),
                   "must refuse with an INFO notice while delegate().isRunning() is false");
        assertEquals(0, host.suppressCalls, "the guard must refuse before ever suppressing the turn");
    }

    // ---- compact()'s accept/reject paths, once past the running guard: the fix's own precise behaviour —
    // suppress on confirmed acceptance, never on failure — had no test driving a real accept. ----
    @Test
    void compact_acceptedPathSuppressesExactlyOnce() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiSession session = newSession("pi-compact-2", new PiSessionSettings());
        PiAiImplementation impl = implFor(session, events::add);
        impl.delegate().setRunningForTests(true);
        PiPersistentSession fakeSession = PiPersistentSession.launch(
                List.of("sh", "-c", SUCCESS_ECHO_SCRIPT), null, line -> {
        }, line -> {
        });
        impl.delegate().persistentSession = fakeSession;
        FakeHost host = new FakeHost();

        try {
            AiInfoBarExtension bar = impl.createInfoBarExtension(session, host);
            clickCompact(bar);

            awaitTrue(() -> host.suppressCalls > 0, "suppressNextTurn to be called after pi accepts the compact");
            assertEquals(1, host.suppressCalls);
            assertFalse(events.stream().anyMatch(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.FAILED),
                        "an accepted compact must never surface FAILED");
        }
        finally {
            fakeSession.close();
        }
    }

    @Test
    void compact_rejectedPathNeverSuppressesAndSurfacesFailed() throws Exception {
        List<AiProcessEvent> events = new ArrayList<>();
        AiSession session = newSession("pi-compact-3", new PiSessionSettings());
        PiAiImplementation impl = implFor(session, events::add);
        impl.delegate().setRunningForTests(true);
        PiPersistentSession fakeSession = PiPersistentSession.launch(
                List.of("sh", "-c", FAILURE_ECHO_SCRIPT), null, line -> {
        }, line -> {
        });
        impl.delegate().persistentSession = fakeSession;
        FakeHost host = new FakeHost();

        try {
            AiInfoBarExtension bar = impl.createInfoBarExtension(session, host);
            clickCompact(bar);

            awaitTrue(() -> events.stream().anyMatch(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.FAILED),
                      "a rejected compact must surface FAILED");
            assertEquals(0, host.suppressCalls, "a rejected compact must never suppress the next turn");
        }
        finally {
            fakeSession.close();
        }
    }

    // pi has no generic "command" command — the command name IS the request's own "type" (e.g. "compact"), so the
    // echo must match that variable name, not a literal "command" (the plugin was sending
    // {type:"command", command:"prompt", ...} when it must send {type:"prompt", ...}).
    private static final String SUCCESS_ECHO_SCRIPT = """
            while IFS= read -r line; do
                printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":true/'
            done
            """;

    private static final String FAILURE_ECHO_SCRIPT = """
            while IFS= read -r line; do
                printf '%s\\n' "$line" | sed 's/"type":"[a-z_]*"/"type":"response","success":false,"error":"nothing to compact"/'
            done
            """;

    private static void awaitTrue(java.util.function.BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    private static void clickCompact(AiInfoBarExtension bar) {
        JButton compactBtn = (JButton) bar.createComponents().get(4);
        for (ActionListener l : compactBtn.getActionListeners()) {
            l.actionPerformed(new ActionEvent(compactBtn, ActionEvent.ACTION_PERFORMED, "compact"));
        }
    }

    private static final class FakeHost implements AiSessionHost {

        int suppressCalls = 0;
        int updateCalls = 0;

        @Override
        public File resolveWorkDir() {
            return null;
        }

        @Override
        public void suppressNextTurn(String statusMessage, String completionMessage) {
            suppressCalls++;
        }

        @Override
        public AiSessionSettings getSessionSettings() {
            return null;
        }

        @Override
        public void updateSessionSettings(AiSessionSettings newSettings) {
            updateCalls++;
        }
    }
}
