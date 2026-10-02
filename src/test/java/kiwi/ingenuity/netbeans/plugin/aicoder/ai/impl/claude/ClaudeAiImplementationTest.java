package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeAiProcessManagerStateTest.TestableClaudeAiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui.ClaudeAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ClaudeAiImplementation#shouldThrottleUsageFetch} (the pure gate that decides whether a
 * usage-endpoint fetch attempt should be skipped) plus the session model/effort wiring: model changes stay
 * session-scoped and never touch the global default, {@code createInfoBarExtension}'s effort combo persists a
 * user selection (and a clear) into the session settings while showing — but never writing back — the global
 * default for an unset session, and {@link ClaudeAiImplementation#effectiveEffort} makes the session value
 * beat the global one.
 */
class ClaudeAiImplementationTest {

    private static ClaudeAiImplementation implFor(AiSession session) {
        return new ClaudeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };
    }

    private static AiSession newSession(String id, ClaudeSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.CLAUDE, null, settings, Instant.now(), Instant.now());
    }

    /**
     * Mirrors {@code CodexAiImplementationTest.stubHost}: updateSessionSettings remembers what it was asked
     * to save so a test can assert not only that a change reached the session settings but that the host was
     * told to persist it. For the null-effort tests the same stub is used in reverse — proving the host was
     * NOT told to save anything.
     */
    private static AiSessionHost stubHost(AiSessionSettings settings, AtomicReference<AiSessionSettings> updated) {
        return new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return null;
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return settings;
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
                updated.set(newSettings);
            }
        };
    }

    @Test
    void noThrottleUntilAnIntervalHasBeenLearned() {
        assertFalse(ClaudeAiImplementation.shouldThrottleUsageFetch(1_000_000L, 999_999L, 0));
    }

    @Test
    void throttlesWhenLessThanLearnedIntervalHasElapsed() {
        assertTrue(ClaudeAiImplementation.shouldThrottleUsageFetch(100_000L, 99_000L, 5_000L));
    }

    @Test
    void doesNotThrottleOnceLearnedIntervalHasFullyElapsed() {
        assertFalse(ClaudeAiImplementation.shouldThrottleUsageFetch(110_000L, 100_000L, 5_000L));
    }

    @Test
    void doesNotThrottleExactlyAtTheBoundary() {
        // now - lastAttempt == learnedInterval: not strictly less than, so allowed through.
        assertFalse(ClaudeAiImplementation.shouldThrottleUsageFetch(105_000L, 100_000L, 5_000L));
    }

    @Test
    void setModel_updatesSessionSettings() {
        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        ClaudeAiImplementation impl = implFor(newSession("claude-setmodel-1", settings));

        impl.setModel("claude-sonnet-4.5");

        assertEquals("claude-sonnet-4.5", settings.model(), "setModel must update the session settings");
    }

    /**
     * {@code applySessionSettings} runs on every OK of the session config dialog, whether or not the model
     * was touched. Claude's {@code setModel} recycles the CLI session unconditionally, so without this
     * comparison every config save threw away a warm session — and, if a turn was in flight, stranded it: the
     * session was nulled while {@code processing} stayed true, which is the state that makes Stop a silent
     * no-op.
     *
     * <p>
     * Exercised through Claude, but the guard lives in {@code AiImplementation} and so covers every backend.
     */
    @Test
    void applySessionSettingsSkipsSetModelWhenTheModelIsUnchanged() {
        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        settings.setModel("claude-opus-5");
        AtomicInteger setModelCalls = new AtomicInteger();
        ClaudeAiImplementation impl = new ClaudeAiImplementation(e -> {
        }, null) {
            {
                currentSession = newSession("claude-apply-1", settings);
            }

            @Override
            public void setModel(String model) {
                setModelCalls.incrementAndGet();
                super.setModel(model);
            }
        };

        impl.applySessionSettings(settings);
        assertEquals(1, setModelCalls.get(),
                "the first apply genuinely changes the model and must go through");

        impl.applySessionSettings(settings);
        assertEquals(1, setModelCalls.get(),
                "re-applying the SAME model must not call setModel again — for Claude that recycles the CLI session");
    }

    @Test
    void setModel_doesNotChangeClaudePluginSettingsGlobalDefault() {
        String globalBefore = ClaudePluginSettings.getModel();
        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        ClaudeAiImplementation impl = implFor(newSession("claude-setmodel-2", settings));

        impl.setModel("claude-haiku-4.5");

        assertEquals(globalBefore, ClaudePluginSettings.getModel(),
                "setModel must NOT write the global plugin default");
    }

    // ---- createInfoBarExtension: the effort wiring [3] ----
    // These pin two defects: (1) the model/effort change listeners' persist block was dead code, so a change
    // survived only via an unrelated later save, and (2) a null-effort session had the GLOBAL default written
    // back into its own settings, destroying inherits-vs-pinned.
    // The Codex effort tests seed and read combos through SwingUtilities to keep
    // assertions deterministic (the extension marshals component mutations onto
    // the EDT), which these tests replicate.
    @Test
    void createInfoBarExtension_effortChangePersistsToSessionSettingsAndHost() throws Exception {
        String globalBefore = ClaudePluginSettings.getEffort();
        ClaudePluginSettings.setEffort("max");
        try {
            ClaudeSessionSettings settings = new ClaudeSessionSettings();
            AiSession session = newSession("claude-effort-persist-1", settings);
            AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
            ClaudeAiImplementation impl = implFor(session);

            AiInfoBarExtension ext = impl.createInfoBarExtension(session, stubHost(settings, updated));
            ClaudeAiInfoBarExtension claudeExt = (ClaudeAiInfoBarExtension) ext;
            JComboBox<?> effortCombo = (JComboBox<?>) claudeExt.createComponents().get(1);

            // The pre-existing model write-back during seeding calls the host once; clear the marker so the
            // assertion below proves the EFFORT change itself persisted.
            updated.set(null);

            SwingUtilities.invokeAndWait(() -> effortCombo.setSelectedItem("high"));

            assertEquals("high", settings.effort(), "an effort selection must reach the session settings");
            assertSame(settings, updated.get(), "host.updateSessionSettings() must be called so the choice is saved");
        }
        finally {
            ClaudePluginSettings.setEffort(globalBefore);
        }
    }

    @Test
    void createInfoBarExtension_effortChangeBackToDefaultClearsPersistedSessionEffort() throws Exception {
        String globalBefore = ClaudePluginSettings.getEffort();
        ClaudePluginSettings.setEffort("low");
        try {
            ClaudeSessionSettings settings = new ClaudeSessionSettings();
            settings.setEffort("high");
            AiSession session = newSession("claude-effort-clear-1", settings);
            AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
            ClaudeAiImplementation impl = implFor(session);

            AiInfoBarExtension ext = impl.createInfoBarExtension(session, stubHost(settings, updated));
            ClaudeAiInfoBarExtension claudeExt = (ClaudeAiInfoBarExtension) ext;
            JComboBox<?> effortCombo = (JComboBox<?>) claudeExt.createComponents().get(1);
            updated.set(null);

            SwingUtilities.invokeAndWait(() -> effortCombo.setSelectedItem(BlankSafeComboRenderer.DEFAULT_OPTION));

            assertNull(settings.effort(), "picking the default label must clear the session's stored effort");
            assertSame(settings, updated.get(), "the clear must be persisted, not left in memory");
        }
        finally {
            ClaudePluginSettings.setEffort(globalBefore);
        }
    }

    @Test
    void createInfoBarExtension_sessionWithoutEffort_showsGlobalButDoesNotWriteItBack() throws Exception {
        String globalBefore = ClaudePluginSettings.getEffort();
        ClaudePluginSettings.setEffort("max");
        try {
            ClaudeSessionSettings settings = new ClaudeSessionSettings();
            AiSession session = newSession("claude-effort-inherit-1", settings);
            AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
            ClaudeAiImplementation impl = implFor(session);

            AiInfoBarExtension ext = impl.createInfoBarExtension(session, stubHost(settings, updated));
            ClaudeAiInfoBarExtension claudeExt = (ClaudeAiInfoBarExtension) ext;
            updated.set(null);

            // Seeding and the fallback read-back are EDT-marshalled; assert the combo on the EDT so the seeded
            // fallback is what we actually observe, matching the Codex effort-combo idiom.
            AtomicReference<String> shown = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> shown.set(claudeExt.getSelectedEffort()));

            assertNull(settings.effort(), "a null-effort session must keep inheriting the global, not be pinned to it");
            assertNull(updated.get(), "seeding must NOT tell the host to save the global default into the session");
            assertEquals("max", shown.get(), "the combo still shows the global so the user sees what will launch");
        }
        finally {
            ClaudePluginSettings.setEffort(globalBefore);
        }
    }

    @Test
    void compactButtonWhilePendingDiffIsInfoOnlyWithoutBusyOrFailed() {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        AiSession session = newSession("claude-compact-pending-diff", settings);
        TestableClaudeAiProcessManager delegate = new TestableClaudeAiProcessManager(events::add);
        delegate.setupForTest();
        ClaudeAiImplementation impl = new ClaudeAiImplementation(events::add, null) {
            {
                currentSession = session;
            }

            @Override
            protected ClaudeAiProcessManager delegate() {
                return delegate;
            }
        };
        delegate.setPendingDiff(true);
        try {
            ClaudeAiInfoBarExtension bar = (ClaudeAiInfoBarExtension) impl.createInfoBarExtension(
                    session, stubHost(settings, new AtomicReference<>()));
            ((JButton) bar.createComponents().get(5)).doClick();

            assertEquals(1, events.stream().filter(StatusEvent.class::isInstance)
                    .filter(e -> ((StatusEvent) e).type() == StatusEventTypeEnum.INFO).count());
            assertFalse(events.stream().filter(StatusEvent.class::isInstance)
                    .map(StatusEvent.class::cast)
                    .anyMatch(e -> e.type() == StatusEventTypeEnum.BUSY
                                   || e.type() == StatusEventTypeEnum.FAILED));
            assertFalse(delegate.isProcessing(), "the refused Compact click must not start a turn");
            assertFalse(delegate.isWorkInFlight(), "the refused Compact click must not start runWork");
        }
        finally {
            delegate.stop();
        }
    }

    @Test
    void compactButtonWhileAwaitingCancelResultIsInfoOnlyWithoutBusyOrFailed() {
        CopyOnWriteArrayList<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        AiSession session = newSession("claude-compact-cancel-gate", settings);
        TestableClaudeAiProcessManager delegate = new TestableClaudeAiProcessManager(events::add);
        delegate.setupForTest();
        delegate.cancelWatchdogMillis = 600_000;
        ClaudeAiImplementation impl = new ClaudeAiImplementation(events::add, null) {
            {
                currentSession = session;
            }

            @Override
            protected ClaudeAiProcessManager delegate() {
                return delegate;
            }
        };
        try {
            delegate.sendPrompt("turn", null, List.of());
            delegate.interrupt(InterruptTypeEnum.Cancel);
            assertTrue(delegate.isAwaitingCancelResult());
            events.clear();

            ClaudeAiInfoBarExtension bar = (ClaudeAiInfoBarExtension) impl.createInfoBarExtension(
                    session, stubHost(settings, new AtomicReference<>()));
            ((JButton) bar.createComponents().get(5)).doClick();

            assertEquals(1, events.stream().filter(StatusEvent.class::isInstance)
                    .filter(e -> ((StatusEvent) e).type() == StatusEventTypeEnum.INFO).count());
            assertFalse(events.stream().filter(StatusEvent.class::isInstance)
                    .map(StatusEvent.class::cast)
                    .anyMatch(e -> e.type() == StatusEventTypeEnum.BUSY
                                   || e.type() == StatusEventTypeEnum.FAILED));
            assertFalse(delegate.isWorkInFlight(), "the refused Compact click must not start runWork");
        }
        finally {
            delegate.stop();
        }
    }

    // ---- effectiveEffort(): the session value wins over the global default ----
    @Test
    void effectiveEffort_sessionWinsOverGlobalDefault() {
        String globalBefore = ClaudePluginSettings.getEffort();
        ClaudePluginSettings.setEffort("max");
        try {
            ClaudeSessionSettings settings = new ClaudeSessionSettings();
            settings.setEffort("high");
            ClaudeAiImplementation impl = implFor(newSession("claude-effeff-1", settings));

            assertEquals("high", impl.effectiveEffort(),
                    "the session's own effort must win over the global default");
        }
        finally {
            ClaudePluginSettings.setEffort(globalBefore);
        }
    }

    @Test
    void effectiveEffort_globalDefaultUsedWhenSessionHasNone() {
        String globalBefore = ClaudePluginSettings.getEffort();
        ClaudePluginSettings.setEffort("max");
        try {
            ClaudeSessionSettings settings = new ClaudeSessionSettings();
            ClaudeAiImplementation impl = implFor(newSession("claude-effeff-2", settings));

            assertEquals("max", impl.effectiveEffort(),
                    "with no session effort, the global default is the effective value");
        }
        finally {
            ClaudePluginSettings.setEffort(globalBefore);
        }
    }

    @Test
    void effectiveEffort_nullWhenNeitherSessionNorGlobalIsSet() {
        String globalBefore = ClaudePluginSettings.getEffort();
        ClaudePluginSettings.setEffort("");
        try {
            ClaudeSessionSettings settings = new ClaudeSessionSettings();
            ClaudeAiImplementation impl = implFor(newSession("claude-effeff-3", settings));

            assertNull(impl.effectiveEffort(),
                    "no session and no global effort means the --effort flag is omitted entirely");
        }
        finally {
            ClaudePluginSettings.setEffort(globalBefore);
        }
    }

    @Test
    void effectiveEffort_blankSessionEffortFallsBackToGlobalDefault() {
        String globalBefore = ClaudePluginSettings.getEffort();
        ClaudePluginSettings.setEffort("xhigh");
        try {
            ClaudeSessionSettings settings = new ClaudeSessionSettings();
            settings.setEffort("   ");
            ClaudeAiImplementation impl = implFor(newSession("claude-effeff-4", settings));

            assertEquals("xhigh", impl.effectiveEffort(),
                    "a blank session effort must not hide the effective global value");
        }
        finally {
            ClaudePluginSettings.setEffort(globalBefore);
        }
    }

    @Test
    void modelCatalogPublishUpdatesTheOpenBarsModelComboThroughAvailableModelsEvent() throws Exception {
        ClaudeAiInfoBarExtension bar = new ClaudeAiInfoBarExtension();
        CountDownLatch delivered = new CountDownLatch(1);
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener listener = event -> {
            // Mirrors AiTopComponent's own bus-listener forwarding: the bus dispatches off the EDT.
            SwingUtilities.invokeLater(() -> bar.onPropertyEvent(event));
            delivered.countDown();
        };
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus bus
                                                                    = kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus.getInstance();
        // AiModelCatalog.publish retains whatever it's given as the new cached snapshot indefinitely (there is
        // no per-test scope), so a later test's createInfoBarExtension replay — or a later bar in the same
        // JVM — would otherwise see this synthetic list. Restore the prior snapshot in finally.
        List<String> before = ClaudeAiImplementation.modelCatalog().getCachedModels();
        bus.addListener(AiTypeEnum.CLAUDE, listener);
        try {
            List<String> discovered = List.of("claude-catalog-test-1", "claude-catalog-test-2");
            ClaudeAiImplementation.modelCatalog().publish(discovered);

            assertTrue(delivered.await(5, TimeUnit.SECONDS),
                    "the type-wide property bus must deliver the discovered list to every open Claude bar");
            SwingUtilities.invokeAndWait(() -> {
            });

            JComboBox<?> modelCombo = (JComboBox<?>) bar.createComponents().get(0);
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < modelCombo.getItemCount(); i++) {
                items.add(modelCombo.getItemAt(i));
            }
            assertEquals(discovered, items,
                    "publishing through the catalog — the only channel now — must repopulate the model combo");
        }
        finally {
            bus.removeListener(AiTypeEnum.CLAUDE, listener);
            ClaudeAiImplementation.modelCatalog().publish(before);
        }
    }
}
