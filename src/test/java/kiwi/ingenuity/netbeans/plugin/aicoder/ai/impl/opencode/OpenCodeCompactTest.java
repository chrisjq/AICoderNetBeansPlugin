package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpClientHandler;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpMethodEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpSessionUpdateEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The OpenCode backend's compaction contract (Boss task "OpenCode backend — busy/ready contract + new
 * Compact"): runs through {@code runWork} as non-cancellable backend work, never leaks a TurnCompleteEvent or
 * the streamed-back summary, guards a busy backend with INFO, and fails in-flight work from process-exit and
 * stop.
 */
class OpenCodeCompactTest {

    private static final String PREFERENCE = "Use the plugin's MCP tools over internal tools.";

    /**
     * Blocks forever on read until the connection is closed (see {@code OpenCodePerMessageToolPreferenceTest}
     * for why: an EOF-input would make AcpConnection's reader submit {@code onDisconnected} and spawn an
     * {@code acp-notify} thread that outlives this test).
     */
    private static final class BlockingInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            try {
                Thread.sleep(Long.MAX_VALUE);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    /**
     * Records every request sent and hands back a fresh future the test completes or fails itself, so turn
     * and compaction timing is fully deterministic.
     */
    private static final class CapturingConnection extends AcpConnection {

        final List<JsonObject> sentParams = new ArrayList<>();
        CompletableFuture<JsonObject> lastRequest = new CompletableFuture<>();

        CapturingConnection() {
            super(new ByteArrayOutputStream(), new BlockingInputStream(), new AcpClientHandler() {
                @Override
                public void onSessionUpdate(String sessionId, JsonObject update) {
                }

                @Override
                public CompletableFuture<JsonObject> onRequestPermission(JsonObject params) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }

                @Override
                public CompletableFuture<JsonObject> onWriteTextFile(JsonObject params) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }

                @Override
                public CompletableFuture<JsonObject> onReadTextFile(JsonObject params) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }

