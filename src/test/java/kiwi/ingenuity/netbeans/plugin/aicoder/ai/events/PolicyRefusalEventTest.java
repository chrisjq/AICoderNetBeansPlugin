package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PolicyRefusalEvent.Refusal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The notice is one short framing clause, the refused path, and the refusal text exactly as {@code GetFileContent}
 * returns it — one entry per path, never merged — followed by the single caveat about other cut-off calls. Whether the
 * user sees any of it is pinned in {@code AiTopComponentPolicyRefusalWiringTest}.
 */
class PolicyRefusalEventTest {

    private static final Refusal HOSTNAME = new Refusal("/etc/hostname",
                                                        "Access denied: /etc/hostname is outside the allowed project scope for this session.");
    private static final Refusal PASSWD = new Refusal("/etc/passwd",
                                                      "Access denied: /etc/passwd is outside the allowed project scope for this session.");

    @Test
    void aSingleRefusalIsTheFramingThePathTheVerbatimTextThenTheCaveat() {
        assertEquals("The previous tool result for /etc/hostname was not a user rejection but automatic: "
                + HOSTNAME.reason() + "\n" + PolicyRefusalEvent.CUT_OFF_CALLS,
                     PolicyRefusalEvent.compose(List.of(HOSTNAME)));
    }

    /**
     * Several refused paths in one turn: each keeps its own entry, path and reason together, so the agent can tell
     * which reason belongs to which file. Nothing is merged under a shared reason and nothing is left for the agent to
     * infer.
     */
    @Test
    void twoRefusedPathsEachGetTheirOwnEntryWithTheirOwnReasonCorrectlyPaired() {
        String notice = PolicyRefusalEvent.compose(List.of(HOSTNAME, PASSWD));
        String[] lines = notice.split("\n");

        assertEquals(3, lines.length, "one line per refused path, then the caveat: " + notice);
        assertEquals(PolicyRefusalEvent.entry(HOSTNAME), lines[0]);
        assertEquals(PolicyRefusalEvent.entry(PASSWD), lines[1]);
        assertEquals(PolicyRefusalEvent.CUT_OFF_CALLS, lines[2]);
        assertTrue(lines[0].contains("/etc/hostname") && lines[0].contains(HOSTNAME.reason()), lines[0]);
        assertTrue(lines[1].contains("/etc/passwd") && lines[1].contains(PASSWD.reason()), lines[1]);
        assertFalse(lines[0].contains("/etc/passwd"), "the first entry must not mention the second file: " + lines[0]);
        assertFalse(lines[1].contains("/etc/hostname"), "nor the second the first: " + lines[1]);
    }

    /**
     * THE PATH IS STATED, NOT ECHOED. The shared refusal text happens to name the path today; a notice that relied on
     * that would lose the mapping between file and reason the day the wording changed. A reason that never mentions the
     * path must therefore still come out paired with it.
     */
    @Test
    void theEntryStatesThePathItselfEvenWhenTheReasonNeverMentionsIt() {
        Refusal wordless = new Refusal("/var/log/build.log", "This file is not available to this session.");

        String notice = PolicyRefusalEvent.compose(List.of(wordless, PASSWD));

        assertTrue(notice.contains("/var/log/build.log"), "the path is in the notice: " + notice);
        assertTrue(notice.contains(PolicyRefusalEvent.ENTRY_START + "/var/log/build.log"
                + PolicyRefusalEvent.ENTRY_MIDDLE + "This file is not available to this session."),
                   "path and reason adjacent, in one entry: " + notice);
    }

    @Test
    void theReasonIsQuotedByteForByteNeverParaphrasedOrTrimmed() {
        Refusal odd = new Refusal("/tmp/a b/\"c\".txt",
                                  "  Access denied: /tmp/a b/\"c\".txt is outside the allowed project scope for this session.\t");

        String notice = PolicyRefusalEvent.compose(List.of(odd));

        assertTrue(notice.contains(PolicyRefusalEvent.ENTRY_MIDDLE + odd.reason() + "\n"), notice);
    }

    @Test
    void theCaveatAppearsOnceAndCoversOnlyOtherCutOffCalls() {
        String notice = PolicyRefusalEvent.compose(List.of(HOSTNAME, PASSWD));

        assertEquals(1, notice.split("UNKNOWN", -1).length - 1, "one caveat, however many refusals: " + notice);
        assertTrue(PolicyRefusalEvent.CUT_OFF_CALLS.startsWith("If any other tool call"),
                   "it is about the OTHER calls; the refused read is known not to have run. Conditional on purpose: a "
                   + "session that made only the one refused call had nothing else cut off, and a notice whose job is "
                   + "correcting a false statement must not make one (observed live on v2, single-call turn)");
        assertTrue(notice.endsWith(PolicyRefusalEvent.CUT_OFF_CALLS));
        assertTrue(PolicyRefusalEvent.CUT_OFF_CALLS.endsWith("then continue."),
                   "the turn is dead and this restarts it, so it must say to carry on");
    }

    @Test
    void theFramingSaysItWasNotTheUserAndAddsNoBlameOrWorkaroundInstruction() {
        String text = PolicyRefusalEvent.compose(List.of(HOSTNAME)).toLowerCase();

        assertTrue(text.contains("not a user rejection"));
        for (String forbidden : List.of("route around", "workaround", "work around", "ask the user", "do not retry",
                                        "your fault", "mistake")) {
            assertFalse(text.contains(forbidden), "no extra narration or instruction: " + forbidden);
        }
    }

    @Test
    void nothingRefusedIsNothingToSay() {
        assertNull(PolicyRefusalEvent.compose(List.of()));
        assertNull(PolicyRefusalEvent.compose(null));
    }

    @Test
    void theEventKeepsItsOwnCopyOfTheRefusals() {
        List<Refusal> mutable = new ArrayList<>(List.of(HOSTNAME));
        PolicyRefusalEvent event = new PolicyRefusalEvent(mutable);

        mutable.add(PASSWD);

        assertEquals(List.of(HOSTNAME), event.refusals(),
                     "a list the caller goes on using must not change what the UI reads later on the EDT");
    }
}
