package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GithubCopilotAiInfoBarExtension#onSessionSettingsChanged} re-syncing the reasoning-effort combo, the
 * asymmetry Boss's review flagged against {@code onModelChanged}'s existing handling — mirrors
 * {@code OllamaAiInfoBarExtension.onSessionSettingsChanged}'s session-or-global-default seeding rule.
 */
class GithubCopilotAiInfoBarExtensionTest {

    private static AiSession newSession(String id, GithubCopilotSessionSettings settings) {
        return new AiSession(id, "Test", null, AiTypeEnum.GitHubCoPilot, null, settings, Instant.now(), Instant.now());
    }

    @Test
    void onSessionSettingsChangedSyncsReasoningEffortComboWithoutNotifyingListeners() throws Exception {
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        GithubCopilotAiInfoBarExtension ext = new GithubCopilotAiInfoBarExtension(newSession("gh-ext-1", settings), null);
        // Seed the live per-model cache for whatever model the combo is ACTUALLY showing (queried via
        // getSelectedModel(), not assumed to equal GithubCopilotPluginSettings.getModel() — a fresh editable
        // JComboBox's getSelectedItem() and its editor's displayed text are not guaranteed to agree, the same
        // divergence setAvailableModels already works around).
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(ext.getSelectedModel(), List.of("low", "high")), Map.of());
        try {
            List<String> notified = new ArrayList<>();
            ext.addListener(new GithubCopilotInfoBarListener() {
                @Override
                public void onCompactRequested() {
                }

                @Override
                public void onModelChanged(String model) {
                }

                @Override
                public void onReasoningEffortChanged(String effort) {
                    notified.add(effort);
                }
            });

            settings.setReasoningEffort("high");
            ext.onSessionSettingsChanged(settings);
            // onSessionSettingsChanged's effort-sync routes through setSelectedReasoningEffort ->
            // refreshReasoningEffortOptions, which defers to the EDT via invokeLater when called off it (as this
            // test thread is) — flush the queue before asserting, or the getter reads pre-update state.
            SwingUtilities.invokeAndWait(() -> {
            });

            assertEquals("high", ext.getSelectedReasoningEffort());
            assertTrue(notified.isEmpty(), "syncing from settings must not notify listeners as if the user picked it");
        }
        finally {
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void onSessionSettingsChangedFallsBackToGlobalDefaultWhenSessionHasNoEffort() throws Exception {
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotPluginSettings.setReasoningEffort("medium");
        GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
        // settings.setReasoningEffort(...) never called — session carries no effort of its own.
        GithubCopilotAiInfoBarExtension ext = new GithubCopilotAiInfoBarExtension(newSession("gh-ext-2", settings), null);
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(ext.getSelectedModel(), List.of("low", "medium")), Map.of());
        try {
            ext.onSessionSettingsChanged(settings);
            // Flush the EDT: the effort-sync's refreshReasoningEffortOptions call defers via invokeLater when
            // called off it, as this test thread is.
            SwingUtilities.invokeAndWait(() -> {
            });

            assertEquals("medium", ext.getSelectedReasoningEffort(),
                         "with no session value, the combo must fall back to the global default rather than blanking");
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
            GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
        }
    }

    @Test
    void onSessionSettingsChangedShowsModelDefaultNotGlobalDefaultWhenModelSupportsNoEfforts() throws Exception {
        // Negative control for the test above: proves the §1.3 rule is genuinely enforced, not just coincidentally
        // satisfied. With no live discovery data for the current model, the combo must show "(model default)" —
        // i.e. null — even though a global default IS configured, never the global default itself. Flushed through
        // the same EDT wait as the positive case above: without it, this would pass even if the rule were NOT
        // enforced, since the queued-but-not-yet-run update also reads back as null.
        String globalBefore = GithubCopilotPluginSettings.getReasoningEffort();
        GithubCopilotPluginSettings.setReasoningEffort("medium");
        try {
            GithubCopilotSessionSettings settings = new GithubCopilotSessionSettings();
            GithubCopilotAiInfoBarExtension ext = new GithubCopilotAiInfoBarExtension(newSession("gh-ext-3", settings), null);
            // No setModelReasoningEffortInfo call — the per-model cache is empty for whatever model this combo is
            // showing, i.e. discovery has not (yet) reported support for anything.

            ext.onSessionSettingsChanged(settings);
            SwingUtilities.invokeAndWait(() -> {
            });

            assertNull(ext.getSelectedReasoningEffort(),
                       "with no live discovery data for the current model, the combo must show \"(model default)\" "
                       + "— never the global default, even though one is configured");
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(globalBefore);
        }
    }
}