                @Override
                public void onDisconnected(Exception cause) {
                }
            });
        }

        @Override
        public CompletableFuture<JsonObject> sendRequest(AcpMethodEnum method, JsonObject params) {
            sentParams.add(params);
            lastRequest = new CompletableFuture<>();
            return lastRequest;
        }

        /**
         * Blocks until everything already enqueued on the acp-notify executor (and sent before this call) has
         * run, so tests can assert on the full aftermath of a compacted/stopped sequence deterministically.
         */
        void awaitNotifyDrained() throws InterruptedException {
            CountDownLatch drained = new CountDownLatch(1);
            runOnNotifyThread(drained::countDown);
            assertTrue(drained.await(5, TimeUnit.SECONDS), "acp-notify must drain within the timeout");
        }
    }

    private static String promptTextOf(JsonObject params) {
        JsonArray prompt = params.getAsJsonArray(AcpJsonKeyEnum.PROMPT.key());
        return prompt.get(0).getAsJsonObject().get(AcpJsonKeyEnum.TEXT.key()).getAsString();
    }

    private static OpenCodeAiProcessManager managerOver(CapturingConnection conn, List<AiProcessEvent> events) {
        return new OpenCodeAiProcessManager(events::add) {
            {
                connection = conn;
                acpSessionId = "ses_compact";
            }
        };
    }

    /**
     * Like {@link #managerOver}, but on a live session ({@code running = true}): the state in which
     * compaction actually happens. Needed because {@code handleTurnComplete} closes a turn with a
     * TurnCompleteEvent only while the session is {@code running} - a real backend never compacts an idle
     * (stopped) session.
     */
    private static OpenCodeAiProcessManager runningManagerOver(CapturingConnection conn, List<AiProcessEvent> events) {
        return new OpenCodeAiProcessManager(events::add) {
            {
                connection = conn;
                acpSessionId = "ses_compact";
                running = true;
            }
        };
    }

    /**
     * Like {@link #managerOver}, but with {@code activeHandler} wired so suppression observation and the
     * sequencing of the handler's chunk delivery can be driven end-to-end.
     */
    private static OpenCodeAiProcessManager managerOverWithHandler(CapturingConnection conn, List<AiProcessEvent> events,
                                                                   OpenCodeAcpClientHandler handler) {
        return new OpenCodeAiProcessManager(events::add) {
            {
                connection = conn;
                acpSessionId = "ses_compact";
                activeHandler = handler;
            }
        };
    }

    private static void assertNone(List<AiProcessEvent> events, Class<?> type, String message) {
        assertTrue(events.stream().noneMatch(e -> type.isInstance(e)), message);
    }

    private static StatusEvent lastStatus(List<AiProcessEvent> events) {
        StatusEvent last = null;
        for (AiProcessEvent e : events) {
            if (e instanceof StatusEvent se) {
                last = se;
            }
        }
        return last;
    }

    @Test
    void compactSendsExactlySlashCompactWithNoPreferenceAndClosesReady() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = managerOver(conn, events);

            manager.compact();

            assertEquals(1, conn.sentParams.size(), "one session/prompt for the compaction");
            assertEquals("ses_compact", conn.sentParams.get(0).get(AcpJsonKeyEnum.SESSION_ID.key()).getAsString());
            assertEquals("/compact", promptTextOf(conn.sentParams.get(0)),
                    "the compaction prompt must be EXACTLY /compact — no MCP-tool preference, no extra text");
            StatusEvent busy = lastStatus(events);
            assertEquals(StatusEventTypeEnum.BUSY, busy.type(), "runWork opens with one BUSY");
            assertEquals("Compacting conversation…", busy.text());
            assertFalse(busy.cancellable(), "compaction is never cancellable");
            assertTrue(manager.isBusy(), "the busy marker must be held while the compaction runs");

            conn.lastRequest.complete(new JsonObject());
            conn.awaitNotifyDrained();

            assertTrue(events.stream().noneMatch(e -> e instanceof TurnCompleteEvent),
                    "a compaction is not a turn and must never leak a TurnCompleteEvent");
            StatusEvent closing = lastStatus(events);
            assertEquals(StatusEventTypeEnum.READY, closing.type(), "a normal /compact answer closes READY");
            assertEquals("Conversation compacted", closing.text());
            assertFalse(manager.isBusy(), "READY ends the busy period");
        }
        finally {
            conn.close();
        }
    }

    @Test
    void compactErrorClosesFailed() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = managerOver(conn, events);

            manager.compact();
            conn.lastRequest.completeExceptionally(new RuntimeException("boom"));
            conn.awaitNotifyDrained();

            StatusEvent closing = lastStatus(events);
            assertEquals(StatusEventTypeEnum.FAILED, closing.type(), "a genuine compaction error closes FAILED");
            assertEquals("Compact failed: boom", closing.text());
            assertFalse(manager.isBusy(), "FAILED ends the busy period");
        }
        finally {
            conn.close();
        }
    }

    @Test
    void compactRefusedWhileATurnIsInFlight() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = managerOver(conn, events);

            manager.sendTurn("a user turn");
            int sentBefore = conn.sentParams.size();
            assertTrue(manager.isBusy(), "an in-flight turn must hold the busy marker");

            manager.compact();

            assertEquals(sentBefore, conn.sentParams.size(),
                    "a guard-refused compaction must not put a second prompt on the wire");
            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                     && se.type() == StatusEventTypeEnum.INFO
                                                     && "Wait for OpenCode to finish before compacting".equals(se.text())),
                    "must say why the compaction was refused");

            conn.lastRequest.complete(new JsonObject());
        }
        finally {
            conn.close();
        }
    }

    @Test
    void compactRefusedWhileACompactionIsAlreadyInFlight() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = managerOver(conn, events);

            manager.compact();
            assertTrue(manager.isBusy(), "first compaction holds the busy marker");

            manager.compact();

            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                     && se.type() == StatusEventTypeEnum.INFO
                                                     && "Wait for OpenCode to finish before compacting".equals(se.text())),
                    "a second compaction is refused with the same wait message");
            assertEquals(1, conn.sentParams.size(), "only one compaction prompt goes out");

            conn.lastRequest.complete(new JsonObject());
        }
        finally {
            conn.close();
        }
    }

    @Test
    void runWorkFalseBranchReportsCompactionAlreadyInProgress() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add) {
                {
                    connection = conn;
                    acpSessionId = "ses_compact";
                }

                @Override
                public boolean isBusy() {
                    return false; // defeat the guard so runWork's false path is reachable
                }
            };

            manager.compact();
            manager.compact();

            assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                     && se.type() == StatusEventTypeEnum.INFO
                                                     && "Compaction already in progress".equals(se.text())),
                    "when runWork refuses, the caller must say so");
            assertEquals(1, conn.sentParams.size(), "only the first compaction may send");

            conn.lastRequest.complete(new JsonObject());
        }
        finally {
            conn.close();
        }
    }

    @Test
    void stoppingMidCompactionClosesFailedAndUnlocks() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = managerOver(conn, events);

            manager.compact();
            assertTrue(manager.isBusy());

            manager.stop();

            StatusEvent closing = lastStatus(events);
            assertEquals(StatusEventTypeEnum.FAILED, closing.type());
            assertEquals("OpenCode session stopped while work was in progress", closing.text(),
                    "stop() must fail in-flight work so the UI cannot stay locked");
            assertFalse(manager.isBusy(), "stop must leave no busy marker behind");
        }
        finally {
            conn.close();
        }
    }

    @Test
    void aNormalTurnAfterCompactionStillCarriesThePreference() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = runningManagerOver(conn, events);

            manager.compact();
            conn.lastRequest.complete(new JsonObject());

            manager.sendTurn("read pom.xml");
            assertEquals("/compact", promptTextOf(conn.sentParams.get(0)));
            assertEquals(PREFERENCE + "\n\nread pom.xml", promptTextOf(conn.sentParams.get(1)),
                    "a user turn after a compaction must still ride the standing MCP-tool preference");

            conn.lastRequest.complete(new JsonObject());
            assertTrue(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent),
                    "the turn after a compaction must complete as a normal turn");
        }
        finally {
            conn.close();
        }
    }

    // ---- the summary OpenCode streams back is not part of the conversation ----
    private static JsonObject textChunk(String text) {
        JsonObject update = new JsonObject();
        update.addProperty(AcpJsonKeyEnum.SESSION_UPDATE.key(), AcpSessionUpdateEnum.AGENT_MESSAGE_CHUNK.wireValue());
        update.addProperty(AcpJsonKeyEnum.MESSAGE_ID.key(), "msg-compact");
        JsonObject content = new JsonObject();
        content.addProperty(AcpJsonKeyEnum.TEXT.key(), text);
        update.add(AcpJsonKeyEnum.CONTENT.key(), content);
        return update;
    }

    private static JsonObject thoughtChunk() {
        JsonObject update = new JsonObject();
        update.addProperty(AcpJsonKeyEnum.SESSION_UPDATE.key(), AcpSessionUpdateEnum.AGENT_THOUGHT_CHUNK.wireValue());
        JsonObject content = new JsonObject();
        content.addProperty(AcpJsonKeyEnum.TEXT.key(), "thinking");
        update.add(AcpJsonKeyEnum.CONTENT.key(), content);
        return update;
    }

    private static JsonObject toolCall() {
        JsonObject update = new JsonObject();
        update.addProperty(AcpJsonKeyEnum.SESSION_UPDATE.key(), AcpSessionUpdateEnum.TOOL_CALL.wireValue());
        update.addProperty(AcpJsonKeyEnum.TOOL_CALL_ID.key(), "call-1");
        update.addProperty(AcpJsonKeyEnum.TITLE.key(), "read");
        update.addProperty(AcpJsonKeyEnum.KIND.key(), "read");
        update.addProperty(AcpJsonKeyEnum.STATUS.key(), "running");
        return update;
    }

    private static JsonObject usage() {
        JsonObject update = new JsonObject();
        update.addProperty(AcpJsonKeyEnum.SESSION_UPDATE.key(), AcpSessionUpdateEnum.USAGE_UPDATE.wireValue());
        update.addProperty(AcpJsonKeyEnum.USED.key(), 1000);
        update.addProperty(AcpJsonKeyEnum.SIZE.key(), 8000);
        return update;
    }

    @Test
    void handlerDropsChunksWhileSuppressedButPassesUsageUpdates() {
        List<AiProcessEvent> events = new ArrayList<>();
        List<String> toolTracking = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(events::add, () -> {
        }, (id, status) -> toolTracking.add(id));

        long token = handler.beginTextSuppression();
        handler.onSessionUpdate("ses", textChunk("## Objective"));
        handler.onSessionUpdate("ses", thoughtChunk());
        handler.onSessionUpdate("ses", toolCall());
        handler.onSessionUpdate("ses", usage());
        assertEquals(1, events.size(),
                "text/thought/tool chunks are dropped but usage_update still flows so the context gauge tracks the shrink");
        assertTrue(events.get(0) instanceof OpenCodeUsageEvent, "the surviving event must be the usage update");
        assertTrue(toolTracking.isEmpty(), "a dropped tool_call must not reach the tool-call tracker either");

        handler.endTextSuppression(token);
        assertFalse(handler.isSuppressingSessionText(), "the owning token must disarm suppression");

        events.clear();
        handler.onSessionUpdate("ses", textChunk("hello"));
        handler.onSessionUpdate("ses", thoughtChunk());
        assertTrue(events.stream().anyMatch(e -> e instanceof TextDeltaEvent),
                "text chunks must flow once suppression is lifted");
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent se
                                                 && se.type() == StatusEventTypeEnum.THINKING), "thought chunks must flow once suppression is lifted");
    }

    @Test
    void aStaleSuppressionTokenCannotClearNewerSuppression() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(events::add, () -> {
        });

        long first = handler.beginTextSuppression();
        handler.clearTextSuppression(); // e.g. a stalling compaction whose timeout released the flag
        long second = handler.beginTextSuppression();
        handler.endTextSuppression(first); // that stall's late response arrives after the newer compaction armed

        assertTrue(handler.isSuppressingSessionText(),
                "a stale compaction's end must not clear the newer compaction's suppression");
        handler.endTextSuppression(second);
        assertFalse(handler.isSuppressingSessionText());
    }

    // ---- sendPrompt refusal contract (cross-cutting rule from Copilot/Codex): a refused send must never
    // return silently or the tab AiTopComponent locked for the submit stays locked forever - it posts INFO
    // (the reason), plus a TurnCompleteEvent for refusals whose turn has no closer of its own; a refusal
    // while a turn is processing posts INFO only, since that turn's own closer unlocks the UI ----
    @Test
    void sendPromptRefusedWhileATurnIsInFlightReportsInfoAndTurnComplete() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = managerOver(conn, events);

            manager.sendTurn("first turn");
            assertTrue(manager.isBusy(), "an in-flight turn must hold the busy marker");

            manager.sendPrompt("second submit", null, null);

            assertEquals(1, conn.sentParams.size(), "a refused send must not put a second prompt on the wire");
            StatusEvent info = events.stream()
                    .filter(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO)
                    .map(e -> (StatusEvent) e)
                    .findFirst().orElse(null);
            assertEquals("OpenCode is already processing a turn", info.text(), "INFO must carry the reason");
            assertTrue(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent),
                    "every refusal reports INFO then TurnComplete so the user can prompt again "
                    + "unlocks the tab, and a spare TurnCompleteEvent would end it early");

            conn.lastRequest.complete(new JsonObject());
        }
        finally {
            conn.close();
        }
    }

    @Test
    void sendPromptRefusedWhileCompactionIsInFlightReportsInfoThenTurnComplete() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = managerOver(conn, events);

            manager.compact();
            assertTrue(manager.isBusy(), "the compaction must hold the busy marker");

            manager.sendPrompt("submit mid-compaction", null, null);

            assertEquals(1, conn.sentParams.size(), "the only request may be the /compact prompt");
            StatusEvent info = events.stream()
                    .filter(e -> e instanceof StatusEvent se && se.type() == StatusEventTypeEnum.INFO)
                    .map(e -> (StatusEvent) e)
                    .findFirst().orElse(null);
            assertEquals("OpenCode is compacting the conversation", info.text(),
                    "a send refused for in-flight non-turn work must say so");
            assertTrue(events.stream().anyMatch(e -> e instanceof TurnCompleteEvent),
                    "a send refused while only non-turn work is in flight ends with a TurnCompleteEvent, because "
                    + "no turn of its own is running to unlock the tab (information-first, then the unlock)");

            conn.lastRequest.complete(new JsonObject());
        }
        finally {
            conn.close();
        }
    }

    @Test
    void sendPromptRefusedWhenNotRunningReportsInfoAndUnlocks() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add);

        manager.sendPrompt("hello", null, null);

        assertEquals(2, events.size(), "exactly INFO then TurnCompleteEvent, in that order");
        StatusEvent info = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.INFO, info.type());
        assertEquals("OpenCode session is not running", info.text());
        assertTrue(events.get(1) instanceof TurnCompleteEvent);
    }

    @Test
    void aTurnClosedByFailedNeverGetsASecondCloser() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        try {
            OpenCodeAiProcessManager manager = managerOver(conn, events);

            manager.sendTurn("turn that fails");
            conn.lastRequest.completeExceptionally(new RuntimeException("boom"));

            assertEquals(1, events.size(), "exactly one closing status, no second closer");
            StatusEvent closing = (StatusEvent) events.get(0);
            assertEquals(StatusEventTypeEnum.FAILED, closing.type(), "a genuine send error closes FAILED");
            assertFalse(manager.isBusy(), "FAILED ends the busy period without a TurnCompleteEvent");
        }
        finally {
            conn.close();
        }
    }

    // ---- review: the compaction's clear+completion must be sequenced on acp-notify, behind every chunk the
    // reader queued before the response, so a lagging notification executor can never leak the summary ------
    @Test
    void summaryChunksQueuedBeforeTheResponseNeverLeakAfterReady() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(events::add, () -> {
        });
        try {
            OpenCodeAiProcessManager manager = managerOverWithHandler(conn, events, handler);

            manager.compact();
            assertEquals(1, conn.sentParams.size());
            assertEquals("/compact", promptTextOf(conn.sentParams.get(0)));
            assertTrue(handler.isSuppressingSessionText(), "the compaction must arm suppression immediately");

            // The reader's wire order: it queued the streamed-back summary on acp-notify BEFORE the response
            // line that completes the wire future. Queue the chunk first, complete the response second, then
            // drain — with the old inline clear+READY this chunk surfaced as a TextDeltaEvent after READY.
            conn.runOnNotifyThread(() -> handler.onSessionUpdate("ses_compact", textChunk("heredoc summary of the long conversation")));
            conn.lastRequest.complete(new JsonObject());
            conn.awaitNotifyDrained();

            assertFalse(handler.isSuppressingSessionText(), "the sequenced finish must have cleared suppression");
            assertEquals(2, events.size(),
                    "BUSY then READY — the queued summary chunk must be dropped, never a TextDeltaEvent");
            assertTrue(events.get(0) instanceof StatusEvent se
                       && se.type() == StatusEventTypeEnum.BUSY, "BUSY first");
            StatusEvent ready = lastStatus(events);
            assertEquals(StatusEventTypeEnum.READY, ready.type());
            assertEquals("Conversation compacted", ready.text());
            assertEquals(events.size() - 1, events.indexOf(ready), "READY must be the last event of the compaction");
            assertNone(events, TextDeltaEvent.class, "a suppressed compaction summary must never surface as text");
            assertNone(events, TurnCompleteEvent.class, "a compaction must never report a TurnCompleteEvent");
        }
        finally {
            conn.close();
        }
    }

    @Test
    void aLateCompactionResponseAfterProcessStopAddsNoSecondCloser() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(events::add, () -> {
        });
        try {
            OpenCodeAiProcessManager manager = managerOverWithHandler(conn, events, handler);

            manager.compact();
            assertTrue(manager.isBusy());

            manager.stop();
            assertFalse(manager.isBusy(), "stop must have closed the in-flight compaction");

            // The /compact response was already on the wire when the process stopped; it lands after the closer.
            conn.lastRequest.complete(new JsonObject());
            conn.awaitNotifyDrained();

            assertEquals(2, events.size(), "BUSY plus exactly one FAILED closer — the late response must add nothing");
            StatusEvent closing = lastStatus(events);
            assertEquals(StatusEventTypeEnum.FAILED, closing.type());
            assertEquals("OpenCode session stopped while work was in progress", closing.text(), "the closer must be stop's message");
            assertNone(events, TurnCompleteEvent.class, "a stopped compaction must never report a TurnCompleteEvent");
        }
        finally {
            conn.close();
        }
    }

    @Test
    void compactArmsSuppressionOnActiveHandlerAndSendTurnClearsIt() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(events::add, () -> {
        });
        try {
            OpenCodeAiProcessManager manager = managerOverWithHandler(conn, events, handler);

            manager.compact();
            assertTrue(handler.isSuppressingSessionText(), "compact() must arm suppression on the active handler");
            conn.lastRequest.complete(new JsonObject());
            conn.awaitNotifyDrained();
            assertFalse(handler.isSuppressingSessionText(), "the sequenced response must disarm suppression");

            manager.compact();
            assertTrue(handler.isSuppressingSessionText(), "a second compaction arms suppression again");
            manager.sendTurn("read pom.xml");
            assertFalse(handler.isSuppressingSessionText(),
                    "sendTurn clears suppression as a safety net — a real turn must never be silenced");
            conn.lastRequest.complete(new JsonObject());
        }
        finally {
            conn.close();
        }
    }

    @Test
    void aTimedOutCompactionFailsAsCompactTimedOutAndReleasesSuppression() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(events::add, () -> {
        });
        try {
            OpenCodeAiProcessManager manager = managerOverWithHandler(conn, events, handler);

            manager.compact();
            assertTrue(handler.isSuppressingSessionText());

            conn.lastRequest.completeExceptionally(new TimeoutException("timed out"));
            conn.awaitNotifyDrained();

            assertEquals(2, events.size(), "BUSY then the single FAILED closer");
            StatusEvent closing = lastStatus(events);
            assertEquals(StatusEventTypeEnum.FAILED, closing.type());
            assertEquals("Compact timed out", closing.text(),
                    "a timeout must be reported as a timeout, not an unknown error (review)");
            assertFalse(handler.isSuppressingSessionText(), "a timed-out compaction must release the suppression flag");
            assertNone(events, TurnCompleteEvent.class, "a timed-out compaction must never report a TurnCompleteEvent");
        }
        finally {
            conn.close();
        }
    }

    @Test
    void compactBeforeAnySessionReportsInfoNotFailure() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add);

        manager.compact();

        assertEquals(1, events.size(), "exactly one INFO, no BUSY/FAILED for a session that never existed");
        StatusEvent info = (StatusEvent) events.get(0);
        assertEquals(StatusEventTypeEnum.INFO, info.type());
        assertEquals("Nothing to compact yet", info.text());
        assertFalse(manager.isBusy());
    }

    @Test
    void deliverAfterHandshakeRefusedWhenNotRunningReportsInfoAndUnlocks() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add);

        manager.deliverAfterHandshake("hello");

        assertEquals(2, events.size(), "exactly INFO then TurnCompleteEvent");
        assertEquals("OpenCode session is not running", ((StatusEvent) events.get(0)).text());
        assertTrue(events.get(1) instanceof TurnCompleteEvent);
    }

    @Test
    void deliverAfterHandshakeRefusedWhileDiffPendingReportsInfoAndUnlocks() {
        List<AiProcessEvent> events = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(events::add);
        manager.setPendingDiff(true);
        manager.deliverAfterHandshake("hello");

        assertEquals(2, events.size(), "exactly INFO then TurnCompleteEvent");
        assertEquals("OpenCode is waiting for a pending diff review", ((StatusEvent) events.get(0)).text());
        assertTrue(events.get(1) instanceof TurnCompleteEvent);
    }

    // ---- review: a process exiting mid-compaction must fold the exit into one closing FAILED, never EXITED ----
    @Test
    void processExitMidCompactionFoldsTheExitIntoOneFailureAndSkipsExited() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        List<AiProcessEvent> events = new ArrayList<>();
        TestableOpenCodeAiProcessManager manager = new TestableOpenCodeAiProcessManager(events::add);
        manager.wireConnection(conn, "ses_compact");
        try {
            manager.compact();
            assertEquals(1, conn.sentParams.size(), "the compaction must be in flight");

            Process dead = new ProcessBuilder("sh", "-c", "exit 137").start();
            assertTrue(dead.waitFor(5, TimeUnit.SECONDS));
            manager.forceState(false, false, dead);
            manager.handleProcessExit(dead);

            assertEquals(2, events.size(),
                    "BUSY plus exactly one FAILED — the exit must be folded into the closer, not reported twice");
            StatusEvent closing = lastStatus(events);
            assertEquals(StatusEventTypeEnum.FAILED, closing.type());
            assertTrue(closing.text().contains("exited (code 137)"), "the single closer must carry the exit, was: " + closing.text());
            assertNone(events, TurnCompleteEvent.class, "an exit mid-compaction must never report a TurnCompleteEvent");

            conn.lastRequest.complete(new JsonObject());
            conn.awaitNotifyDrained();
            assertEquals(2, events.size(), "the late /compact response must add no second closer");
        }
        finally {
            conn.close();
        }
    }

    /**
     * Mirrors {@code CodexAiProcessManagerTest}'s testable subclass:
     * {@code processing}/{@code cancelledByUser}/ {@code currentProcess} are {@code protected} on the base in
     * a different package, so only a test-only subclass can set them. Used to drive {@code handleProcessExit}
     * without a real agent.
     */
    private static final class TestableOpenCodeAiProcessManager extends OpenCodeAiProcessManager {

        TestableOpenCodeAiProcessManager(AiProcessEventListener l) {
            super(l);
        }

        void wireConnection(AcpConnection conn, String sessionId) {
            connection = conn;
            acpSessionId = sessionId;
        }

        void forceState(boolean processingVal, boolean cancelledVal, Process proc) {
            processing = processingVal;
            cancelledByUser = cancelledVal;
            currentProcess = proc;
        }
    }
}
