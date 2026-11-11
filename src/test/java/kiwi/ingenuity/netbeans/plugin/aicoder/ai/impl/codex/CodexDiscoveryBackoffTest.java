package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CodexDiscoveryBackoffTest {

    private List<String> catalogBefore;

    @BeforeEach
    void isolate() {
        CodexPluginSettings.useForTests(MemoryPreferences.create());
        catalogBefore = CodexAiImplementation.modelCatalog().getCachedModels();
    }

    @AfterEach
    void resetBackoff() {
        CodexAiImplementation.clearDiscoveryBackoff();
        AiModelCatalog catalog = CodexAiImplementation.modelCatalog();
        catalog.publish(catalogBefore);
        catalog.invalidate();
        CodexPluginSettings.useForTests(null);
    }

    @Test
    @Timeout(5)
    void aFailureStartsTheBackoffAndASuccessEndsIt() {
        CodexAiImplementation.recordDiscoveryFailure();
        assertTrue(CodexAiImplementation.discoveryBackingOff(), "a failed discovery must back off");

        CodexAiImplementation.clearDiscoveryBackoff();
        assertFalse(CodexAiImplementation.discoveryBackingOff(), "a successful discovery must reset the backoff");
    }

    @Test
    @Timeout(5)
    void theBackoffLastsSixtySecondsAndThenExpires() {
        CodexAiImplementation.recordDiscoveryFailure();

        CodexAiImplementation.backdateDiscoveryFailureForTests(59_000L);
        assertTrue(CodexAiImplementation.discoveryBackingOff(), "inside the window the next trigger is skipped");

        CodexAiImplementation.backdateDiscoveryFailureForTests(61_000L);
        assertFalse(CodexAiImplementation.discoveryBackingOff(), "after the window a new discovery may run");
    }

    @Test
    @Timeout(5)
    void noRecordedFailureMeansNoBackoff() {
        CodexAiImplementation.clearDiscoveryBackoff();
        assertFalse(CodexAiImplementation.discoveryBackingOff());
    }

    @Test
    @Timeout(5)
    void triggerDoesNotStartDiscoveryWhileBackingOff() {
        CodexAiImplementation.recordDiscoveryFailure();

        CodexAiImplementation.triggerModelDiscovery();

        assertFalse(CodexModelDiscovery.discoveryInProgressForTests(),
                    "a trigger inside the backoff window must not start a discovery");
        assertTrue(CodexAiImplementation.discoveryBackingOff(), "the skipped trigger leaves the backoff in place");
    }

    @Test
    @Timeout(5)
    void aCompleteListFromDiscoveryClearsTheBackoff() {
        CodexAiImplementation.recordDiscoveryFailure();

        CodexAiImplementation.applyDiscoveryResult(List.of("gpt-backoff-test"));

        assertFalse(CodexAiImplementation.discoveryBackingOff(), "a successful discovery must end the backoff");
        assertEquals(List.of("gpt-backoff-test"), CodexPluginSettings.getDiscoveredModels());
    }

    @Test
    @Timeout(5)
    void anEmptyResultFromDiscoveryStartsTheBackoff() {
        CodexAiImplementation.clearDiscoveryBackoff();

        CodexAiImplementation.applyDiscoveryResult(List.of());

        assertTrue(CodexAiImplementation.discoveryBackingOff(), "an empty discovery must back off");
    }
}
