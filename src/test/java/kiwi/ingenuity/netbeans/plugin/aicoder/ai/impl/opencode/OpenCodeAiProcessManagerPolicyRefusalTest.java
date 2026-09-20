package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent.Refusal;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * When the read policy's refusal is what ended the agent's turn, the manager tells the UI so, just before it reports
 * the turn complete. The whole decision is made here, from three facts at the end of the turn: a refusal was recorded,
 * the turn ended {@code cancelled}, and the user did not press Stop.
 *
 * <p>
 * How often the agent may then be resumed is the UI's business and is pinned in
 * {@code AiTopComponentPolicyRefusalWiringTest}; the manager never caps and never delivers anything itself.
 */
class OpenCodeAiProcessManagerPolicyRefusalTest {

    private static final String FILE = "/etc/hostname";
    private static final String OTHER = "/etc/passwd";

    /**
     * A manager and a handler that can be made to refuse a read, with everything the manager tells the UI recorded on
     * one timeline, so ORDER can be asserted.
     */
    private static final class Rig {

        final List<String> timeline = new CopyOnWriteArrayList<>();
        final List<AiProcessEvent> events = new CopyOnWriteArrayList<>();
        final OpenCodeAcpClientHandler handler = new OpenCodeAcpClientHandler(e -> {
        }, () -> {
                                                                      }, null,
                                                                              new OpenCodeAcpClientHandler.SessionFileScope(
                                                                                      p -> false, p -> false, p -> "refused: " + p));
        final OpenCodeAiProcessManager manager;

        // Not named like the manager's own fields: inside the anonymous subclass below a simple name resolves to the
        // inherited field, which would turn "running = running" into a self-assignment.
        Rig(boolean startRunning, boolean userStopped) {
            manager = new OpenCodeAiProcessManager(e -> {
                events.add(e);
                timeline.add(e instanceof TurnCompleteEvent ? "turn-complete"
                             : e instanceof PolicyRefusalEvent ? "policy-refusal"
                               : e instanceof StatusEvent ? "status" : e.getClass().getSimpleName());
            }) {
                {
                    this.running = startRunning;
                    this.processing = true;
                    this.cancelledByUser = userStopped;
                }
            };
            manager.activeHandler = handler;
        }

        Rig() {
            this(true, false);
        }

        /**
         * The agent reads a file the policy refuses, exactly as OpenCode's ACP agent asks: an external_directory ask
         * traced back to an earlier read tool call.
         */
        void refuse(String path) throws Exception {
            JsonObject update = new JsonObject();
            update.addProperty("sessionUpdate", "tool_call");
            update.addProperty("toolCallId", "call-" + path);
            update.addProperty("title", "read");
            update.addProperty("kind", "read");
            update.addProperty("status", "pending");
            handler.onSessionUpdate("ses_x", update);

            JsonObject rawInput = new JsonObject();
            rawInput.addProperty("filepath", path);
            rawInput.addProperty("parentDir", "/etc");
            JsonArray locations = new JsonArray();
            JsonObject location = new JsonObject();
            location.addProperty("path", path);
            locations.add(location);
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("toolCallId", "call-" + path);
            toolCall.addProperty("title", "/etc");
            toolCall.addProperty("kind", "other");
            toolCall.addProperty("status", "pending");
            toolCall.add("locations", locations);
            toolCall.add("rawInput", rawInput);
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", "ses_abc");
            params.add("toolCall", toolCall);

            assertEquals("reject", handler.onRequestPermission(params).get().getAsJsonObject("outcome")
                         .get("optionId").getAsString(), "the harness must produce a real refusal");
        }

        void complete(String stopReason) {
            JsonObject result = new JsonObject();
            if (stopReason != null) {
                result.addProperty("stopReason", stopReason);
            }
            manager.handleTurnComplete(result);
        }

        List<Refusal> reported() {
            return events.stream().filter(e -> e instanceof PolicyRefusalEvent)
                    .flatMap(e -> ((PolicyRefusalEvent) e).refusals().stream()).toList();
        }
    }

