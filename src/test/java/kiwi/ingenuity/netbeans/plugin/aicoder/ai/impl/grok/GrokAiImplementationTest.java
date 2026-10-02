package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@code GrokAiImplementation}'s session-lifecycle glue around the ACP-based
 * {@code GrokAiProcessManager}: model/reasoning-effort scoping (session-scoped changes never touch the global
 * default — {@link GrokPluginSettings}), {@code isStoredSessionValid}/{@code resumeSession} always trusting
 * the stored ACP session id (ACP's {@code session/load} falls back to {@code session/new} itself, so a stale
 * id never blocks the user — mirroring {@code OpenCodeAiImplementation}, not the old CLI's create-vs-resume
 * problem this test used to guard), the effective-reasoning-effort precedence (session wins over the global
 * default), and the callback path that clears a session-sourced effort the agent rejected — both in-memory
 * and in the persisted {@link GrokSessionSettings}.
 */
class GrokAiImplementationTest {

    private static kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener noopListener() {
        return (AiProcessEvent event) -> {
        };
    }

    private static ExecutablePrompter noopPrompter() {
        return (dialogTitle, executableName) -> CompletableFuture.completedFuture(null);
    }

    private static GrokAiImplementation implFor(AiSession session) {
        return new GrokAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };
    }

    private static AiSession newSession(String id, GrokSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.GROK, null, settings, Instant.now(), Instant.now());
    }

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

    private String originalUserHome;

    @TempDir
    Path tempHome;

    @BeforeEach
    void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void isStoredSessionValid_alwaysTrue_sessionLoadFallsBackToSessionNew() {
        // session/load is attempted first with fallback to session/new (GrokAiProcessManager.spawnAndHandshake),
        // so a stored session id is always safe to try resuming — mirrors OpenCodeAiImplementation.
        GrokAiImplementation impl = new GrokAiImplementation(noopListener(), noopPrompter());
        assertTrue(impl.isStoredSessionValid(UUID.randomUUID().toString()));
        assertTrue(impl.isStoredSessionValid("anything-at-all"));
    }

    @Test
    void setModel_updatesSessionSettings() {
        GrokSessionSettings settings = new GrokSessionSettings();
        GrokAiImplementation impl = implFor(newSession("grok-setmodel-1", settings));

        impl.setModel("grok-4");

        assertEquals("grok-4", settings.model(), "setModel must update the session settings");
    }

    @Test
    void setModel_doesNotChangeGrokPluginSettingsGlobalDefault() {
        String globalBefore = GrokPluginSettings.getModel();
        GrokSessionSettings settings = new GrokSessionSettings();
        GrokAiImplementation impl = implFor(newSession("grok-setmodel-2", settings));

        impl.setModel("grok-3");

        assertEquals(globalBefore, GrokPluginSettings.getModel(),
                "setModel must NOT write the global plugin default");
    }

    // ---- afterStart(): effective reasoning effort (session wins over global default) ----
    @Test
    void afterStart_appliesTheSessionsOwnReasoningEffort() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.6");
        settings.setReasoningEffort("high");
        GrokAiImplementation impl = implFor(newSession("grok-afterstart-1", settings));

        impl.afterStart();

        assertEquals("high", impl.delegate().reasoningEffort,
                "the session's own reasoning effort must win over any global default");
        assertTrue(impl.delegate().reasoningEffortFromSession);
    }

    @Test
    void afterStart_fallsBackToTheGlobalReasoningEffortWhenTheSessionHasNone() {
        String globalBefore = GrokPluginSettings.getReasoningEffort();
        try {
            GrokPluginSettings.setReasoningEffort("medium");
            GrokSessionSettings settings = new GrokSessionSettings();
            settings.setModel("grok-4.5");
            GrokAiImplementation impl = implFor(newSession("grok-afterstart-2", settings));

            impl.afterStart();

            assertEquals("medium", impl.delegate().reasoningEffort);
            assertFalse(impl.delegate().reasoningEffortFromSession);
        }
        finally {
            GrokPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    // ---- the manager clearing an unsupported value must also clear the PERSISTED source, not just its own
    // in-memory field, or the same INFO event recurs forever across restarts ----
    @Test
    void unsupportedReasoningEffortClearedAtSendTimeAlsoClearsThePersistedSessionSetting() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setModel("grok-4.5");
        settings.setReasoningEffort("xhigh");
        GrokAiImplementation impl = implFor(newSession("grok-clear-1", settings));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));

        impl.delegate().configureReasoningEffort("xhigh", true);
        impl.delegate().sessionConfigOptions = fakeConfigOptions("grok-4.5", "low", "medium", "high");
        impl.delegate().applyInitialConfigOptionsIfNeeded();

        assertNull(settings.reasoningEffort(),
                "a stored-unsupported value must end up null in the persisted session settings, not just the "
                + "in-memory field");
        assertEquals(settings, updated.get(), "the cleared settings must actually be persisted through the host");
    }

    /**
     * Builds a {@code configOptions} snapshot shaped like {@code session/new}'s response: a {@code model}
     * option whose current value already matches {@code model} (so the model branch never needs to send
     * anything), and a {@code reasoning_effort} option offering exactly {@code availableEfforts}.
     */
    private static com.google.gson.JsonArray fakeConfigOptions(String model, String... availableEfforts) {
        com.google.gson.JsonArray options = new com.google.gson.JsonArray();
        com.google.gson.JsonObject modelOpt = new com.google.gson.JsonObject();
        modelOpt.addProperty("id", "model");
        modelOpt.addProperty("currentValue", model);
        options.add(modelOpt);
        com.google.gson.JsonObject effortOpt = new com.google.gson.JsonObject();
        effortOpt.addProperty("id", "reasoning_effort");
        com.google.gson.JsonArray values = new com.google.gson.JsonArray();
        for (String effort : availableEfforts) {
            com.google.gson.JsonObject v = new com.google.gson.JsonObject();
            v.addProperty("value", effort);
            values.add(v);
        }
        effortOpt.add("options", values);
        options.add(effortOpt);
        return options;
    }

    @Test
    void clearInvalidPersistedReasoningEffort_clearsSessionScopedValue() {
        GrokSessionSettings settings = new GrokSessionSettings();
        settings.setReasoningEffort("xhigh");
        GrokAiImplementation impl = implFor(newSession("grok-clear-2", settings));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));

        impl.clearInvalidPersistedReasoningEffort();

        assertNull(settings.reasoningEffort());
        assertEquals(settings, updated.get());
    }

    // ---- only a SESSION-pinned value is ever cleared automatically. The global default belongs to the user
    // and to every other session/backend, so one session's model rejecting it must never touch the global
    // default. ----
    @Test
    void clearInvalidPersistedReasoningEffort_neverTouchesTheGlobalDefaultWhenSessionHasNoOverride() {
        String globalBefore = GrokPluginSettings.getReasoningEffort();
        GrokPluginSettings.setReasoningEffort("xhigh");
        try {
            GrokSessionSettings settings = new GrokSessionSettings();
            GrokAiImplementation impl = implFor(newSession("grok-clear-3", settings));

            impl.clearInvalidPersistedReasoningEffort();

            assertEquals("xhigh", GrokPluginSettings.getReasoningEffort(),
                    "with no session-level override, the global default must be left completely untouched — "
                    + "this callback only ever fires for the session-sourced case in the first place");
        }
        finally {
            GrokPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    // ---- session AND global set to DIFFERENT non-blank values at once — the case that would catch a
    // wrong-scope clearing bug ----
    @Test
    void sessionValueClearedButGlobalDefaultWithADifferentValueIsLeftUntouched() {
        String globalBefore = GrokPluginSettings.getReasoningEffort();
        GrokPluginSettings.setReasoningEffort("medium");
        try {
            GrokSessionSettings settings = new GrokSessionSettings();
            settings.setModel("grok-4.5");
            // xhigh is grok-4.6-only, so it is unsupported on this session's grok-4.5 model — but it is the
            // session's OWN value, so per rule 3a it wins over "medium" and is the one that must be cleared.
            settings.setReasoningEffort("xhigh");
            GrokAiImplementation impl = implFor(newSession("grok-clear-4", settings));
            AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
            impl.onStarted(stubHost(settings, updated));

            impl.afterStart();
            impl.delegate().sessionConfigOptions = fakeConfigOptions("grok-4.5", "low", "medium", "high");
            impl.delegate().applyInitialConfigOptionsIfNeeded();

            assertNull(settings.reasoningEffort(), "the session's own unsupported value must be cleared");
            assertEquals("medium", GrokPluginSettings.getReasoningEffort(),
                    "the global default must be left completely untouched, even though it is also set");
        }
        finally {
            GrokPluginSettings.setReasoningEffort(globalBefore);
        }
    }

    @Test
    void modelCatalogPublishUpdatesTheOpenBarsModelComboThroughAvailableModelsEvent() throws Exception {
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui.GrokAiInfoBarExtension bar
                                                                                      = new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui.GrokAiInfoBarExtension();
        CountDownLatch delivered = new CountDownLatch(1);
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener listener = event -> {
            // Mirrors AiTopComponent's own bus-listener forwarding: the bus dispatches off the EDT.
            javax.swing.SwingUtilities.invokeLater(() -> bar.onPropertyEvent(event));
            delivered.countDown();
        };
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus bus
                                                                    = kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus.getInstance();
        // AiModelCatalog.publish retains whatever it's given as the new cached snapshot indefinitely (there is
        // no per-test scope), so a later test's createInfoBarExtension replay — or a later bar in the same
        // JVM — would otherwise see this synthetic list. Restore the prior snapshot in finally.
        List<String> before = GrokAiImplementation.modelCatalog().getCachedModels();
        bus.addListener(AiTypeEnum.GROK, listener);
        try {
            List<String> discovered = List.of("grok-catalog-test-1", "grok-catalog-test-2");
            GrokAiImplementation.modelCatalog().publish(discovered);

            assertTrue(delivered.await(5, TimeUnit.SECONDS),
                    "the type-wide property bus must deliver the discovered list to every open Grok bar");
            javax.swing.SwingUtilities.invokeAndWait(() -> {
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
            bus.removeListener(AiTypeEnum.GROK, listener);
            GrokAiImplementation.modelCatalog().publish(before);
        }
    }
}
