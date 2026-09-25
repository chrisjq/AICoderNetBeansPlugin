package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import java.io.File;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui.OllamaAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/**
 * Mirrors CodexAiImplementationTest and OpenCodeAiImplementationTest: a model picked for one session must
 * reach that session's own settings and must not touch the global Tools &gt; Options default, which is owned
 * solely by OllamaAiSettingsTab.
 */
class OllamaAiImplementationTest {

    /**
     * Port 1 refuses immediately, so discovery fails fast instead of reaching a live Ollama.
     */
    private static final String UNREACHABLE_BASE_URL = "http://127.0.0.1:1";

    /**
     * Never leave the base URL unset here, per the standing "never touch the live server" rule.
     * {@code createInfoBarExtension} calls both {@code triggerModelDiscovery()} and
     * {@code triggerCapabilityDiscovery()} (OllamaAiImplementation:191-192), each resolving through
     * {@code resolveBaseUrl()} — which falls back to {@code OllamaPluginSettings.getBaseUrl()},
     * http://localhost:11434, when the session has none. That produced real GET /v1/models, GET /api/tags and
     * a burst of POST /api/show against whatever Ollama the developer was running, several 404ing on model
     * names this test invented.
     */
    private static OllamaSessionSettings newSettings() {
        OllamaSessionSettings settings = new OllamaSessionSettings();
        settings.setBaseUrl(UNREACHABLE_BASE_URL);
        return settings;
    }

    private static AiSession newSession(String id, OllamaSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.OLLAMA_LOCAL, null, settings, Instant.now(), Instant.now());
    }

    private static OllamaAiImplementation implFor(AiSession session) {
        return new OllamaAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };
    }

    private static AiSessionHost fakeHost(OllamaSessionSettings settings) {
        return new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return null;
            }

            @Override
            public void suppressNextTurn(String statusMessage, String completionMessage) {
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return settings;
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
            }
        };
    }

    @Test
    void setModel_updatesSessionSettings() {
        OllamaSessionSettings settings = newSettings();
        OllamaAiImplementation impl = implFor(newSession("ollama-setmodel-1", settings));

        impl.setModel("llama3.3");

        assertEquals("llama3.3", settings.model(), "setModel must update the session settings");
    }

    @Test
    void setModel_doesNotChangeOllamaPluginSettingsGlobalDefault() {
        String globalBefore = OllamaPluginSettings.getModel();
        OllamaSessionSettings settings = newSettings();
        OllamaAiImplementation impl = implFor(newSession("ollama-setmodel-2", settings));

        impl.setModel("qwen3");

        assertEquals(globalBefore, OllamaPluginSettings.getModel(),
                "setModel must NOT write the global plugin default — picking a model for one "
                + "session would otherwise change Tools > Options for every other session");
    }

    @Test
    void setModel_withNoCurrentSessionDoesNotThrow() {
        String globalBefore = OllamaPluginSettings.getModel();
        OllamaAiImplementation impl = new OllamaAiImplementation(e -> {
        }, null);

        impl.setModel("mistral");

        assertEquals(globalBefore, OllamaPluginSettings.getModel(),
                "no session at all must neither throw nor fall back to writing the global default");
    }

    @Test
    void createInfoBarExtension_seedsReasoningEffortFromSessionWhenPinned() throws Exception {
        OllamaSessionSettings settings = newSettings();
        settings.setReasoningEffort("high");
        AiSession session = newSession("ollama-effort-1", settings);
        OllamaAiImplementation impl = implFor(session);

        // createInfoBarExtension seeds the combo via OllamaAiInfoBarExtension.setSelectedReasoningEffort, which
        // defers to SwingUtilities.invokeLater when called off the EDT (as it is here) — running the whole call ON
        // the EDT and awaiting it is what makes the assertion below prove something instead of racing that
        // still-queued task.
        OllamaAiInfoBarExtension[] extHolder = new OllamaAiInfoBarExtension[1];
        SwingUtilities.invokeAndWait(() -> extHolder[0] = impl.createInfoBarExtension(session, fakeHost(settings)));

        assertEquals("high", extHolder[0].getSelectedReasoningEffort());
    }

    @Test
    void createInfoBarExtension_seedsReasoningEffortFromGlobalDefaultWhenSessionIsUnset() throws Exception {
        String before = OllamaPluginSettings.getReasoningEffort();
        try {
            OllamaPluginSettings.setReasoningEffort("medium");
            OllamaSessionSettings settings = newSettings();
            AiSession session = newSession("ollama-effort-2", settings);
            OllamaAiImplementation impl = implFor(session);

            OllamaAiInfoBarExtension[] extHolder = new OllamaAiInfoBarExtension[1];
            SwingUtilities.invokeAndWait(() -> extHolder[0] = impl.createInfoBarExtension(session, fakeHost(settings)));
            OllamaAiInfoBarExtension ext = extHolder[0];

            assertEquals("medium", ext.getSelectedReasoningEffort(),
                    "must display the global default rather than \"(model default)\" when one is set");
            assertNull(settings.reasoningEffort(),
                    "seeding for display must never write the global fallback back into the session's own settings");
        } finally {
            OllamaPluginSettings.setReasoningEffort(before);
        }
    }

    /**
     * Wired to {@code OllamaAiProcessManager.setOnReasoningEffortCleared}: fires only for a SESSION-sourced
     * value, so this method itself needs no scope resolution — it must simply clear whatever the session
     * currently has pinned and persist that through the host.
     */
    @Test
    void clearInvalidPersistedReasoningEffort_clearsSessionScopedValueAndPersistsThroughTheHost() {
        OllamaSessionSettings settings = newSettings();
        settings.setReasoningEffort("high");
        AiSession session = newSession("ollama-clear-1", settings);
        OllamaAiImplementation impl = implFor(session);
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return null;
            }

            @Override
            public void suppressNextTurn(String statusMessage, String completionMessage) {
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return settings;
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
                updated.set(newSettings);
            }
        });

        impl.clearInvalidPersistedReasoningEffort();

        assertNull(settings.reasoningEffort());
        assertEquals(settings, updated.get(), "the cleared settings must actually be persisted through the host");
    }
}
