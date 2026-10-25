package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnEndReason;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ErrorMapper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cancel while a tool runs, and the next turn's wait for the abandoned tool (spec §7.2), driven at the loop level. The
 * manager's part of Cancel (claim, cancel the handle, interrupt the turn thread) is reproduced by {@link #cancel}.
 */
class ToolCancelTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withAbandonedToolWait(Duration.ofSeconds(5));

    private static final TurnLoop.RequestContext CONTEXT = new TurnLoop.RequestContext(
            "http://localhost:1/v1", "m", null, null, List.of(), ErrorMapper.KeySituation.NONE);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "test-tool-executor");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch slowStarted = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private final ToolRunner runner = new ToolRunner((name, args) -> {
        if ("Slow".equals(name)) {
            slowStarted.countDown();
            try {
                releaseSlow.await();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
            return "slow done";
        }
        return name + " done";
    }, () -> executor, null);

    @AfterEach
    void tearDown() {
        releaseSlow.countDown();
        executor.shutdownNow();
    }

    /**
     * First reply asks for Quick then Slow; any later reply is prose.
     */
    private static ChatClient twoToolsThenProse() {
        List<Integer> calls = new CopyOnWriteArrayList<>();
        return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            calls.add(1);
            if (calls.size() == 1) {
                return CompletableFuture.completedFuture(new ClientResult(
                        new AssembledReply("", List.of(new ChatToolCall("q", "Quick", "{}"),
                                                       new ChatToolCall("s", "Slow", "{}")),
                                           "tool_calls", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false));
            }
            return CompletableFuture.completedFuture(new ClientResult(
                    new AssembledReply("Answer.", List.of(), "stop", null, null, true, true),
                    ClientResult.End.COMPLETE, null, false));
        };
    }

    private static ChatClient prose() {
        return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(new ClientResult(
                        new AssembledReply("Answer.", List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false));
    }

    private record Running(Thread thread, TurnLoop loop, TurnOutcome outcome, RequestHandle handle,
                           List<ChatMessage> history) {

    }

    private Running start(ChatClient client, OpenAiCompatibleLimits limits, String prompt, List<ChatMessage> history) {
        TurnOutcome outcome = new TurnOutcome();
        RequestHandle handle = new RequestHandle();
        TurnLoop loop = new TurnLoop(history, "", client, new CompatibilityFlags(), limits, handle, outcome, CONTEXT,
                                     text -> {
                                     }, null, () -> false, runner, 1);
        Thread thread = new Thread(() -> loop.run(prompt), "test-turn");
        thread.setDaemon(true);
        thread.start();
        return new Running(thread, loop, outcome, handle, history);
    }

    /**
     * What the manager does on Cancel: claim (with the running tool's message), cancel the handle, interrupt.
     */
    private static void cancel(Running running) {
        String tool = running.loop().runningToolName();
        running.outcome().claim(TurnEndReason.CANCELLED, tool != null
                                                         ? TurnLoop.cancelledDuringToolMessage(tool)
                                                         : TurnEndReason.CANCELLED.message());
        running.handle().cancel();
        running.thread().interrupt();
    }

    /**
     * Starts a turn whose second tool blocks, cancels it while that tool runs, and returns it ended.
     */
    private Running abandonSlowTool() throws InterruptedException {
        Running first = start(twoToolsThenProse(), LIMITS, "go", new ArrayList<>());
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS), "the slow tool started");
        awaitTrue(() -> first.loop().runningToolName() != null, "the turn to be waiting on the tool");
        cancel(first);
        first.thread().join(TimeUnit.SECONDS.toMillis(5));
        return first;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    @Test
    @Timeout(10)
    void cancelDuringAToolEndsTheTurnAtOnceAndKeepsWhatCompleted() throws Exception {
        Running first = start(twoToolsThenProse(), LIMITS, "go", new ArrayList<>());
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
        awaitTrue(() -> first.loop().runningToolName() != null, "the turn to be waiting on the tool");

        long started = System.nanoTime();
        cancel(first);
        first.thread().join(1000);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertFalse(first.thread().isAlive());
        assertTrue(elapsedMillis < 200, "the turn ended " + elapsedMillis + " ms after Cancel");
        assertEquals(TurnEndReason.CANCELLED, first.outcome().reason());
        assertEquals("Cancelled. Slow was still running; its result was discarded.", first.outcome().message());
        List<ChatMessage> tools = first.history().stream().filter(m -> m.role() == ChatRole.TOOL).toList();
        assertEquals(2, tools.size(), "every call has a result, so the next request is well formed");
        assertEquals("Quick done", tools.get(0).content(), "the completed call is kept");
        assertEquals(ToolRunner.cancelledNote("Slow"), tools.get(1).content());
        assertTrue(runner.isBusy(), "the tool itself keeps running; only the wait was cancelled");
    }

    @Test
    @Timeout(10)
    void theNextPromptWaitsForTheAbandonedToolThenProceeds() throws Exception {
        Running first = abandonSlowTool();

        Running second = start(prose(), LIMITS, "again", first.history());
        awaitTrue(() -> second.loop().statusText().startsWith("Waiting for previous tool (Slow, "),
                  "the waiting status");
        assertTrue(second.thread().isAlive(), "the new turn is waiting, not running");
        releaseSlow.countDown();
        second.thread().join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(TurnEndReason.ANSWERED, second.outcome().reason());
        assertEquals("again", second.history().stream().filter(m -> m.role() == ChatRole.USER)
                     .reduce((a, b) -> b).orElseThrow().content());
    }

    @Test
    @Timeout(10)
    void theNextPromptIsRefusedWhenTheAbandonedToolOutlastsTheWaitLimit() throws Exception {
        Running first = abandonSlowTool();
        int before = first.history().size();

        Running second = start(prose(), LIMITS.withAbandonedToolWait(Duration.ofMillis(200)), "again",
                               first.history());
        second.thread().join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(TurnEndReason.SERVER_ERROR, second.outcome().reason());
        assertEquals("The previous Slow is still running — try again when it finishes.", second.outcome().message());
        assertEquals(before, second.history().size(), "a refused prompt adds nothing to the model's context");
    }

    @Test
    @Timeout(10)
    void cancelDuringTheWaitForTheAbandonedToolEndsTheTurnCancelled() throws Exception {
        Running first = abandonSlowTool();
        AtomicReference<Running> second = new AtomicReference<>(start(prose(), LIMITS, "again", first.history()));
        awaitTrue(() -> second.get().loop().statusText().startsWith("Waiting for previous tool"),
                  "the waiting status");

        cancel(second.get());
        second.get().thread().join(1000);

        assertFalse(second.get().thread().isAlive());
        assertEquals(TurnEndReason.CANCELLED, second.get().outcome().reason());
    }
}
