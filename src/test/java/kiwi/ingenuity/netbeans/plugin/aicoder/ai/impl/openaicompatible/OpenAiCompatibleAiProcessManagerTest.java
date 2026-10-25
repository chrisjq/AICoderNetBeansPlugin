package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnEndReason;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.ChatClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleAiProcessManagerTest {

    private static final OpenAiCompatibleLimits LIMITS = OpenAiCompatibleLimits.defaults()
            .withCheckInterval(Duration.ofMillis(20))
            .withFirstByteTimeout(Duration.ofMillis(300))
            .withIdleTimeout(Duration.ofMillis(300))
            .withTotalCap(Duration.ofSeconds(2))
            .withRetryBackoff(Duration.ofMillis(50))
            .withProbeTimeout(Duration.ofMillis(100));

    /**
     * No keyring, ever: an empty store read on the calling thread.
     */
    private static final SecretStore NO_SECRETS = new SecretStore() {
        @Override
        public char[] read(String key) {
            return null;
        }

        @Override
        public void save(String key, char[] value, String description) {
        }

        @Override
        public void delete(String key) {
        }
    };

    private final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
    private OpenAiCompatibleAiProcessManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    /**
     * A client whose requests never answer on their own, like a server that has stalled.
     */
    private static final class StalledClient {

        final CountDownLatch sent = new CountDownLatch(1);

        ChatClient client() {
            return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
                sent.countDown();
                return new CompletableFuture<ClientResult>();
            };
        }
    }

    private OpenAiCompatibleAiProcessManager startedManager(ChatClient client) {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("http://localhost:1");
        settings.setModel("model-x");
        return startedManager(client, settings);
    }

    private OpenAiCompatibleAiProcessManager startedManager(ChatClient client,
                                                            OpenAiCompatibleSessionSettings settings) {
        String sessionId = "oc-pm-" + System.nanoTime() + "-" + System.nanoTime();
        OpenAiCompatibleAiProcessManager created = new OpenAiCompatibleAiProcessManager(
                events::add, limits -> client,
                (id, keySet) -> new SessionSecrets(id, NO_SECRETS, Runnable::run, keySet), LIMITS);
        AiSession session = new AiSession(sessionId, "OC", null, AiTypeEnum.OPENAI_COMPATIBLE,
                                          null, settings, Instant.now(), Instant.now());
        created.setContextDirResolverForTest(id -> CONTEXT_ROOT.resolve(id));
        created.startWithoutMcpForTest(session);
        manager = created;
        return created;
    }

    /**
     * Saved contexts of these tests go to a temporary folder, never to the user's plugin folder.
     */
    private static final java.nio.file.Path CONTEXT_ROOT = tempContextRoot();

    private static java.nio.file.Path tempContextRoot() {
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("oc-pm-context");
            root.toFile().deleteOnExit();
            return root;
        }
        catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private long count(Class<?> type) {
        return events.stream().filter(type::isInstance).count();
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
    void aPromptWhileATurnRunsIsRefusedWithInfoAndATurnComplete() throws Exception {
        StalledClient stalled = new StalledClient();
        OpenAiCompatibleAiProcessManager pm = startedManager(stalled.client());
        pm.sendPrompt("first", null, List.of());
        assertTrue(stalled.sent.await(5, TimeUnit.SECONDS));

        pm.sendPrompt("second", null, List.of());

        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.INFO
                && "Wait for the model to finish before sending".equals(s.text())), events.toString());
        assertEquals(1, count(TurnCompleteEvent.class), "the refused prompt closes with its own TurnCompleteEvent");
        TurnCompleteEvent refused = (TurnCompleteEvent) events.stream()
                .filter(TurnCompleteEvent.class::isInstance).findFirst().orElseThrow();
        assertEquals(null, refused.reason(), "a refusal carries no turn reason");
        assertTrue(pm.isProcessing(), "the first turn is still running");
    }

    @Test
    @Timeout(10)
    void cancelDuringStreamingEndsTheTurnOnceAsCancelledAndReportsStopped() throws Exception {
        StalledClient stalled = new StalledClient();
        OpenAiCompatibleAiProcessManager pm = startedManager(stalled.client());
        pm.sendPrompt("hi", null, List.of());
        assertTrue(stalled.sent.await(5, TimeUnit.SECONDS));

        pm.interrupt(InterruptTypeEnum.Cancel);

        awaitTrue(() -> count(TurnCompleteEvent.class) > 0, "the turn's closing event");
        Thread.sleep(100);
        assertEquals(1, count(TurnCompleteEvent.class), "exactly one TurnCompleteEvent");
        TurnCompleteEvent done = (TurnCompleteEvent) events.stream()
                .filter(TurnCompleteEvent.class::isInstance).findFirst().orElseThrow();
        assertEquals(TurnEndReason.CANCELLED, done.reason());
        assertEquals("Cancelled.", done.message());
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.STOPPED),
                   "STOPPED makes the UI reject pending diffs and questions");
        assertFalse(pm.isProcessing());
    }

    @Test
    @Timeout(10)
    void stopReturnsAtOnceWhileARequestIsStalled() throws Exception {
        StalledClient stalled = new StalledClient();
        OpenAiCompatibleAiProcessManager pm = startedManager(stalled.client());
        pm.sendPrompt("hi", null, List.of());
        assertTrue(stalled.sent.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        pm.stop();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 100, "stop() took " + elapsedMillis + " ms");
        assertFalse(pm.isRunning());
        awaitTrue(() -> count(TurnCompleteEvent.class) == 1, "the stopped turn's single closing event");
    }

    /**
     * A client whose first reply asks for the tool "Slow"; later replies are prose.
     */
    private static ChatClient slowToolThenProse() {
        java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            String text = sent.getAndIncrement() == 0 ? "" : "Done.";
            List<kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall> calls = text.isEmpty()
                                                                                      ? List.of(new kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall("s", "Slow", "{}"))
                                                                                      : List.of();
            return CompletableFuture.completedFuture(new ClientResult(
                    new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                            text, calls, calls.isEmpty() ? "stop" : "tool_calls", null, null, true, true),
                    ClientResult.End.COMPLETE, null, false));
        };
    }

    private static com.google.gson.JsonArray slowToolSchema() {
        com.google.gson.JsonObject schema = new com.google.gson.JsonObject();
        schema.addProperty("name", "Slow");
        schema.addProperty("description", "Blocks until released.");
        com.google.gson.JsonArray tools = new com.google.gson.JsonArray();
        tools.add(schema);
        return tools;
    }

    @Test
    @Timeout(10)
    void cancelDuringAToolEndsTheTurnAtOnceWithOneStopped() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OpenAiCompatibleAiProcessManager pm = startedManager(slowToolThenProse());
        pm.setToolsForTest((name, args) -> {
            started.countDown();
            try {
                release.await();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "released";
        }, slowToolSchema());
        try {
            pm.sendPrompt("go", null, List.of());
            assertTrue(started.await(5, TimeUnit.SECONDS), "the tool started");
            Thread.sleep(50);

            long begun = System.nanoTime();
            pm.interrupt(InterruptTypeEnum.Cancel);
            awaitTrue(() -> count(TurnCompleteEvent.class) > 0, "the turn's closing event");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun);

            assertTrue(elapsedMillis < 200, "Cancel took " + elapsedMillis + " ms");
            Thread.sleep(100);
            assertEquals(1, count(TurnCompleteEvent.class));
            TurnCompleteEvent done = (TurnCompleteEvent) events.stream()
                    .filter(TurnCompleteEvent.class::isInstance).findFirst().orElseThrow();
            assertEquals(TurnEndReason.CANCELLED, done.reason());
            assertEquals("Cancelled. Slow was still running; its result was discarded.", done.message());
            assertEquals(1, events.stream()
                         .filter(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.STOPPED).count(),
                         "STOPPED exactly once, so the UI rejects the pending diff or question once");
            assertTrue(events.stream().anyMatch(e -> e instanceof kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent t
                    && "Slow".equals(t.toolName())), "a ToolUseEvent for the call");
        }
        finally {
            release.countDown();
        }
    }

    @Test
    @Timeout(10)
    void stopDuringAToolReturnsAtOnceAndInterruptsTheTool() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        OpenAiCompatibleAiProcessManager pm = startedManager(slowToolThenProse());
        pm.setToolsForTest((name, args) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            }
            catch (InterruptedException ex) {
                interrupted.countDown();
            }
            return "stopped";
        }, slowToolSchema());
        pm.sendPrompt("go", null, List.of());
        assertTrue(started.await(5, TimeUnit.SECONDS), "the tool started");

        long begun = System.nanoTime();
        pm.stop();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun);

        assertTrue(elapsedMillis < 100, "stop() took " + elapsedMillis + " ms");
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the tool thread was interrupted");
        awaitTrue(() -> count(TurnCompleteEvent.class) == 1, "the stopped turn's single closing event");
    }

    @Test
    @Timeout(10)
    void anAnsweredTurnEndsWithAnsweredAndClearsTheStatusLine() throws Exception {
        ChatClient answering = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            onTextDelta.accept("Hello.");
            return CompletableFuture.completedFuture(new ClientResult(
                    new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                            "Hello.", List.of(), "stop", null, null, true, true),
                    ClientResult.End.COMPLETE, null, false));
        };
        OpenAiCompatibleAiProcessManager pm = startedManager(answering);

        pm.sendPrompt("hi", null, List.of());

        awaitTrue(() -> count(TurnCompleteEvent.class) == 1, "the answered turn");
        awaitTrue(() -> !pm.isProcessing(), "processing cleared");
        TurnCompleteEvent done = (TurnCompleteEvent) events.stream()
                .filter(TurnCompleteEvent.class::isInstance).findFirst().orElseThrow();
        assertEquals(TurnEndReason.ANSWERED, done.reason());
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.READY
                && "".equals(s.text())), "the status line is cleared at the end of the turn");
    }

    private static ChatClient answering() {
        return (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(new ClientResult(
                        new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                                "Hello.", List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false));
    }

    private long uncappedNotes() {
        return events.stream().filter(e -> e instanceof SystemNotificationEvent n
                && OpenAiCompatibleAiProcessManager.UNCAPPED_HOSTED_MESSAGE.equals(n.text())).count();
    }

    private static OpenAiCompatibleSessionSettings hostedSettings(Integer maxToolRounds) {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("https://api.example.com/v1");
        settings.setModel("model-x");
        settings.setApiKeySet(true);
        settings.setMaxToolRounds(maxToolRounds);
        return settings;
    }

    @Test
    @Timeout(10)
    void aHostedEndpointWithNoRoundCapGetsTheCostNoteOncePerSession() throws Exception {
        OpenAiCompatibleAiProcessManager pm = startedManager(answering(), hostedSettings(null));

        pm.sendPrompt("one", null, List.of());
        awaitTrue(() -> count(TurnCompleteEvent.class) == 1 && !pm.isProcessing(), "the first turn");
        pm.sendPrompt("two", null, List.of());
        awaitTrue(() -> count(TurnCompleteEvent.class) == 2 && !pm.isProcessing(), "the second turn");

        assertEquals(1, uncappedNotes(), "the note is shown once per session: " + events);
    }

    @Test
    @Timeout(10)
    void aHostedEndpointWithARoundCapGetsNoCostNote() throws Exception {
        OpenAiCompatibleAiProcessManager pm = startedManager(answering(), hostedSettings(5));

        pm.sendPrompt("one", null, List.of());
        awaitTrue(() -> count(TurnCompleteEvent.class) == 1 && !pm.isProcessing(), "the turn");

        assertEquals(0, uncappedNotes());
    }

    @Test
    @Timeout(10)
    void aLocalEndpointGetsNoCostNote() throws Exception {
        OpenAiCompatibleAiProcessManager pm = startedManager(answering());

        pm.sendPrompt("one", null, List.of());
        awaitTrue(() -> count(TurnCompleteEvent.class) == 1 && !pm.isProcessing(), "the turn");

        assertEquals(0, uncappedNotes());
    }

    private long notes(String text) {
        return events.stream().filter(e -> e instanceof SystemNotificationEvent n && text.equals(n.text())).count();
    }

    @Test
    @Timeout(10)
    void afterTheNativeFailureLimitTheNextTurnSwitchesToSchemaWithAFreshContext() throws Exception {
        List<kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec> requests
                = new CopyOnWriteArrayList<>();
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            requests.add(request);
            kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage last
                    = request.messages().get(request.messages().size() - 1);
            boolean askForTool = request.tools() != null
                    && last.role() == kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole.USER;
            kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply reply = askForTool
                                                                                                          ? new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                            "", List.of(new kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall(
                                    "c", "Slow", "not json")), "tool_calls", null, null, true, true)
                                                                                                          : new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                            "Done.", List.of(), "stop", null, null, true, true);
            return CompletableFuture.completedFuture(new ClientResult(reply, ClientResult.End.COMPLETE, null, false));
        };
        OpenAiCompatibleAiProcessManager pm = startedManager(client);
        pm.setToolsForTest((name, args) -> "ok", slowToolSchema());
        for (int turn = 1; turn <= 2; turn++) {
            int expected = turn;
            pm.sendPrompt("turn " + turn, null, List.of());
            awaitTrue(() -> count(TurnCompleteEvent.class) == expected && !pm.isProcessing(), "turn " + turn);
        }
        assertEquals(0, notes(OpenAiCompatibleAiProcessManager.SWITCHED_TO_SCHEMA_MESSAGE),
                     "the switch applies from the turn after the limit");
        int before = requests.size();

        pm.sendPrompt("turn 3", null, List.of());
        awaitTrue(() -> count(TurnCompleteEvent.class) == 3 && !pm.isProcessing(), "turn 3");

        assertEquals(1, notes(OpenAiCompatibleAiProcessManager.SWITCHED_TO_SCHEMA_MESSAGE));
        var third = requests.get(before);
        assertEquals(null, third.tools(), "schema mode offers no native tools");
        assertTrue(third.messages().stream().noneMatch(m -> m.content() != null && m.content().contains("turn 1")),
                   "the model-facing context starts afresh");
        assertTrue(third.messages().stream().anyMatch(m -> "turn 3".equals(m.content())));
    }

    @Test
    @Timeout(10)
    void theResultCapUsesTheWindowOverrideElseTheAssumedWindow() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        int assumed = OpenAiCompatibleAiProcessManager.toolResultCapChars(settings);
        assertTrue(assumed > 0, "a cap even before detection: " + assumed);

        settings.setContextWindowOverride(64_000);

        assertTrue(OpenAiCompatibleAiProcessManager.toolResultCapChars(settings) > assumed);
    }

    private boolean hasInfo(String text) {
        return events.stream().anyMatch(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.INFO
                && text.equals(s.text()));
    }

    private static javax.swing.JButton compactButtonOf(
            kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleInfoBarExtension bar) {
        return bar.createComponents().stream()
                .filter(c -> c instanceof javax.swing.JButton b && b.getText().endsWith("Compact"))
                .map(javax.swing.JButton.class::cast).findFirst().orElseThrow();
    }

    private static void flushEdt() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
        });
    }

    @Test
    @Timeout(10)
    void theCompactButtonIsGreyedWhileATurnRunsAndEnabledAfter() throws Exception {
        StalledClient stalled = new StalledClient();
        OpenAiCompatibleAiProcessManager pm = startedManager(stalled.client());
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleInfoBarExtension bar
                = new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleInfoBarExtension();
        pm.attachInfoBar(bar);
        javax.swing.JButton compact = compactButtonOf(bar);

        pm.sendPrompt("hi", null, List.of());
        assertTrue(stalled.sent.await(5, TimeUnit.SECONDS));
        flushEdt();
        assertFalse(compact.isEnabled(), "greyed while the turn runs");

        pm.interrupt(InterruptTypeEnum.Cancel);
        awaitTrue(() -> count(TurnCompleteEvent.class) == 1, "the cancelled turn");
        flushEdt();

        assertTrue(compact.isEnabled(), "enabled again when the turn ends");
    }

    private static final String VALID_SUMMARY = "## Goal\nKeep going\n## Constraints\nnone\n## Progress\nSome\n"
            + "## Decisions\nnone\n## Next steps\nMore\n## Relevant files\nnone\n## Critical context\nnone";

    @Test
    @Timeout(20)
    void aManualSummaryRefusesPromptsUntilItEndsThenFillsTheSummaryButton() throws Exception {
        CountDownLatch summaryAsked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            String first = request.messages().get(0).content();
            boolean summaryRequest = first != null
                    && (first.startsWith("Summarise the conversation") || first.startsWith("Update the summary"));
            if (!summaryRequest) {
                return CompletableFuture.completedFuture(new ClientResult(
                        new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                                "a".repeat(3000), List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false));
            }
            return CompletableFuture.supplyAsync(() -> {
                summaryAsked.countDown();
                try {
                    release.await();
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new ClientResult(
                        new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                                VALID_SUMMARY, List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false);
            });
        };
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("http://localhost:1");
        settings.setModel("model-x");
        settings.setCompactionStrategy(
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum.SUMMARISE);
        OpenAiCompatibleAiProcessManager pm = startedManager(client, settings);
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleInfoBarExtension bar
                = new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleInfoBarExtension();
        pm.attachInfoBar(bar);
        for (int turn = 1; turn <= 12; turn++) {
            int expected = turn;
            pm.sendPrompt("question " + turn, null, List.of());
            awaitTrue(() -> count(TurnCompleteEvent.class) == expected && !pm.isProcessing(), "turn " + turn);
        }

        pm.compactNow();
        assertTrue(summaryAsked.await(5, TimeUnit.SECONDS), "the summary model was asked");
        pm.sendPrompt("too soon", null, List.of());

        assertTrue(hasInfo(OpenAiCompatibleAiProcessManager.PROMPT_REFUSED_DURING_COMPACT), events.toString());
        assertEquals("Compaction is running — try again when it finishes.",
                     OpenAiCompatibleAiProcessManager.PROMPT_REFUSED_DURING_COMPACT);
        assertEquals(13, count(TurnCompleteEvent.class), "the refusal closes with its own TurnCompleteEvent");

        release.countDown();
        awaitTrue(() -> !pm.isCompacting(), "compaction finished");
        assertTrue(events.stream().anyMatch(e -> e instanceof SystemNotificationEvent n
                && n.text().startsWith("Context compacted (") && n.text().endsWith(" messages summarised)")),
                   events.toString());
        String summary = pm.turnContextForTest().summary();
        assertTrue(summary != null && summary.contains("## Goal"), String.valueOf(summary));
        flushEdt();
        javax.swing.JButton summaryButton = bar.createComponents().stream()
                .filter(c -> c instanceof javax.swing.JButton b && b.getText().endsWith("Summary"))
                .map(javax.swing.JButton.class::cast).findFirst().orElseThrow();
        assertTrue(summaryButton.isEnabled(), "the Summary button shows the new summary");
    }

    @Test
    @Timeout(10)
    void manualCompactQueuesWhileATurnRuns() throws Exception {
        StalledClient stalled = new StalledClient();
        OpenAiCompatibleAiProcessManager pm = startedManager(stalled.client());
        pm.sendPrompt("hi", null, List.of());
        assertTrue(stalled.sent.await(5, TimeUnit.SECONDS));

        pm.compactNow();
        pm.compactNow();

        assertTrue(hasInfo(OpenAiCompatibleAiProcessManager.COMPACT_REFUSED_DURING_TURN), events.toString());
        assertTrue(pm.compactQueuedForTest(), "the request is queued for turn completion");
        assertFalse(pm.isCompacting());
    }

    @Test
    @Timeout(10)
    void manualCompactWithNothingToCompactSaysSoAndClearsTheStatus() throws Exception {
        OpenAiCompatibleAiProcessManager pm = startedManager(answering());

        pm.compactNow();

        awaitTrue(() -> hasInfo(OpenAiCompatibleAiProcessManager.NOTHING_TO_COMPACT), "the nothing-to-compact note");
        awaitTrue(() -> !pm.isCompacting(), "compaction finished");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.READY
                && "".equals(s.text())), "the status line is cleared");
    }

    @Test
    @Timeout(10)
    void manualCompactTrimsALongConversationOnTheAssumedWindow() throws Exception {
        String longAnswer = "a".repeat(3000);
        ChatClient longAnswers = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(new ClientResult(
                        new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                                longAnswer, List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false));
        OpenAiCompatibleAiProcessManager pm = startedManager(longAnswers);
        for (int turn = 1; turn <= 12; turn++) {
            int expected = turn;
            pm.sendPrompt("question " + turn, null, List.of());
            awaitTrue(() -> count(TurnCompleteEvent.class) == expected && !pm.isProcessing(), "turn " + turn);
        }

        pm.compactNow();

        awaitTrue(() -> events.stream().anyMatch(e -> e instanceof SystemNotificationEvent n
                && n.text().startsWith("Context trimmed (") && n.text().contains("window assumed")),
                  "the trimmed notice, noting the assumed window");
        awaitTrue(() -> !pm.isCompacting(), "compaction finished");
    }

    @Test
    @Timeout(10)
    void aStatedOverflowLimitIsStoredInTheSessionDecisions() throws Exception {
        java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        ChatClient overflowOnce = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(sent.getAndIncrement() == 0
                                                     ? new ClientResult(null, ClientResult.End.HTTP_ERROR,
                                                                        new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.MappedError(
                                                                                kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.MappedError.Kind.CONTEXT_OVERFLOW,
                                                                                "too long", null, 4096), false)
                                                     : new ClientResult(
                                new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                                        "Short.", List.of(), "stop", null, null, true, true),
                                ClientResult.End.COMPLETE, null, false));
        OpenAiCompatibleAiProcessManager pm = startedManager(overflowOnce);

        pm.sendPrompt("hi", null, List.of());
        awaitTrue(() -> count(TurnCompleteEvent.class) == 1 && !pm.isProcessing(), "the turn");

        assertEquals(4096, pm.decisionsForTest().learnedWindow());
    }

    @Test
    @Timeout(10)
    void turnEndPublishesTheServerReportedContextUsage() throws Exception {
        ChatClient replyWithUsage = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                -> CompletableFuture.completedFuture(new ClientResult(
                        new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                                "Done.", List.of(), "stop", 14_137, 75, true, true),
                        ClientResult.End.COMPLETE, null, false));
        OpenAiCompatibleAiProcessManager pm = startedManager(replyWithUsage);

        pm.sendPrompt("hi", null, List.of());

        awaitTrue(() -> events.stream().anyMatch(e -> e instanceof kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.events.OpenAiCompatibleContextUsageEvent),
                  "the context usage event");
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.events.OpenAiCompatibleContextUsageEvent usage
                = (kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.events.OpenAiCompatibleContextUsageEvent) events.stream().filter(kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.events.OpenAiCompatibleContextUsageEvent.class::isInstance)
                        .reduce((first, second) -> second).orElseThrow();
        assertEquals(14_212, usage.used());
    }

    @Test
    @Timeout(10)
    void contextConfigUsesTheConfiguredServerTypeAsTheKind() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setServerType(ServerTypeEnum.LM_STUDIO);
        settings.setContextWindowOverride(12_000);
        settings.setSummariseThreshold(20_000);

        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.TurnContext.Config config
                = OpenAiCompatibleAiProcessManager.contextConfig(settings, "m");

        assertEquals("LM_STUDIO", config.kindName());
        assertEquals(12_000, config.override());
        assertEquals(20_000, config.summariseThreshold());
        assertEquals("m", config.model());
    }

    @Test
    @Timeout(10)
    void countsComeFromTheSessionOverridesElseTheDefaults() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        assertEquals(kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.TurnLoop.Counts.defaults(),
                     OpenAiCompatibleAiProcessManager.countsOf(settings));

        settings.setMaxToolRounds(7);

        assertEquals(7, OpenAiCompatibleAiProcessManager.countsOf(settings).maxToolRounds());
    }

    @Test
    @Timeout(10)
    void resetContextClearsHistoryAndKeepsPinnedInstructionsAfterATurn() throws Exception {
        OpenAiCompatibleAiProcessManager pm = startedManager(answering());
        pm.updatePinnedContext("identity", "baseline", "special instructions");
        pm.sendPrompt("hi", null, List.of());
        awaitTrue(() -> count(TurnCompleteEvent.class) == 1 && !pm.isProcessing(), "the turn");

        assertTrue(pm.historyForTest().size() > 0, "history has turns");

        assertTrue(pm.resetContext());
        assertTrue(pm.historyForTest().isEmpty(), "history cleared");
        assertEquals(null, pm.turnContextForTest().summary());
        assertEquals(0, pm.turnContextForTest().prunedCount());
        pm.awaitContextIo();
        assertFalse(java.nio.file.Files.exists(CONTEXT_ROOT.resolve(pm.currentSessionIdForTest()).resolve(OpenAiCompatibleAiProcessManager.CONTEXT_FILE_NAME)),
                    "context file deleted");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent s && s.type() == StatusEventTypeEnum.INFO
                && "Context reset — the model no longer has the earlier conversation (the chat log above is kept)".equals(s.text())));
    }

    @Test
    @Timeout(10)
    void resetContextRefusedWhileProcessingAndChangesNothing() throws Exception {
        StalledClient stalled = new StalledClient();
        OpenAiCompatibleAiProcessManager pm = startedManager(stalled.client());
        pm.updatePinnedContext("identity", "baseline", "special instructions");
        pm.sendPrompt("hi", null, List.of());
        assertTrue(stalled.sent.await(5, TimeUnit.SECONDS));
        int before = pm.historyForTest().size();

        assertFalse(pm.resetContext());
        assertEquals(before, pm.historyForTest().size());
        pm.interrupt(InterruptTypeEnum.Cancel);
        awaitTrue(() -> count(TurnCompleteEvent.class) >= 1, "turn ends");
    }

    @Test
    @Timeout(10)
    void resetContextRefusedWhileCompactingAndChangesNothing() throws Exception {
        CountDownLatch summaryAsked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatClient client = (request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation) -> {
            String first = request.messages().get(0).content();
            boolean summaryRequest = first != null
                    && (first.startsWith("Summarise the conversation") || first.startsWith("Update the summary"));
            if (!summaryRequest) {
                return CompletableFuture.completedFuture(new ClientResult(
                        new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                                "a".repeat(3000), List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false));
            }
            return CompletableFuture.supplyAsync(() -> {
                summaryAsked.countDown();
                try {
                    release.await();
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new ClientResult(
                        new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.AssembledReply(
                                VALID_SUMMARY, List.of(), "stop", null, null, true, true),
                        ClientResult.End.COMPLETE, null, false);
            });
        };
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("http://localhost:1");
        settings.setModel("model-x");
        settings.setCompactionStrategy(
                kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum.SUMMARISE);
        OpenAiCompatibleAiProcessManager pm = startedManager(client, settings);
        for (int turn = 1; turn <= 12; turn++) {
            int expected = turn;
            pm.sendPrompt("question " + turn, null, List.of());
            awaitTrue(() -> count(TurnCompleteEvent.class) == expected && !pm.isProcessing(), "turn " + turn);
        }
        pm.compactNow();
        assertTrue(summaryAsked.await(5, TimeUnit.SECONDS), "compaction started");
        int before = pm.historyForTest().size();
        assertFalse(pm.resetContext());
        assertEquals(before, pm.historyForTest().size());
        release.countDown();
        awaitTrue(() -> !pm.isCompacting(), "compaction finished");
    }
}