    // ---- Reporting ----
    @Test
    void aRefusalThatEndedTheTurnIsReportedBeforeTheTurnCompleteEventNeverAfter() throws Exception {
        Rig rig = new Rig();
        rig.refuse(FILE);

        rig.complete("cancelled");

        assertEquals(List.of("policy-refusal", "turn-complete"), rig.timeline,
                     "the UI decides at turn-complete whether to carry on, so it must already know");
        assertEquals(List.of(new Refusal(FILE, "refused: " + FILE)), rig.reported(),
                     "the path and the shared refusal text, verbatim");
    }

    /**
     * Several reads refused in one turn arrive together, each path still paired with its own reason.
     */
    @Test
    void twoRefusedPathsInOneTurnAreReportedTogetherEachWithItsOwnReason() throws Exception {
        Rig rig = new Rig();
        rig.refuse(FILE);
        rig.refuse(OTHER);

        rig.complete("cancelled");

        assertEquals(List.of("policy-refusal", "turn-complete"), rig.timeline, "one event for the turn, not one per path");
        assertEquals(List.of(new Refusal(FILE, "refused: " + FILE), new Refusal(OTHER, "refused: " + OTHER)),
                     rig.reported(), "in the order refused, path and reason together");
    }

    @Test
    void theReportIsAnEventTheUiCanIgnoreAndNothingElseReachesTheUi() throws Exception {
        Rig rig = new Rig();
        rig.refuse(FILE);

        rig.complete("cancelled");

        assertEquals(2, rig.events.size(), "the report and the ordinary turn-complete, nothing more");
        assertInstanceOf(PolicyRefusalEvent.class, rig.events.get(0));
        assertInstanceOf(TurnCompleteEvent.class, rig.events.get(1));
    }

    // ---- When it must NOT fire ----
    @Test
    void aTurnTheUserStoppedIsNeverReportedEvenWithARefusalOnRecord() throws Exception {
        Rig rig = new Rig(true, true);
        rig.refuse(FILE);

        rig.complete("cancelled");

        assertEquals(List.of("turn-complete"), rig.timeline, "Stop ended that turn because the user asked");
        assertTrue(rig.handler.consumeTurnRefusals().isEmpty(), "and the refusal is not carried into the next turn");
    }

    @Test
    void aTurnThatEndedNormallyIsNeverReportedBecauseAgentsOnV1CarryOnAfterARefusal() throws Exception {
        Rig rig = new Rig();
        rig.refuse(FILE);

        rig.complete("end_turn");

        assertEquals(List.of("turn-complete"), rig.timeline);
        assertTrue(rig.handler.consumeTurnRefusals().isEmpty(), "consumed anyway, never carried to a later turn");
    }

    @Test
    void onlyACancelledStopReasonCountsAndAnythingElseOrAbsentDoesNot() throws Exception {
        for (String reason : new String[]{"end_turn", "max_tokens", "max_turn_requests", "refusal", "no_such_reason", null}) {
            Rig rig = new Rig();
            rig.refuse(FILE);

            rig.complete(reason);

            assertEquals(List.of("turn-complete"), rig.timeline, "stopReason=" + reason);
        }
        Rig rig = new Rig();
        rig.refuse(FILE);
        JsonObject weird = new JsonObject();
        weird.add("stopReason", new JsonObject());
        rig.manager.handleTurnComplete(weird);
        assertEquals(List.of("turn-complete"), rig.timeline, "a stopReason that is not a string is not 'cancelled'");
    }

    @Test
    void aCancelledTurnWithNoRefusalIsUntouched() {
        Rig rig = new Rig();

        rig.complete("cancelled");

        assertEquals(List.of("turn-complete"), rig.timeline, "nothing refused, nothing to say");
    }

    @Test
    void aManagerThatIsNotRunningOrHasNoHandlerStaysQuiet() throws Exception {
        Rig stopped = new Rig(false, false);
        stopped.refuse(FILE);
        stopped.complete("cancelled");
        assertTrue(stopped.timeline.isEmpty(), "a stopped manager reports nothing at all: " + stopped.timeline);
        assertTrue(stopped.handler.consumeTurnRefusals().isEmpty(), "but does not keep the refusal for later");

        Rig noHandler = new Rig();
        noHandler.manager.activeHandler = null;
        noHandler.complete("cancelled");
        assertEquals(List.of("turn-complete"), noHandler.timeline);
    }

