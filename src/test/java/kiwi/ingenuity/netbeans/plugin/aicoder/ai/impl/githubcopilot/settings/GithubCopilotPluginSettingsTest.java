package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Covers the live per-model reasoning-effort cache that {@code GithubCopilotModelDiscovery}'s SDK tier populates and
 * every reasoning-effort combo (info bar, create dialog, Options tab) reads from — never a hardcoded list. Reset in
 * {@link #resetCache()} since the cache is a static, in-memory, non-persisted field shared across the whole test JVM.
 */
class GithubCopilotPluginSettingsTest {

    @AfterEach
    void resetCache() {
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(Map.of(), Map.of());
    }

    @Test
    void getSupportedReasoningEfforts_unknownModel_returnsEmpty() {
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                Map.of("gpt-5.4", List.of("low", "high")), Map.of());

        assertTrue(GithubCopilotPluginSettings.getSupportedReasoningEfforts("some-other-model").isEmpty());
    }

    @Test
    void getSupportedReasoningEfforts_nullModel_returnsEmpty() {
        assertTrue(GithubCopilotPluginSettings.getSupportedReasoningEfforts(null).isEmpty());
    }

    @Test
    void getSupportedReasoningEfforts_beforeAnyDiscovery_returnsEmpty() {
        assertTrue(GithubCopilotPluginSettings.getSupportedReasoningEfforts("gpt-5.4").isEmpty());
    }

    @Test
    void getSupportedReasoningEfforts_knownModel_returnsExactDiscoveredList() {
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                Map.of("gpt-5.4", List.of("low", "medium", "high")), Map.of());

        assertEquals(List.of("low", "medium", "high"), GithubCopilotPluginSettings.getSupportedReasoningEfforts("gpt-5.4"));
    }

    @Test
    void getDefaultReasoningEffort_unknownModel_returnsNull() {
        assertNull(GithubCopilotPluginSettings.getDefaultReasoningEffort("some-other-model"));
    }

    @Test
    void getDefaultReasoningEffort_knownModel_returnsDiscoveredDefault() {
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                Map.of(), Map.of("gpt-5.4", "medium"));

        assertEquals("medium", GithubCopilotPluginSettings.getDefaultReasoningEffort("gpt-5.4"));
    }

    @Test
    void setModelReasoningEffortInfo_replacesRatherThanMerges() {
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                Map.of("gpt-5.4", List.of("low")), Map.of("gpt-5.4", "low"));

        GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                Map.of("claude-sonnet-4.5", List.of("high")), Map.of("claude-sonnet-4.5", "high"));

        assertTrue(GithubCopilotPluginSettings.getSupportedReasoningEfforts("gpt-5.4").isEmpty(),
                   "a fresh discovery cycle must replace the whole cache, not accumulate stale models");
        assertEquals(List.of("high"), GithubCopilotPluginSettings.getSupportedReasoningEfforts("claude-sonnet-4.5"));
    }

    @Test
    void setModelReasoningEffortInfo_null_clearsCache() {
        GithubCopilotPluginSettings.setModelReasoningEffortInfo(
                Map.of("gpt-5.4", List.of("low")), Map.of("gpt-5.4", "low"));

        GithubCopilotPluginSettings.setModelReasoningEffortInfo(null, null);

        assertTrue(GithubCopilotPluginSettings.getSupportedReasoningEfforts("gpt-5.4").isEmpty());
        assertNull(GithubCopilotPluginSettings.getDefaultReasoningEffort("gpt-5.4"));
    }

    @Test
    void reasoningEffortGlobalDefault_unsetIsEmptyString_notNull() {
        String before = GithubCopilotPluginSettings.getReasoningEffort();
        try {
            GithubCopilotPluginSettings.setReasoningEffort(null);
            assertEquals("", GithubCopilotPluginSettings.getReasoningEffort(),
                         "Preferences cannot store null, so the sentinel for \"not set\" must be \"\"");
        }
        finally {
            GithubCopilotPluginSettings.setReasoningEffort(before);
        }
    }
}
