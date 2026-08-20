package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.io.File;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotQuotaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Mirrors CodexAiImplementationTest and OpenCodeAiImplementationTest — the same
 * bug class those two were fixed for: a session's chosen model must be used at
 * startup rather than silently falling back to the global default.
 */
class GithubCopilotAiImplementationTest {

    private static AiSession newSession(String id, GithubCopilotSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.GitHubCoPilot, null, settings, Instant.now(), Instant.now());
    }

    private static GithubCopilotAiImplementation implFor(AiSession session) {
        return new GithubCopilotAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }
        };
    }

    private static AiSessionHost stubHost(AiSessionSettings settings, AtomicReference<AiSessionSettings> updated) {
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
                updated.set(newSettings);
            }
        };
    }

    @Test
    void publishQuota_broadcastsToEveryCopilotListener() throws Exception {
        CountDownLatch delivered = new CountDownLatch(2);
        GithubCopilotQuotaEvent quota = new GithubCopilotQuotaEvent(
                false, 12, 300, 96.0, "2026-09-01T00:00:00Z", false);
        AiPropertyListener first = event -> {
            assertEquals(quota, event);
            delivered.countDown();
        };
        AiPropertyListener second = event -> {
            assertEquals(quota, event);
            delivered.countDown();
        };
        AiTypePropertyBus bus = AiTypePropertyBus.getInstance();
        bus.addListener(AiTypeEnum.GitHubCoPilot, first);
        bus.addListener(AiTypeEnum.GitHubCoPilot, second);
        try {
            GithubCopilotAiImplementation.publishQuota(quota);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
        finally {
            bus.removeListener(AiTypeEnum.GitHubCoPilot, first);
            bus.removeListener(AiTypeEnum.GitHubCoPilot, second);
        }
    }

    // Tests the extracted helper directly rather than startWithDiscovery(), whose
    // other branch depends on GithubCopilotExecutableLocator.locate() finding a
    // real CLI on disk — environment-dependent, not something a unit test assumes.
    @Test
    void resolveStartupModel_usesSessionModelNotGlobalDefault() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setModel("claude-sonnet-4.5");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-swd-1", settings));

        assertEquals("claude-sonnet-4.5", impl.resolveStartupModel(null),
                "startWithDiscovery(null) must fall back to the session's chosen model, not "
                + "GithubCopilotPluginSettings.getModel() — this is the per-session-model bug");
    }

    @Test
    void resolveStartupModel_explicitArgumentWinsOverSessionModel() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setModel("claude-sonnet-4.5");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-swd-2", settings));

        assertEquals("gpt-5.4", impl.resolveStartupModel("gpt-5.4"),
                "an explicit model argument must still win over the session setting");
    }

    @Test
    void resolveStartupModel_noSessionModelFallsBackToGlobalDefault() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        // settings.setModel(...) never called.
        GithubCopilotAiImplementation impl = implFor(newSession("gh-swd-3", settings));

        assertEquals(GithubCopilotPluginSettings.getModel(), impl.resolveStartupModel(null),
                "with no session model and no explicit argument, the global default is still correct");
    }

    @Test
    void resolveStartupModel_blankSessionModelFallsBackToGlobalDefault() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        settings.setModel("   ");
        GithubCopilotAiImplementation impl = implFor(newSession("gh-swd-4", settings));

        assertEquals(GithubCopilotPluginSettings.getModel(), impl.resolveStartupModel(null),
                "a blank (not null) session model must not be treated as a real choice");
    }

    @Test
    void resolveStartupModel_withNoCurrentSessionFallsBackToGlobalDefault() {
        GithubCopilotAiImplementation impl = new GithubCopilotAiImplementation(e -> {
        }, null);

        assertEquals(GithubCopilotPluginSettings.getModel(), impl.resolveStartupModel(null),
                "no session at all must not throw — the global default applies");
    }

    @Test
    void setModel_updatesSessionSettings() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();

        implFor(newSession("gh-setmodel-1", settings)).setModel("gpt-5.4");

        assertEquals("gpt-5.4", settings.model(), "setModel must update the session settings");
    }

    @Test
    void setModel_doesNotChangeGithubCopilotPluginSettingsGlobalDefault() {
        String globalBefore = GithubCopilotPluginSettings.getModel();
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();

        implFor(newSession("gh-setmodel-2", settings)).setModel("claude-opus-4.6");

        assertEquals(globalBefore, GithubCopilotPluginSettings.getModel(),
                "setModel must NOT write the global plugin default");
    }

    @Test
    void applyModelFallback_updatesAndPersistsSessionSettings() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = implFor(newSession("gh-fallback-1", settings));
        AtomicReference<AiSessionSettings> updated = new AtomicReference<>();
        impl.onStarted(stubHost(settings, updated));

        impl.applyModelFallback("auto");

        assertEquals("auto", settings.model(), "fallback must update the session model");
        assertEquals(settings, updated.get(), "fallback must persist the updated settings");
    }

    @Test
    void applyModelFallback_withoutHostOrInfoBarDoesNotThrow() {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiImplementation impl = implFor(newSession("gh-fallback-2", settings));

        assertDoesNotThrow(() -> impl.applyModelFallback("auto"));
        assertEquals("auto", settings.model(), "fallback must still update the session model");
    }

}