    @Test
    void aRefusalIsNeverCarriedIntoALaterTurn() throws Exception {
        Rig rig = new Rig();
        rig.refuse(FILE);
        rig.complete("end_turn");
        rig.timeline.clear();

        rig.complete("cancelled");

        assertEquals(List.of("turn-complete"), rig.timeline,
                     "the second turn refused nothing; the first turn's refusal must not be reported against it");
    }

    /**
     * The manager applies no cap: the guard against a loop is the UI's budget, refilled by the user. Pinned so the
     * responsibility does not quietly drift to two places that then disagree.
     */
    @Test
    void theManagerNeverCapsAndReportsEveryRefusalKilledTurn() throws Exception {
        Rig rig = new Rig();

        for (int turn = 0; turn < 20; turn++) {
            rig.refuse(FILE + turn);
            rig.complete("cancelled");
        }

        assertEquals(20, rig.timeline.stream().filter(t -> t.equals("policy-refusal")).count());
    }

    // ---- The error channel ----
    @Test
    void aCancellationReportedAsAnErrorFollowsTheSameRuleAndIsTestedBothWays() throws Exception {
        Rig reported = new Rig();
        reported.refuse(FILE);
        reported.manager.handleTurnError(new AcpException(-32800, "cancelled"));
        assertEquals(List.of("policy-refusal", "turn-complete"), reported.timeline,
                     "-32800 is the same cancelled turn, so a refusal on record is reported, before turn-complete");
        assertEquals(List.of(new Refusal(FILE, "refused: " + FILE)), reported.reported());

        Rig stopped = new Rig(true, true);
        stopped.refuse(FILE);
        stopped.manager.handleTurnError(new AcpException(-32800, "cancelled"));
        assertEquals(List.of("turn-complete"), stopped.timeline, "unless the user pressed Stop");

        Rig none = new Rig();
        none.manager.handleTurnError(new AcpException(-32800, "cancelled"));
        assertEquals(List.of("turn-complete"), none.timeline, "and nothing refused means nothing to say");
    }

    @Test
    void aTurnThatFailedForAnyOtherReasonIsNotOneARefusalEndedAndForgetsItsRefusals() throws Exception {
        for (Throwable failure : new Throwable[]{new AcpException(-32000, "auth required"),
            new RuntimeException("unexpected failure")}) {
            Rig rig = new Rig();
            rig.refuse(FILE);

            rig.manager.handleTurnError(failure);

            assertEquals(List.of("status"), rig.timeline, "only the FAILED status, no report and no turn-complete: " + failure);
            assertTrue(rig.handler.consumeTurnRefusals().isEmpty(), "and its refusals are not carried forward");
        }
    }

    // ---- Diagnostics ----
    @Test
    void theReportIsLoggedOnlyUnderTheDebugSettingAndNeverShown() throws Exception {
        for (boolean debug : new boolean[]{true, false}) {
            Rig rig = new Rig();
            rig.refuse(FILE);

            List<String> log = managerLogDuring(debug, () -> rig.complete("cancelled"));

            assertEquals(debug, log.stream().anyMatch(m -> m.contains("policy refusal ended the turn")), String.valueOf(log));
            assertEquals(List.of("policy-refusal", "turn-complete"), rig.timeline, "the log never changes what is posted");
        }
    }

    @FunctionalInterface
    private interface Action {

        void run() throws Exception;
    }

    private static List<String> managerLogDuring(boolean debugJson, Action action) throws Exception {
        Logger logger = Logger.getLogger(OpenCodeAiProcessManager.class.getName());
        List<String> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(new SimpleFormatter().formatMessage(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Level previousLevel = logger.getLevel();
        boolean previousDebug = PluginSettings.isDebugJson();
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
        PluginSettings.setDebugJson(debugJson);
        try {
            action.run();
        }
        finally {
            PluginSettings.setDebugJson(previousDebug);
            logger.setLevel(previousLevel);
            logger.removeHandler(capture);
        }
        return messages;
    }
}
