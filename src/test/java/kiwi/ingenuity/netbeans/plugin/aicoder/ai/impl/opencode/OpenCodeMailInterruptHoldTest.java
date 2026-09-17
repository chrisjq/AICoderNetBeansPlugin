package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F5: a Mail interrupt must never abort a tool call this plugin is itself servicing — OpenCode treats
 * {@code session/cancel} as "the user doesn't want to proceed" and cuts whatever the agent is waiting on, tool calls
 * included. These tests drive {@link OpenCodeAiProcessManager} like {@code OpenCodeAiProcessManagerTest} does: a
 * pipe-backed {@link AcpConnection} plus a {@link RecordingFakeAgent} stand in for the live process, and the in-flight
 * tool-call lifecycle is fed in through {@link OpenCodeAiProcessManager#trackToolCallLifecycle} (the same
 * package-private method the handler forwards to) — no real {@code opencode acp} is launched.
 */
class OpenCodeMailInterruptHoldTest {

    private static void awaitTrue(BooleanSupplier cond, String desc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timeout waiting for " + desc);
            }
            Thread.sleep(20);
        }
    }

    private TestableOpenCodeAiProcessManager manager;
    private RecordingFakeAgent agent;
    private Thread agentThread;
    private AcpConnection conn;
    private PipedOutputStream agentOut;

    @BeforeEach
    void setup() throws IOException {
        PipedInputStream agentIn = new PipedInputStream(65536);
        PipedOutputStream pluginOut = new PipedOutputStream(agentIn);
        PipedInputStream pluginIn = new PipedInputStream(65536);
        agentOut = new PipedOutputStream(pluginIn);
        agent = new RecordingFakeAgent(agentIn, agentOut);
        agentThread = new Thread(agent, "fake-acp-agent-mail-hold");
        agentThread.setDaemon(true);
        agentThread.start();
        conn = new AcpConnection(pluginOut, pluginIn, new OpenCodeAcpClientHandler(e -> {
                         }, () -> {
                                                                           }));
        manager = new TestableOpenCodeAiProcessManager(e -> {
        });
        manager.armTurn(true);
        manager.connection = conn;
        manager.acpSessionId = "ses_mailholdtest";
    }

    @AfterEach
    void teardown() throws InterruptedException {
        try {
            agentOut.close();
        }
        catch (IOException ignored) {
            // already closed — fine
        }
        if (conn != null) {
            conn.close();
        }
        agentThread.join(5000);
    }

    // ---- idle / no in-flight call: today's immediate-send behaviour is preserved ----
    @Test
    void mailInterruptIsSentImmediatelyWhenNoToolCallInFlight() throws InterruptedException {
        manager.interrupt(InterruptTypeEnum.Mail);

        awaitTrue(() -> agent.countMethod("session/cancel") == 1, "cancel sent immediately when idle");
        assertEquals(0, manager.getInFlightToolCalls());
        assertFalse(manager.isMailInterruptPending());
    }

    @Test
    void mailInterruptIsIgnoredWhenNoTurnIsInFlight() throws InterruptedException {
        manager.armTurn(false);
        manager.interrupt(InterruptTypeEnum.Mail);

        Thread.sleep(100);
        assertEquals(0, agent.countMethod("session/cancel"),
                     "no cancel when there is no turn to interrupt — the queued mail arrives via the normal inbox flush");
    }

    // ---- hold + flush on a terminal status ----
    @Test
    void mailInterruptIsHeldWhileToolCallInFlightThenFlushedOnCompleted() throws InterruptedException {
        manager.trackToolCallLifecycle("call_1", "pending");
        assertEquals(1, manager.getInFlightToolCalls());

        manager.interrupt(InterruptTypeEnum.Mail);
        assertTrue(manager.isMailInterruptPending(), "must be held while the tool call is in flight");

        // Give the (incorrect) immediate-send behaviour a chance to happen before asserting its absence.
        Thread.sleep(100);
        assertEquals(0, agent.countMethod("session/cancel"), "must NOT cancel while the call is still in flight");

        // Repeated in_progress for the same id must not double-count.
        manager.trackToolCallLifecycle("call_1", "in_progress");
        assertEquals(1, manager.getInFlightToolCalls());

        manager.trackToolCallLifecycle("call_1", "completed");
        assertEquals(0, manager.getInFlightToolCalls());
        assertFalse(manager.isMailInterruptPending());
        awaitTrue(() -> agent.countMethod("session/cancel") == 1, "interrupt flushed once the call completed");
    }

    @Test
    void mailInterruptIsHeldThenFlushedOnFailed() throws InterruptedException {
        manager.trackToolCallLifecycle("call_1", "in_progress");
        manager.interrupt(InterruptTypeEnum.Mail);
        Thread.sleep(100);
        assertEquals(0, agent.countMethod("session/cancel"), "must NOT cancel while the call is in flight");

        manager.trackToolCallLifecycle("call_1", "failed");
        assertEquals(0, manager.getInFlightToolCalls());
        assertFalse(manager.isMailInterruptPending());
        awaitTrue(() -> agent.countMethod("session/cancel") == 1, "failed is as terminal as completed");
    }

    // ---- turn end clears the hold WITHOUT sending ----
    @Test
    void mailInterruptHoldIsClearedOnTurnCompleteWithoutSending() throws InterruptedException {
        manager.trackToolCallLifecycle("call_1", "pending");
        manager.interrupt(InterruptTypeEnum.Mail);
        assertTrue(manager.isMailInterruptPending());

        manager.handleTurnComplete(new JsonObject());

        assertFalse(manager.isMailInterruptPending(), "turn end must clear the held interrupt");
        assertEquals(0, manager.getInFlightToolCalls());
        assertEquals(0, agent.countMethod("session/cancel"), "turn end must not send a moot interrupt");
    }

    @Test
    void mailInterruptHoldIsClearedOnTurnErrorWithoutSending() throws InterruptedException {
        manager.trackToolCallLifecycle("call_1", "pending");
        manager.interrupt(InterruptTypeEnum.Mail);
        assertTrue(manager.isMailInterruptPending());

        manager.handleTurnError(new RuntimeException("boom"));

        assertFalse(manager.isMailInterruptPending());
        assertEquals(0, manager.getInFlightToolCalls());
        assertEquals(0, agent.countMethod("session/cancel"), "turn error must not send a moot interrupt");
    }

    // ---- safety valve backstop ----
    @Test
    void safetyValveDeliversHeldInterruptIfToolCallNeverResolves() throws InterruptedException {
        manager.mailInterruptSafetyValveMillis = 150;
        manager.trackToolCallLifecycle("call_1", "pending");
        manager.interrupt(InterruptTypeEnum.Mail);
        assertEquals(0, agent.countMethod("session/cancel"), "must be held immediately after the request");

        // No terminal status and no turn end ever arrive — only the safety valve can flush this.
        awaitTrue(() -> agent.countMethod("session/cancel") == 1, "safety valve must deliver the held interrupt");
        assertFalse(manager.isMailInterruptPending());
        assertEquals(0, manager.getInFlightToolCalls());
    }

    @Test
    void secondMailInterruptWhileAlreadyHeldDoesNotStartASecondWatchdog() throws InterruptedException {
        manager.mailInterruptSafetyValveMillis = 150;
        manager.trackToolCallLifecycle("call_1", "in_progress");
        manager.interrupt(InterruptTypeEnum.Mail);
        manager.interrupt(InterruptTypeEnum.Mail);
        assertEquals(1, manager.mailInterruptSafetyValveStarts,
                     "a second Mail while one is held must not start a second watchdog");

        awaitTrue(() -> agent.countMethod("session/cancel") == 1,
                  "exactly one cancel resolves any number of queued messages");
        assertFalse(manager.isMailInterruptPending());
        assertEquals(0, manager.getInFlightToolCalls());
    }

    // ---- counting rules ----
    @Test
    void repeatedInProgressUpdatesForSameCallAreCountedOnce() {
        manager.trackToolCallLifecycle("call_1", "pending");
        manager.trackToolCallLifecycle("call_1", "in_progress");
        manager.trackToolCallLifecycle("call_1", "in_progress");
        assertEquals(1, manager.getInFlightToolCalls());

        manager.trackToolCallLifecycle("call_1", "failed");
        assertEquals(0, manager.getInFlightToolCalls());
    }

    @Test
    void twoParallelToolCallsReleaseIndependently() throws InterruptedException {
        manager.trackToolCallLifecycle("call_a", "pending");
        manager.trackToolCallLifecycle("call_b", "pending");
        assertEquals(2, manager.getInFlightToolCalls());

        manager.interrupt(InterruptTypeEnum.Mail);
        assertTrue(manager.isMailInterruptPending());

        manager.trackToolCallLifecycle("call_a", "completed");
        assertEquals(1, manager.getInFlightToolCalls(), "one call left — the interrupt must stay held");
        assertTrue(manager.isMailInterruptPending());
        assertEquals(0, agent.countMethod("session/cancel"));

        manager.trackToolCallLifecycle("call_b", "failed");
        awaitTrue(() -> agent.countMethod("session/cancel") == 1, "flush only after the last call releases");
        assertFalse(manager.isMailInterruptPending());
        assertEquals(0, manager.getInFlightToolCalls());
    }

    @Test
    void unknownStatusLeavesCountUnchangedAndSafetyValveIsTheBackstop() throws InterruptedException {
        manager.mailInterruptSafetyValveMillis = 150;
        manager.trackToolCallLifecycle("call_1", "pending");
        manager.interrupt(InterruptTypeEnum.Mail);

        // "cancelled" is NOT part of the documented ACP vocabulary (design doc §12); it must not
        // be guessed terminal — the count stays, the hold stays, and the safety valve delivers.
        manager.trackToolCallLifecycle("call_1", "cancelled");
        assertEquals(1, manager.getInFlightToolCalls());
        assertTrue(manager.isMailInterruptPending());
        assertEquals(0, agent.countMethod("session/cancel"));

        awaitTrue(() -> agent.countMethod("session/cancel") == 1, "safety valve delivers despite the unknown status");
        assertFalse(manager.isMailInterruptPending());
    }

    // ---- handler forwards both update types to the tracker ----
    @Test
    void handlerForwardsToolCallAndToolCallUpdateToTracker() {
        List<List<String>> tracked = new ArrayList<>();
        OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(e -> {
        }, () -> {
                                                                }, (toolCallId, status) -> tracked.add(List.of(toolCallId, status)));

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("sessionUpdate", "tool_call");
        toolCall.addProperty("toolCallId", "call_1");
        toolCall.addProperty("title", "read");
        toolCall.addProperty("kind", "read");
        toolCall.addProperty("status", "pending");
        handler.onSessionUpdate("ses_x", toolCall);

        JsonObject toolCallUpdate = new JsonObject();
        toolCallUpdate.addProperty("sessionUpdate", "tool_call_update");
        toolCallUpdate.addProperty("toolCallId", "call_1");
        toolCallUpdate.addProperty("status", "in_progress");
        handler.onSessionUpdate("ses_x", toolCallUpdate);

        assertEquals(List.of(List.of("call_1", "pending"), List.of("call_1", "in_progress")), tracked,
                     "both update kinds must reach the tracker");
    }

    /**
     * Minimal fake ACP agent over piped streams: records every incoming message in arrival order and answers every
     * request (a message carrying both id and method) with an empty success result — mirroring the identical harness in
     * {@code OpenCodeAiProcessManagerTest}.
     */
    private static final class RecordingFakeAgent implements Runnable {

        private final BufferedReader in;
        private final PipedOutputStream out;
        private final List<JsonObject> messages = new ArrayList<>();
        private volatile boolean reachedEof = false;

        RecordingFakeAgent(PipedInputStream pluginToAgent, PipedOutputStream agentToPlugin) {
            this.in = new BufferedReader(new InputStreamReader(pluginToAgent, StandardCharsets.UTF_8));
            this.out = agentToPlugin;
        }

        int countMethod(String method) {
            int count = 0;
            synchronized (messages) {
                for (JsonObject m : messages) {
                    if (m.has("method") && method.equals(m.get("method").getAsString())) {
                        count++;
                    }
                }
            }
            return count;
        }

        boolean reachedEof() {
            return reachedEof;
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
                    synchronized (messages) {
                        messages.add(msg);
                    }
                    if (msg.has("id") && msg.has("method")) {
                        JsonObject response = new JsonObject();
                        response.addProperty("jsonrpc", "2.0");
                        response.addProperty("id", msg.get("id").getAsLong());
                        response.add("result", new JsonObject());
                        out.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
                reachedEof = true;
            }
            catch (IOException e) {
                // Pipe torn down under us — expected once the plugin side closes.
            }
        }
    }

    /**
     * Wraps the protected AiProcessManager lifecycle fields (running/processing) in package-private setters, exactly
     * like {@code OpenCodeAiProcessManagerHandshakeTest.RecordingManager} — the test class itself is not a subclass of
     * {@code AiProcessManager}, so it cannot touch those fields directly.
     */
    private static class TestableOpenCodeAiProcessManager extends OpenCodeAiProcessManager {

        TestableOpenCodeAiProcessManager(AiProcessEventListener listener) {
            super(listener);
        }

        void armTurn(boolean turnInFlight) {
            running = true;
            processing = turnInFlight;
        }
    }
}
