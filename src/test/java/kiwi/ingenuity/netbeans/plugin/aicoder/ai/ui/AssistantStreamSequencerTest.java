package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AssistantStreamSequencerTest {

    private static final long CAP_MILLIS = 10L;
    private static final long STALL_MILLIS = 500L;

    @Test
    void splitsTextAroundNoticeAtNaturalSentenceBoundary() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("The cat sa");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("t on the mat. Then");

        assertEquals(List.of("begin", "append:The cat sat on the mat.", "finalise",
                             "system:Mail", "begin", "append:Then"), sink.events);
    }

    @Test
    void drawsNoticeBeforeAnyAssistantTextWithoutOpeningBubble() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.showSystemNotice("Mail");

        assertEquals(List.of("system:Mail"), sink.events);
        assertFalse(sequencer.isAssistantTurnActive());
    }

    @Test
    void dropsLeadingLineBreaksButPreservesIndentBeforeFirstText() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("\n\r\n    ");
        sequencer.onTextDelta("code");

        assertEquals(List.of("begin", "append:    code"), sink.events);
    }

    @Test
    void endTurnAfterOnlyBlankDeltasDoesNotCreateBubble() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("\n");
        sequencer.onTextDelta("  \t");
        sequencer.endTurn();

        assertEquals(List.of(), sink.events);
    }

    @Test
    void drawsMultipleNoticesInArrivalOrderInOneSplit() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("First");
        sequencer.showSystemNotice("Second");
        sequencer.onTextDelta("\nB");

        assertEquals(List.of("begin", "append:A\n", "finalise", "system:First",
                             "system:Second", "begin", "append:B"), sink.events);
    }

    @Test
    void endTurnDrainsQueuedNoticeWithoutExtraFinalise() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.endTurn();

        assertEquals(List.of("begin", "append:A", "finalise", "system:Mail"), sink.events);
        assertFalse(sequencer.hasPendingNotices());
    }

    @Test
    void paragraphBreakThenToolNoticeDoesNotCreateBlankBubble() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.markParagraphBreak();
        sequencer.showSystemNotice("Tool");

        assertEquals(List.of("begin", "append:A", "finalise", "system:Tool"), sink.events);
    }

    @Test
    void externalCloseDrawsPendingNoticeImmediately() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.onAssistantMessageClosedExternally();

        assertEquals(List.of("begin", "append:A", "system:Mail"), sink.events);
        assertFalse(sequencer.isAssistantTurnActive());
    }

    @Test
    void splitNowIsNoOpWithoutPendingNotices() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.splitNow();

        assertEquals(List.of("begin", "append:A"), sink.events);
    }

    @Test
    void keepsFencedTextTogetherUntilFenceCloses() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());
        String fence = ticks(3);

        sequencer.onTextDelta("Start\n" + fence + "code");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("\nmore\n" + fence + "\nNext");

        assertEquals(List.of("begin", "append:Start\n" + fence + "code\nmore\n" + fence + "\n",
                             "finalise", "system:Mail", "begin", "append:Next"), sink.events);
    }

    @Test
    void capSplitsAtNextWhitespaceWithoutBreakingWord() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("hello");
        sequencer.showSystemNotice("Mail");
        scheduler.fire();
        sequencer.onTextDelta(" world");

        assertEquals(List.of("begin", "append:hello ", "finalise", "system:Mail",
                             "begin", "append:world"), sink.events);
    }

    @Test
    void capSplitsImmediatelyWhenExistingTailIsWhitespace() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("hello ");
        sequencer.showSystemNotice("Mail");
        scheduler.fire();

        assertEquals(List.of("begin", "append:hello ", "finalise", "system:Mail"), sink.events);
    }

    @Test
    void resetCancelsScheduledSplitAndDropsNotices() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.reset();
        scheduler.fire();

        assertEquals(List.of("begin", "append:A"), sink.events);
        assertFalse(sequencer.hasPendingNotices());
    }

    @Test
    void closedAssistantTurnCanStartAgainAfterNoticeSplit() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("\n\nB");

        assertTrue(sequencer.isAssistantTurnActive());
        assertEquals(List.of("begin", "append:A\n\n", "finalise", "system:Mail",
                             "begin", "append:B"), sink.events);
    }

    @Test
    void noticeAtTrailingNewlineSplitsImmediately() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A\n");
        sequencer.showSystemNotice("Mail");

        assertEquals(List.of("begin", "append:A\n", "finalise", "system:Mail"), sink.events);
    }

    @Test
    void noticeAfterSentenceAndWhitespaceSplitsImmediately() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("Done. ");
        sequencer.showSystemNotice("Mail");

        assertEquals(List.of("begin", "append:Done. ", "finalise", "system:Mail"), sink.events);
    }

    @Test
    void findsSentenceBoundaryStraddlingExistingAndIncomingText() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("Done.");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta(" Next");

        assertEquals(List.of("begin", "append:Done.", "finalise", "system:Mail",
                             "begin", "append:Next"), sink.events);
    }

    @Test
    void findsParagraphBoundaryInIncomingText() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("\n\nNext");

        assertEquals(List.of("begin", "append:A\n\n", "finalise", "system:Mail",
                             "begin", "append:Next"), sink.events);
    }

    @Test
    void paragraphPauseSplitsPendingNoticeImmediately() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.markParagraphBreak();

        assertEquals(List.of("begin", "append:A", "finalise", "system:Mail"), sink.events);
    }

    @Test
    void noticeDuringPausedTextDrawsImmediately() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.markParagraphBreak();
        sequencer.showSystemNotice("Mail");

        assertEquals(List.of("begin", "append:A", "finalise", "system:Mail"), sink.events);
    }

    @Test
    void tildeFenceHoldsNoticeUntilItCloses() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());
        String fence = "~~~";

        sequencer.onTextDelta("Start\n" + fence + "code");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("\nmore\n" + fence + "\nNext");

        assertEquals(List.of("begin", "append:Start\n" + fence + "code\nmore\n" + fence + "\n",
                             "finalise", "system:Mail", "begin", "append:Next"), sink.events);
    }

    @Test
    void inlineBackticksDoNotToggleTheFence() {
        // A marker in the middle of a line never opens a fence.
        RecordingSink middle = new RecordingSink();
        AssistantStreamSequencer middleSequencer = sequencer(middle, new ManualScheduler());
        middleSequencer.onTextDelta("Use " + ticks(3) + "x" + ticks(3) + " here");
        middleSequencer.showSystemNotice("Mail");
        middleSequencer.onTextDelta(". Next");

        assertEquals(List.of("begin", "append:Use " + ticks(3) + "x" + ticks(3) + " here.",
                             "finalise", "system:Mail", "begin", "append:Next"), middle.events);

        // A line that starts with a backtick run and has another backtick after it is not an opener either.
        Run lineStart = afterNotice(ticks(3) + "x" + ticks(3) + " is the syntax", ". Next");
        assertEquals(List.of("begin", "append:" + ticks(3) + "x" + ticks(3) + " is the syntax.",
                             "finalise", "system:Mail", "begin", "append:Next"), lineStart.sink().events);

        // An unbalanced inline marker is not a fence either.
        Run unbalanced = afterNotice("Use " + ticks(3) + " once", ". Next");
        assertEquals(List.of("begin", "append:Use " + ticks(3) + " once.",
                             "finalise", "system:Mail", "begin", "append:Next"), unbalanced.sink().events);
    }

    @Test
    void fourBacktickFenceKeepsAnInnerBacktickExampleInside() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        String first = "Intro\n" + ticks(4) + "\n" + ticks(3) + "\n";
        sequencer.onTextDelta(first);
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("x\n" + ticks(4) + "\nafter");

        assertEquals(List.of("begin", "append:" + first + "x\n" + ticks(4) + "\n",
                             "finalise", "system:Mail", "begin", "append:after"), sink.events);
    }

    @Test
    void fenceMarkerSplitAcrossDeltasIsRecognised() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("Start\n``");
        sequencer.onTextDelta("`code");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("\nmore\n```\nAfter");

        assertEquals(List.of("begin", "append:Start\n```code\nmore\n```\n",
                             "finalise", "system:Mail", "begin", "append:After"), sink.events);
    }

    @Test
    void noticeInsideFenceWaitsForTheCloserEvenAfterTheCap() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("```\ncode line\n");
        sequencer.showSystemNotice("Mail");
        scheduler.fire();
        sequencer.onTextDelta("more\n");
        assertEquals(List.of("begin", "append:```\ncode line\nmore\n"), sink.events);

        sequencer.onTextDelta("```\nAfter");

        assertEquals(List.of("begin", "append:```\ncode line\nmore\n```\n",
                             "finalise", "system:Mail", "begin", "append:After"), sink.events);
    }

    @Test
    void forcedSplitInsideFenceKeepsTheFenceForTheContinuation() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("```\ncode");
        sequencer.showSystemNotice("Mail");
        sequencer.closeAndDrawNow();
        sequencer.onTextDelta("more\n");
        sequencer.showSystemNotice("Two");
        sequencer.onTextDelta("```\nAfter");

        assertEquals(List.of("begin", "append:```\ncode", "finalise", "system:Mail",
                             "begin", "append:more\n```\n", "finalise", "system:Two",
                             "begin", "append:After"), sink.events);
    }

    @Test
    void backtickOpenerWithInfoStringOpensAndTheCloserHoldsTheNotice() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("```java\ncode\n");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("``` extra\nmore\n");

        assertTrue(sequencer.hasPendingNotices(), "a line with text after the backticks does not close the fence");
        assertEquals(List.of("begin", "append:```java\ncode\n``` extra\nmore\n"), sink.events);

        sequencer.onTextDelta("```\nAfter");

        assertEquals(List.of("begin", "append:```java\ncode\n``` extra\nmore\n```\n",
                             "finalise", "system:Mail", "begin", "append:After"), sink.events);
    }

    @Test
    void indentedFenceIsRecognised() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("  ```\ncode\n");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("  ```\nAfter");

        assertEquals(List.of("begin", "append:  ```\ncode\n  ```\n",
                             "finalise", "system:Mail", "begin", "append:After"), sink.events);
    }

    @Test
    void crlfParagraphBecomesOneBlankLine() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A\r\n\r\nB");

        assertEquals(List.of("begin", "append:A\n\nB"), sink.events);
    }

    @Test
    void loneCarriageReturnBecomesOneNewline() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A\rB");

        assertEquals(List.of("begin", "append:A\nB"), sink.events);
    }

    @Test
    void carriageReturnAndNewlineAcrossDeltasGiveOneNewline() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A\r");
        sequencer.onTextDelta("\nB");

        assertEquals(List.of("begin", "append:A\nB"), sink.events);
    }

    @Test
    void crlfParagraphWithPendingNoticeSplitsAtTheParagraph() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta("\r\n\r\nB");

        assertEquals(List.of("begin", "append:A\n\n", "finalise", "system:Mail", "begin", "append:B"),
                     sink.events);
    }

    @Test
    void crlfAcrossDeltasWithPendingNoticeKeepsOneNewlineAtTheSplit() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        // The CR alone is a line end, so the notice draws there; the LF that follows is not a second line.
        sequencer.onTextDelta("\r");
        sequencer.onTextDelta("\n\r\nB");

        assertEquals(List.of("begin", "append:A\n", "finalise", "system:Mail", "begin", "append:B"),
                     sink.events);
    }

    @Test
    void listMarkersAbbreviationsAndDecimalsDoNotSplitButRealSentencesDo() {
        Run list = afterNotice("1", ". Then");
        assertEquals(List.of("begin", "append:1. Then"), list.sink().events);
        assertTrue(list.sequencer().hasPendingNotices());

        Run abbreviation = afterNotice("see e.g", ". x");
        assertEquals(List.of("begin", "append:see e.g. x"), abbreviation.sink().events);
        assertTrue(abbreviation.sequencer().hasPendingNotices());

        Run title = afterNotice("Dr", ". Smith");
        assertEquals(List.of("begin", "append:Dr. Smith"), title.sink().events);
        assertTrue(title.sequencer().hasPendingNotices());

        Run decimal = afterNotice("3.14", " is pi");
        assertEquals(List.of("begin", "append:3.14 is pi"), decimal.sink().events);
        assertTrue(decimal.sequencer().hasPendingNotices());

        Run sentence = afterNotice("Done", ". Next");
        assertEquals(List.of("begin", "append:Done.", "finalise", "system:Mail",
                             "begin", "append:Next"), sentence.sink().events);
        assertFalse(sentence.sequencer().hasPendingNotices());
    }

    @Test
    void lineEndWithinWindowBeatsNearerSentenceEnd() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("Z");
        sequencer.showSystemNotice("Mail");
        String incoming = "x".repeat(10) + ". " + "y".repeat(290) + "\n" + "z";
        sequencer.onTextDelta(incoming);

        assertEquals(List.of("begin", "append:Z" + "x".repeat(10) + ". " + "y".repeat(290) + "\n",
                             "finalise", "system:Mail", "begin", "append:z"), sink.events);
    }

    @Test
    void beyondThePriorityWindowTheEarliestBreakOfAnyKindIsTaken() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("Z");
        sequencer.showSystemNotice("Mail");
        String incoming = "a".repeat(449) + ". " + "b".repeat(20) + "\n" + "c";
        sequencer.onTextDelta(incoming);

        assertEquals(List.of("begin", "append:Z" + "a".repeat(449) + ".",
                             "finalise", "system:Mail", "begin", "append:" + "b".repeat(20) + "\nc"),
                     sink.events);
    }

    @Test
    void secondNoticeDoesNotRearmTheCap() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("First");
        sequencer.showSystemNotice("Second");

        assertEquals(1, scheduler.scheduledCount(CAP_MILLIS));
        assertEquals(0, scheduler.cancelCount(CAP_MILLIS));
    }

    @Test
    void capDoesNotSplitAtABareSentenceMark() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("Hello.");
        sequencer.showSystemNotice("Mail");
        scheduler.fire();

        assertEquals(List.of("begin", "append:Hello."), sink.events);
        assertTrue(sequencer.hasPendingNotices());
    }

    @Test
    void capDoesNotSplitInsideADottedToken() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("ver 3");
        sequencer.showSystemNotice("Mail");
        scheduler.fire();
        sequencer.onTextDelta(".");
        sequencer.onTextDelta("14");

        assertEquals(List.of("begin", "append:ver 3.14"), sink.events);
        assertTrue(sequencer.hasPendingNotices());

        // A real sentence end after the cap still splits.
        sequencer.onTextDelta(". Next");
        assertEquals(List.of("begin", "append:ver 3.14.", "finalise", "system:Mail",
                             "begin", "append:Next"), sink.events);
    }

    @Test
    void timerFiringAfterEndTurnIsNoOp() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.endTurn();
        List<String> afterEnd = new ArrayList<>(sink.events);
        scheduler.fire();
        scheduler.fireStall();

        assertEquals(afterEnd, sink.events);
    }

    @Test
    void externalCloseCancelsTheTimers() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.onAssistantMessageClosedExternally();

        assertEquals(1, scheduler.cancelCount(CAP_MILLIS));
        assertEquals(1, scheduler.cancelCount(STALL_MILLIS));
    }

    @Test
    void characterByCharacterFeedOfSentencesSplitsOnlyAtRealSentenceEnds() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("N");
        for (char c : ". B. C.".toCharArray()) {
            sequencer.onTextDelta(String.valueOf(c));
        }

        assertEquals(List.of("begin", "append:A. B.", "finalise", "system:N",
                             "begin", "append:C."), sink.events);
    }

    @Test
    void continuationAfterSplitNowHasNoLeadingSpace() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.splitNow();
        sequencer.onTextDelta("   B");

        assertEquals(List.of("begin", "append:A", "finalise", "system:Mail",
                             "begin", "append:B"), sink.events);
    }

    @Test
    void firstBubbleKeepsItsIndent() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("    code");

        assertEquals(List.of("begin", "append:    code"), sink.events);
    }

    @Test
    void newTurnAfterASplitKeepsItsFirstIndent() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("N");
        sequencer.splitNow();
        sequencer.endTurn();
        sequencer.onTextDelta("    code");

        assertEquals(List.of("begin", "append:A", "finalise", "system:N", "begin", "append:    code"),
                     sink.events);
    }

    @Test
    void noticeBeforeAnyTextThenIndentedCodeKeepsTheIndent() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.showSystemNotice("N");
        sequencer.onTextDelta("    code");

        assertEquals(List.of("system:N", "begin", "append:    code"), sink.events);
    }

    @Test
    void whitespaceOnlyDeltaAfterSplitOpensNothing() {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.splitNow();
        sequencer.onTextDelta("   ");
        sequencer.onTextDelta("code");

        assertEquals(List.of("begin", "append:A", "finalise", "system:Mail",
                             "begin", "append:code"), sink.events);
    }

    @Test
    void markParagraphBreakWithNoticeGivesNoDoubleParagraph() {
        RecordingSink pausedFirst = new RecordingSink();
        AssistantStreamSequencer first = sequencer(pausedFirst, new ManualScheduler());
        first.onTextDelta("A");
        first.markParagraphBreak();
        first.showSystemNotice("N");
        first.onTextDelta("B");

        RecordingSink noticeFirst = new RecordingSink();
        AssistantStreamSequencer second = sequencer(noticeFirst, new ManualScheduler());
        second.onTextDelta("A");
        second.showSystemNotice("N");
        second.markParagraphBreak();
        second.onTextDelta("B");

        List<String> expected = List.of("begin", "append:A", "finalise", "system:N", "begin", "append:B");
        assertEquals(expected, pausedFirst.events);
        assertEquals(expected, noticeFirst.events);
    }

    @Test
    void closeAndDrawNowClosesDrawsAndCancelsTheCap() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("Mail");
        sequencer.closeAndDrawNow();

        assertEquals(List.of("begin", "append:A", "finalise", "system:Mail"), sink.events);
        assertEquals(1, scheduler.cancelCount(CAP_MILLIS));
    }

    @Test
    @Timeout(5)
    void largeDeltaWithExpiredCapFinishesQuickly() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("A");
        sequencer.showSystemNotice("N");
        scheduler.fire();
        long started = System.nanoTime();
        sequencer.onTextDelta("word ".repeat(40_000));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 1000, "a 200 KB delta must not take " + elapsedMillis + " ms");
        assertTrue(sink.events.contains("system:N"));
    }

    @Test
    void capOnAMidWordTailWaitsForTheStallTimer() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("hello wor");
        sequencer.showSystemNotice("Mail");
        scheduler.fire();

        assertEquals(List.of("begin", "append:hello wor"), sink.events);
        assertTrue(sequencer.hasPendingNotices());

        scheduler.fireStall();

        assertEquals(List.of("begin", "append:hello wor", "finalise", "system:Mail"), sink.events);
    }

    @Test
    void tailTruncationKeepsALineStartInTheTail() {
        Run longLine = afterNotice("x".repeat(60) + "\n1", ". item");
        assertEquals(List.of("begin", "append:" + "x".repeat(60) + "\n1. item"), longLine.sink().events);
        assertTrue(longLine.sequencer().hasPendingNotices());
    }

    @Test
    void tailTruncationStillSplitsAfterAPlainSentence() {
        Run longLine = afterNotice("x".repeat(60) + " 1", ". item");
        assertEquals(List.of("begin", "append:" + "x".repeat(60) + " 1.", "finalise", "system:Mail",
                             "begin", "append:item"), longLine.sink().events);
    }

    @Test
    void stallTimerSplitsMidWordWhenTheStreamGoesQuiet() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("hello wor");
        sequencer.showSystemNotice("Mail");
        scheduler.fireStall();
        sequencer.onTextDelta("ant more");

        assertEquals(List.of("begin", "append:hello wor", "finalise", "system:Mail",
                             "begin", "append:ant more"), sink.events);
    }

    @Test
    void eachDeltaRearmsTheStallTimerAndASupersededTimerDoesNothing() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("hello");
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta(" w");
        scheduler.fireCancelled(STALL_MILLIS);

        assertEquals(List.of("begin", "append:hello w"), sink.events);
        assertTrue(sequencer.hasPendingNotices());

        scheduler.fireStall();

        assertEquals(List.of("begin", "append:hello w", "finalise", "system:Mail"), sink.events);
    }

    @Test
    void stallAfterAMidLineSplitStartsTheContinuationOnANewLine() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("foo");
        sequencer.showSystemNotice("Mail");
        scheduler.fireStall();
        sequencer.onTextDelta("\n```\ncode\n");
        sequencer.showSystemNotice("N2");

        assertTrue(sequencer.hasPendingNotices(), "the notice waits while the continuation is inside a fence");

        sequencer.onTextDelta("```\nAfter");

        assertEquals(List.of("begin", "append:foo", "finalise", "system:Mail",
                             "begin", "append:```\ncode\n```\n", "finalise", "system:N2",
                             "begin", "append:After"), sink.events);
    }

    @Test
    void afterAMidLineSplitAListMarkerIsNotASentenceEnd() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("foo");
        sequencer.showSystemNotice("Mail");
        scheduler.fireStall();
        sequencer.onTextDelta("1");
        sequencer.showSystemNotice("N2");
        sequencer.onTextDelta(". item");

        assertEquals(List.of("begin", "append:foo", "finalise", "system:Mail",
                             "begin", "append:1. item"), sink.events);
        assertTrue(sequencer.hasPendingNotices());
    }

    @Test
    void stallInsideAnOpenFenceDoesNotSplit() {
        RecordingSink sink = new RecordingSink();
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(sink, scheduler);

        sequencer.onTextDelta("```\ncode line");
        sequencer.showSystemNotice("Mail");
        scheduler.fireStall();

        assertEquals(List.of("begin", "append:```\ncode line"), sink.events);
        assertTrue(sequencer.hasPendingNotices());
    }

    @Test
    void stallTimerIsCancelledByEndTurn() {
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(new RecordingSink(), scheduler);

        sequencer.onTextDelta("hello");
        sequencer.showSystemNotice("Mail");
        sequencer.endTurn();

        assertEquals(1, scheduler.cancelCount(STALL_MILLIS));
    }

    @Test
    void stallTimerIsCancelledByReset() {
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(new RecordingSink(), scheduler);

        sequencer.onTextDelta("hello");
        sequencer.showSystemNotice("Mail");
        sequencer.reset();

        assertEquals(1, scheduler.cancelCount(STALL_MILLIS));
    }

    @Test
    void stallTimerIsCancelledBySplitNow() {
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(new RecordingSink(), scheduler);

        sequencer.onTextDelta("hello");
        sequencer.showSystemNotice("Mail");
        sequencer.splitNow();

        assertEquals(1, scheduler.cancelCount(STALL_MILLIS));
    }

    @Test
    void stallTimerIsNotArmedWithoutAPendingNotice() {
        ManualScheduler scheduler = new ManualScheduler();
        AssistantStreamSequencer sequencer = sequencer(new RecordingSink(), scheduler);

        sequencer.onTextDelta("hello");
        sequencer.onTextDelta(" world");

        assertEquals(0, scheduler.scheduledCount(STALL_MILLIS));
    }

    private static String ticks(int count) {
        return "`".repeat(count);
    }

    /**
     * The sink and sequencer after one delta, a notice, and a second delta.
     */
    private record Run(RecordingSink sink, AssistantStreamSequencer sequencer) {

    }

    private Run afterNotice(String first, String second) {
        RecordingSink sink = new RecordingSink();
        AssistantStreamSequencer sequencer = sequencer(sink, new ManualScheduler());
        sequencer.onTextDelta(first);
        sequencer.showSystemNotice("Mail");
        sequencer.onTextDelta(second);
        return new Run(sink, sequencer);
    }

    private AssistantStreamSequencer sequencer(RecordingSink sink, ManualScheduler scheduler) {
        return new AssistantStreamSequencer(sink, scheduler, CAP_MILLIS, STALL_MILLIS);
    }

    private static final class RecordingSink implements AssistantStreamSink {

        private final List<String> events = new ArrayList<>();

        @Override
        public void beginAssistantMessage() {
            events.add("begin");
        }

        @Override
        public void appendDelta(String text) {
            int lastIndex = events.size() - 1;
            if (lastIndex >= 0 && events.get(lastIndex).startsWith("append:")) {
                events.set(lastIndex, events.get(lastIndex) + text);
            }
            else {
                events.add("append:" + text);
            }
        }

        @Override
        public void finaliseAssistantMessage() {
            events.add("finalise");
        }

        @Override
        public void addSystemMessage(String text) {
            events.add("system:" + text);
        }
    }

    /**
     * Holds scheduled tasks until a test fires them. Each task remembers its delay, so the cap timer and the stall
     * timer can be fired separately.
     */
    private static final class ManualScheduler implements SplitScheduler {

        private final List<Task> tasks = new ArrayList<>();
        private final List<Long> scheduledDelays = new ArrayList<>();
        private final List<Long> cancelledDelays = new ArrayList<>();

        @Override
        public Runnable schedule(Runnable task, long delayMillis) {
            scheduledDelays.add(delayMillis);
            Task entry = new Task(task, delayMillis);
            tasks.add(entry);
            return () -> {
                if (!entry.cancelled) {
                    entry.cancelled = true;
                    cancelledDelays.add(delayMillis);
                }
            };
        }

        /**
         * Fires the live cap timers.
         */
        void fire() {
            fireDue(CAP_MILLIS, false);
        }

        /**
         * Fires the live stall timers.
         */
        void fireStall() {
            fireDue(STALL_MILLIS, false);
        }

        /**
         * Runs only the cancelled timers with this delay, as a scheduler that ignores cancellation would.
         */
        void fireCancelled(long delayMillis) {
            fireDue(delayMillis, true);
        }

        private void fireDue(long delayMillis, boolean cancelledOnly) {
            List<Task> due = new ArrayList<>();
            for (Task task : tasks) {
                if (task.delay == delayMillis && task.cancelled == cancelledOnly) {
                    due.add(task);
                }
            }
            tasks.removeAll(due);
            for (Task task : due) {
                task.run.run();
            }
        }

        int scheduledCount(long delayMillis) {
            return (int) scheduledDelays.stream().filter(delay -> delay == delayMillis).count();
        }

        int cancelCount(long delayMillis) {
            return (int) cancelledDelays.stream().filter(delay -> delay == delayMillis).count();
        }

        private static final class Task {

            private final Runnable run;
            private final long delay;
            private boolean cancelled;

            Task(Runnable run, long delay) {
                this.run = run;
                this.delay = delay;
            }
        }
    }
}
