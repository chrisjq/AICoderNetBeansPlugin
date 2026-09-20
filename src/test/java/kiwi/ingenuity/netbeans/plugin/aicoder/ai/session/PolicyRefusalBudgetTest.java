package kiwi.ingenuity.netbeans.plugin.aicoder.ai.session;

import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The loop guard for waking a session after a policy refusal. The property that matters is termination: a refusal is
 * caused by the agent and the wake-up runs the agent again, so nothing outside the model limits how often the pair can
 * repeat except this budget.
 */
class PolicyRefusalBudgetTest {

    /**
     * THE TERMINATION PROPERTY, on real behaviour. However many times a refusal ends a turn, and however the model
     * behaves, the automatic turns between two user messages never exceed the cap. Written against the constant, not a
     * literal: the cap is a judgement call and may be tuned, the property must hold for any value.
     */
    @Test
    void aHundredRefusalsWithNoUserMessageWakeTheSessionExactlyTheCappedNumberOfTimes() {
        PolicyRefusalBudget budget = new PolicyRefusalBudget();

        int wakes = 0;
        for (int i = 0; i < 100; i++) {
            if (budget.tryAcquire()) {
                wakes++;
            }
        }

        assertEquals(PolicyRefusalBudget.MAX_WAKES, wakes, "automatic turns must be bounded, whatever the model does");
    }

    @Test
    void theCapIsFiveAndTheBoundIsFiniteForAnyValue() {
        assertEquals(5, PolicyRefusalBudget.MAX_WAKES, "5 is a judgement call; change it deliberately, with this test");
        for (int cap : new int[]{0, 1, 2, 7, 50}) {
            PolicyRefusalBudget budget = new PolicyRefusalBudget(cap);
            int wakes = 0;
            for (int i = 0; i < 1000; i++) {
                if (budget.tryAcquire()) {
                    wakes++;
                }
            }
            assertEquals(cap, wakes, "whatever the cap, without a reset the wake-ups stop at it");
        }
    }

    @Test
    void onceSpentTheBudgetStaysSpentUntilResetAndOnlyResetRefillsIt() {
        PolicyRefusalBudget budget = new PolicyRefusalBudget();
        for (int i = 0; i < PolicyRefusalBudget.MAX_WAKES; i++) {
            assertTrue(budget.tryAcquire());
        }

        assertFalse(budget.tryAcquire(), "spent");
        assertFalse(budget.tryAcquire(), "and it stays spent: asking again never refills it");

        budget.reset();

        for (int i = 0; i < PolicyRefusalBudget.MAX_WAKES; i++) {
            assertTrue(budget.tryAcquire(), "a user message refills it");
        }
        assertFalse(budget.tryAcquire(), "and the cap is the same afterwards, not larger");
    }

    @Test
    void resettingAnUntouchedBudgetDoesNotRaiseTheCap() {
        PolicyRefusalBudget budget = new PolicyRefusalBudget();
        budget.reset();
        budget.reset();

        int wakes = 0;
        for (int i = 0; i < 100; i++) {
            if (budget.tryAcquire()) {
                wakes++;
            }
        }

        assertEquals(PolicyRefusalBudget.MAX_WAKES, wakes, "reset gives back what was spent; it never adds tokens");
    }

    @Test
    void everyBudgetIsIndependent() {
        PolicyRefusalBudget first = new PolicyRefusalBudget();
        PolicyRefusalBudget second = new PolicyRefusalBudget();
        while (first.tryAcquire()) {
            // spend it
        }

        assertTrue(second.tryAcquire(), "one session running out must not stop another");
    }

    @Test
    void concurrentAcquiresNeverExceedTheCap() throws Exception {
        PolicyRefusalBudget budget = new PolicyRefusalBudget();
        AtomicInteger granted = new AtomicInteger();
        Thread[] threads = new Thread[16];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 50; j++) {
                    if (budget.tryAcquire()) {
                        granted.incrementAndGet();
                    }
                }
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(PolicyRefusalBudget.MAX_WAKES, granted.get(),
                     "a race between threads must not grant extra wake-ups");
    }
}
