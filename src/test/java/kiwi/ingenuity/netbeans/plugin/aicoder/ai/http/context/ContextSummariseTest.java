package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ContextSummariseTest {

    private static ContextBrokerSettings summariseSettings(int threshold) {
        ContextBrokerSettings s = ContextBrokerSettings.defaults();
        s.setStrategy(ContextTrimStrategyEnum.SUMMARISE);
        s.setTrigger(ContextTriggerEnum.ESTIMATED_TOKENS);
        s.setTokenThreshold(threshold);
        s.setTrimTargetPercent(70);
        return s;
    }

    private static void addTurn(TestBroker b, String text) {
        b.beginTurn();
        b.append(new ChatMessage(ChatRole.USER, text, List.of(), null));
        b.append(new ChatMessage(ChatRole.ASSISTANT, "reply " + text, List.of(), null));
        b.commitTurn();
    }

    @Test
    void theSummaryReplacesTheEvictedSpan() {
        TestBroker b = new TestBroker(summariseSettings(400));
        b.setSummariser(span -> "SUMMARY OF EARLIER TALK");
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }

        b.trimIfNeeded();

        assertTrue(b.snapshot().stream().anyMatch(m
                -> m.content() != null && m.content().contains("SUMMARY OF EARLIER TALK")));
    }

    @Test
    void aFailingSummariserFallsBackToTheDropMarker() {
        TestBroker b = new TestBroker(summariseSettings(400));
        b.setSummariser(span -> {
            throw new IOException("model unavailable");
        });
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }

        b.trimIfNeeded();

        assertTrue(b.snapshot().stream().anyMatch(m
                -> m.content() != null && m.content().contains("trimmed to fit")),
                "a failed summariser must not lose the fact that history was dropped");
    }

    @Test
    void aBlankSummaryFallsBackToTheDropMarker() {
        TestBroker b = new TestBroker(summariseSettings(400));
        b.setSummariser(span -> "   ");
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }

        b.trimIfNeeded();

        assertTrue(b.snapshot().stream().anyMatch(m
                -> m.content() != null && m.content().contains("trimmed to fit")));
    }

    @Test
    void aStaleSummaryIsDiscardedWhenHistoryMovedUnderIt() throws Exception {
        TestBroker b = new TestBroker(summariseSettings(400));
        CountDownLatch summariserEntered = new CountDownLatch(1);
        CountDownLatch mutationDone = new CountDownLatch(1);

        b.setSummariser(span -> {
            summariserEntered.countDown();
            try {
                mutationDone.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "STALE SUMMARY";
        });
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }

        Thread trimmer = new Thread(b::trimIfNeeded);
        trimmer.setDaemon(true);
        trimmer.start();

        assertTrue(summariserEntered.await(5, TimeUnit.SECONDS));
        b.clearHistory();
        mutationDone.countDown();
        trimmer.join(5000);

        assertFalse(b.snapshot().stream().anyMatch(m
                -> m.content() != null && m.content().contains("STALE SUMMARY")),
                "a summary computed against a history that has since changed must be "
                + "discarded, not written over the newer state");
    }

    @Test
    void noSummariserConfiguredFallsBackRatherThanThrowing() {
        TestBroker b = new TestBroker(summariseSettings(400));
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }

        b.trimIfNeeded();

        assertEquals(true, b.entryCount() < 40, "it must still evict");
    }

    @Test
    void compactNowSummarisesUnderDropMarkedWhenSummariserIsPresent() {
        ContextBrokerSettings s = ContextBrokerSettings.defaults();
        s.setStrategy(ContextTrimStrategyEnum.DROP_MARKED);
        s.setTrigger(ContextTriggerEnum.ESTIMATED_TOKENS);
        s.setTokenThreshold(400);
        s.setTrimTargetPercent(70);
        TestBroker b = new TestBroker(s);
        b.setSummariser(span -> "COMPACT SUMMARY");
        for (int i = 0; i < 20; i++) {
            addTurn(b, "message " + i + " " + "x".repeat(200));
        }

        b.compactNow();

        assertTrue(b.snapshot().stream().anyMatch(m
                -> m.content() != null && m.content().contains("COMPACT SUMMARY")),
                "compactNow must summarise when a summariser is present, even under DROP_MARKED strategy");
    }

    static class TestBroker extends AbstractChatContextBroker {

        TestBroker(ContextBrokerSettings s) {
            super("sum-session", s);
        }

        @Override
        protected int contextLimit() {
            return 0;
        }
    }
}
