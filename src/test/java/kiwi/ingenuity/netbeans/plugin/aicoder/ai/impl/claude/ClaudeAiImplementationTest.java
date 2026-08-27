package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ClaudeAiImplementation#shouldThrottleUsageFetch} — the pure gate that decides whether a usage-endpoint
 * fetch attempt should be skipped because it's too soon after the previous one, given a learned minimum interval
 * derived from an earlier 429.
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
     * {@code applySessionSettings} runs on every OK of the session config dialog, whether or not the model was touched.
     * Claude's {@code setModel} recycles the CLI session unconditionally, so without this comparison every config save
     * threw away a warm session — and, if a turn was in flight, stranded it: the session was nulled while
     * {@code processing} stayed true, which is the state that makes Stop a silent no-op.
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
}
