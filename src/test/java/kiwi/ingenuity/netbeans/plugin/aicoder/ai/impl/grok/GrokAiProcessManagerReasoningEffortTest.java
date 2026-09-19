package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link GrokAiProcessManager#buildReasoningEffortArgs} — the {@code --reasoning-effort} launch-arg logic: unset
 * omits the flag, set+supported passes the exact value, set+unsupported clears the stored value and fires exactly one
 * INFO event without passing anything. No process is spawned; mirrors {@code PiAiProcessManagerTest}'s pure-logic
 * launch-arg tests. Restart-to-apply and live-list-populated-combo behaviour do not apply to Grok: it spawns a fresh
 * process per turn (no restart concept) and has no live-discovered effort list (a static table instead).
 */
class GrokAiProcessManagerReasoningEffortTest {

    private final List<AiProcessEvent> events = new ArrayList<>();
    private GrokAiProcessManager manager;

    @BeforeEach
    void setup() {
        events.clear();
        manager = new GrokAiProcessManager(events::add);
    }

    @Test
    void unsetReasoningEffortOmitsTheFlag() {
        manager.configureReasoningEffort(null, true);
        assertTrue(manager.buildReasoningEffortArgs("grok-4.6").isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    void blankReasoningEffortTreatedAsUnset() {
        manager.configureReasoningEffort("   ", true);
        assertTrue(manager.buildReasoningEffortArgs("grok-4.6").isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    void supportedLevelIsPassedWithExactValue() {
        manager.configureReasoningEffort("xhigh", true);
        assertEquals(List.of("--reasoning-effort", "xhigh"), manager.buildReasoningEffortArgs("grok-4.6"));
        assertTrue(events.isEmpty(), "a supported level must not fire any status event");
    }

    @Test
    void supportedLevelForGrok45IsPassed() {
        manager.configureReasoningEffort("high", true);
        assertEquals(List.of("--reasoning-effort", "high"), manager.buildReasoningEffortArgs("grok-4.5"));
    }

    // ---- session-sourced value, unsupported by the model: clear + exactly one INFO ----
    @Test
    void sessionSourcedLevelUnsupportedByModelIsClearedAndFiresExactlyOneInfoEvent() {
        // xhigh is grok-4.6-only; grok-4.5 does not support it.
        manager.configureReasoningEffort("xhigh", true);

        assertTrue(manager.buildReasoningEffortArgs("grok-4.5").isEmpty(), "an unsupported level must not be passed");

        assertEquals(1, events.size(), "exactly one INFO event must fire for the mismatch");
        assertTrue(events.get(0) instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO);

        // The mismatch self-corrects: a second call for the same (now-cleared) state must not fire again.
        events.clear();
        assertTrue(manager.buildReasoningEffortArgs("grok-4.5").isEmpty());
        assertTrue(events.isEmpty(), "the same mismatch must not fire a second INFO event once cleared");
    }

    @Test
    void sessionSourcedUnknownModelSupportsNoLevelAtAll() {
        manager.configureReasoningEffort("low", true);

        assertTrue(manager.buildReasoningEffortArgs("grok-1-ancient").isEmpty());
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO);
    }

    // ---- global-sourced value, unsupported by the model: send nothing, but never clear the global default,
    // never touch the session, never fire an INFO the user can't dismiss ----
    @Test
    void globalSourcedLevelUnsupportedByModelIsOmittedWithoutInfoOrClearCallback() {
        AtomicInteger clearedCount = new AtomicInteger();
        manager.setOnReasoningEffortCleared(clearedCount::incrementAndGet);
        manager.configureReasoningEffort("xhigh", false);

        assertTrue(manager.buildReasoningEffortArgs("grok-4.5").isEmpty(),
                   "an unsupported global default must not be sent either");
        assertTrue(events.isEmpty(), "the global-default case must never fire an INFO event");
        assertEquals(0, clearedCount.get(),
                     "the global-default case must never invoke the persisted-clear callback — nothing is cleared");
    }

    @Test
    void globalSourcedUnknownModelSupportsNoLevelAtAllButStaysQuiet() {
        AtomicInteger clearedCount = new AtomicInteger();
        manager.setOnReasoningEffortCleared(clearedCount::incrementAndGet);
        manager.configureReasoningEffort("low", false);

        assertTrue(manager.buildReasoningEffortArgs("grok-1-ancient").isEmpty());
        assertTrue(events.isEmpty());
        assertEquals(0, clearedCount.get());
    }
}
