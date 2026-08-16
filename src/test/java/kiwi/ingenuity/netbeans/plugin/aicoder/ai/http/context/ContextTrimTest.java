package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ContextTrimTest {

    private static ContextBrokerSettings settings(ContextTrimStrategyEnum strategy,
            ContextTriggerEnum trigger, int threshold) {
        ContextBrokerSettings s = ContextBrokerSettings.defaults();
        s.setStrategy(strategy);
        s.setTrigger(trigger);
        s.setTokenThreshold(threshold);
        s.setTrimTargetPercent(70);
        return s;
    }

    private static void addTurn(TestBroker b, String text) {
        b.beginTurn();
        b.append(new ChatMessage(ChatRole.USER, text, List.of(), null));
        b.append(new ChatMessage(ChatRole.ASSISTANT, "reply to " + text, List.of(), null));
        b.commitTurn();
    }

    @Test
    void strategyNoneNeverTrims() {
        TestBroker b = new TestBroker(settings(ContextTrimStrategyEnum.NONE,
                ContextTriggerEnum.ESTIMATED_TOKENS, 10));
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }
        b.trimIfNeeded();
        assertEquals(40, b.entryCount());
    }

    @Test
    void estimatedTokensTrimsDownToTheLowWaterMark() {
        TestBroker b = new TestBroker(settings(ContextTrimStrategyEnum.DROP,
                ContextTriggerEnum.ESTIMATED_TOKENS, 400));
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }
        int before = b.entryCount();
        b.trimIfNeeded();

        assertTrue(b.entryCount() < before, "it must actually evict");
        assertTrue(b.estimatedTokenTotal() <= 400 * 70 / 100,
                "it must trim to the low-water mark, not merely back under the threshold");
    }

    @Test
    void messageCountCapTrims() {
        ContextBrokerSettings s = settings(ContextTrimStrategyEnum.DROP,
                ContextTriggerEnum.MESSAGE_COUNT, 1000000);
        s.setMaxMessages(10);
        TestBroker b = new TestBroker(s);
        for (int i = 0; i < 20; i++) {
            addTurn(b, "m" + i);
        }
        b.trimIfNeeded();
        assertTrue(b.entryCount() <= 10);
    }

    @Test
    void reportedTokensFallsBackToEstimatesUntilUsageIsSeen() {
        TestBroker b = new TestBroker(settings(ContextTrimStrategyEnum.DROP,
                ContextTriggerEnum.REPORTED_TOKENS, 400));
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }
        int before = b.entryCount();
        b.trimIfNeeded();
        assertTrue(b.entryCount() < before,
                "with no usage yet, REPORTED_TOKENS must behave as ESTIMATED_TOKENS "
                + "rather than never trimming");
    }

    @Test
    void dropMarkedUpsertsASingleMarkerNotAPile() {
        TestBroker b = new TestBroker(settings(ContextTrimStrategyEnum.DROP_MARKED,
                ContextTriggerEnum.ESTIMATED_TOKENS, 400));
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }
        b.trimIfNeeded();
        for (int i = 0; i < 20; i++) {
            addTurn(b, "more " + i + " " + "x".repeat(200));
        }
        b.trimIfNeeded();

        long markers = b.snapshot().stream()
                .filter(m -> m.content() != null && m.content().contains("trimmed to fit"))
                .count();
        assertEquals(1, markers, "the marker is upserted, never appended repeatedly");
    }

    @Test
    void pinnedContentAloneOverBudgetDoesNotSpin() {
        TestBroker b = new TestBroker(settings(ContextTrimStrategyEnum.DROP,
                ContextTriggerEnum.ESTIMATED_TOKENS, 10));
        b.upsertPin(PinSlotEnum.IDENTITY, "x".repeat(10000));
        b.trimIfNeeded();
        assertEquals(0, b.entryCount());
    }

    @Test
    void theInFlightTurnSurvivesATrim() {
        TestBroker b = new TestBroker(settings(ContextTrimStrategyEnum.DROP,
                ContextTriggerEnum.ESTIMATED_TOKENS, 400));
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }
        b.beginTurn();
        b.append(new ChatMessage(ChatRole.USER, "the current question", List.of(), null));
        b.trimIfNeeded();

        assertTrue(b.snapshot().stream().anyMatch(m
                -> "the current question".equals(m.content())),
                "trimming must never evict the turn being built");
    }

    @Test
    void theTrimMarkerCountMatchesTheNumberOfGroupsActuallyEvicted() {
        TestBroker b = new TestBroker(settings(ContextTrimStrategyEnum.DROP_MARKED,
                ContextTriggerEnum.ESTIMATED_TOKENS, 400));
        
        // First batch: add turns until trim fires
        int totalEvictedGroups = 0;
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }
        int beforeFirstTrim = b.entryCount();
        b.trimIfNeeded();
        int afterFirstTrim = b.entryCount();
        int firstBatchEvicted = (beforeFirstTrim - afterFirstTrim) / 2;
        totalEvictedGroups += firstBatchEvicted;
        
        // Second batch: add more turns and trim again to accumulate count
        for (int i = 0; i < 20; i++) {
            addTurn(b, "more " + i + " " + "x".repeat(200));
        }
        int beforeSecondTrim = b.entryCount();
        b.trimIfNeeded();
        int afterSecondTrim = b.entryCount();
        int secondBatchEvicted = (beforeSecondTrim - afterSecondTrim) / 2;
        totalEvictedGroups += secondBatchEvicted;
        
        // Find the marker and extract the count using regex
        String markerContent = b.snapshot().stream()
                .filter(m -> m.content() != null && m.content().contains("trimmed to fit"))
                .map(ChatMessage::content)
                .findFirst()
                .orElse(null);
        
        assertTrue(markerContent != null, "marker must be present in snapshot");
        
        Matcher m = Pattern.compile("\\[(\\d+) ").matcher(markerContent);
        assertTrue(m.find(), "marker not found or not in the expected format: " + markerContent);
        int markerCount = Integer.parseInt(m.group(1));
        
        assertEquals(totalEvictedGroups, markerCount,
                "the trim marker must count the actual number of groups evicted across all trim calls");
    }

    @Test
    void pinnedOverBudgetPreventsEvictionOfConversation() {
        TestBroker b = new TestBroker(settings(ContextTrimStrategyEnum.DROP,
                ContextTriggerEnum.ESTIMATED_TOKENS, 10));
        b.upsertPin(PinSlotEnum.IDENTITY, "x".repeat(10000));
        addTurn(b, "turn one");
        addTurn(b, "turn two");
        int before = b.entryCount();
        b.trimIfNeeded();
        assertEquals(before, b.entryCount(),
                "no entries must be evicted when pinned content alone exceeds the low-water mark");
    }

    @Test
    void pinnedOverBudgetFlagIsSetWhenThresholdIsTooLow() {
        TestBroker b = new TestBroker(settings(ContextTrimStrategyEnum.DROP,
                ContextTriggerEnum.ESTIMATED_TOKENS, 10));
        b.upsertPin(PinSlotEnum.IDENTITY, "x".repeat(10000));
        addTurn(b, "turn one");
        b.trimIfNeeded();
        assertTrue(b.isPinnedOverBudget(),
                "isPinnedOverBudget() must return true when the threshold is too low");
    }

    static class TestBroker extends AbstractChatContextBroker {

        TestBroker(ContextBrokerSettings s) {
            super("trim-session", s);
        }

        @Override
        protected int contextLimit() {
            return 0;
        }
    }
}
