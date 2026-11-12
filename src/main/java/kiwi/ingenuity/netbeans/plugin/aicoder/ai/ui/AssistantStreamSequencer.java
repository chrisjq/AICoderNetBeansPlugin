package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps system notices out of an in-progress assistant bubble while preserving readable boundaries in streamed text.
 *
 * <p>
 * A notice that arrives while a reply streams is queued until the reply reaches a break. Breaks are tried in this
 * order: a paragraph break (a blank line), a line end, then a sentence end. A sentence end is {@code .}, {@code !} or
 * {@code ?} followed by whitespace, except a list marker such as {@code 1.} or {@code a.} at the start of a line, and a
 * few common abbreviations. A break is never taken inside a code fence, so a notice waits for the closing fence. After
 * {@link #DEFAULT_SPLIT_CAP_MILLIS} the queue stops waiting for a sentence or line end: the next whitespace or line end
 * is taken instead. A tail inside a word, or a bare sentence mark with no following whitespace, waits for the stall
 * timer ({@link #STALL_SPLIT_MILLIS}).
 *
 * <p>
 * Break kinds keep their priority only within the first {@value #PRIORITY_WINDOW} characters of an incoming delta.
 * Beyond that window the earliest break of any kind is taken, so a very large delta is not held back for a late
 * paragraph break while an earlier sentence end waits.
 *
 * <p>
 * Fence state is tracked line by line as text arrives, so a fence marker split across deltas is still recognised, and
 * the fence state carries across a split within the same turn. Work per delta is proportional to the delta's length.
 * This class is confined to the EDT.
 */
final class AssistantStreamSequencer {

    static final long DEFAULT_SPLIT_CAP_MILLIS = 2500L;

    /**
     * Within this many characters of an incoming delta, paragraph breaks beat line ends, which beat sentence ends.
     */
    static final int PRIORITY_WINDOW = 400;

    /**
     * How long a stream may stay silent, with a notice pending and no code fence open, before the notice is drawn at
     * the current tail even if that splits a word.
     */
    static final long STALL_SPLIT_MILLIS = 10_000L;

    private static final int TAIL_LENGTH = 32;
    private static final int MAX_ABBREVIATION_LENGTH = 8;
    private static final Set<String> ABBREVIATIONS = Set.of(
            "e.g", "i.e", "etc", "vs", "dr", "mr", "mrs", "ms", "fig", "no", "st");
    private static final Runnable NO_OP = () -> {
    };

    private final AssistantStreamSink sink;
    private final SplitScheduler scheduler;
    private final long capMillis;
    private final List<String> pendingNotices = new ArrayList<>();
    /**
     * The last {@value #TAIL_LENGTH} characters of the turn's text, enough to judge a break at the end of the text.
     */
    private final StringBuilder tail = new StringBuilder();
    private final StringBuilder leadingIndent = new StringBuilder();
    private final FenceState fence = new FenceState();

    private final long stallMillis;
    private Runnable cancelCap = NO_OP;
    private int capGeneration;
    private Runnable cancelStall = NO_OP;
    private int stallGeneration;
    private boolean assistantTurnActive;
    private boolean bubbleVisible;
    private boolean bubblesThisTurn;
    private boolean continuation;
    private boolean paragraphBreakPending;
    private boolean capExpired;
    private boolean tailTruncated;
    private boolean endedWithCarriageReturn;

    AssistantStreamSequencer(AssistantStreamSink sink, SplitScheduler scheduler, long capMillis) {
        this(sink, scheduler, capMillis, STALL_SPLIT_MILLIS);
    }

    AssistantStreamSequencer(AssistantStreamSink sink, SplitScheduler scheduler, long capMillis, long stallMillis) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.capMillis = Math.max(0L, capMillis);
        this.stallMillis = Math.max(0L, stallMillis);
    }

    void onTextDelta(String raw) {
        String delta = normaliseLineEnds(raw);
        if (delta.isEmpty()) {
            return;
        }
        if (!assistantTurnActive) {
            openBubble(delta);
            return;
        }

        String text = paragraphBreakPending ? "\n\n" + delta : delta;
        paragraphBreakPending = false;
        if (hasPendingNotices()) {
            int split = findSplitPoint(text);
            if (split >= 0) {
                appendToOpenBubble(text.substring(0, split));
                splitNow();
                openBubble(text.substring(split));
                return;
            }
        }
        appendToOpenBubble(text);
    }

    void markParagraphBreak() {
        if (!assistantTurnActive) {
            return;
        }
        paragraphBreakPending = true;
        if (hasPendingNotices()) {
            splitNow();
        }
    }

    void showSystemNotice(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!assistantTurnActive || !bubbleVisible || paragraphBreakPending || endsAtNaturalBreak()) {
            if (assistantTurnActive) {
                closeAssistantMessage();
            }
            pendingNotices.add(text);
            drawPendingNotices();
            return;
        }

        // Only the first queued notice starts the clock, so later notices cannot push the split back.
        if (pendingNotices.isEmpty()) {
            armCap();
            pendingNotices.add(text);
            armStallTimer();
            return;
        }
        pendingNotices.add(text);
    }

    void splitNow() {
        if (!hasPendingNotices()) {
            return;
        }
        if (assistantTurnActive) {
            closeAssistantMessage();
        }
        drawPendingNotices();
    }

    /**
     * Closes the open bubble and draws the queued notices now, for a mid-turn row such as a tool or permission request.
     * The fence state is kept, so the continuation is still inside a fence if the reply was.
     */
    void closeAndDrawNow() {
        if (assistantTurnActive) {
            closeAssistantMessage();
        }
        cancelCapTimer();
        drawPendingNotices();
        continuation = bubblesThisTurn;
    }

    void endTurn() {
        if (assistantTurnActive) {
            closeAssistantMessage();
        }
        drawPendingNotices();
        clearStreamState();
    }

    void reset() {
        clearStreamState();
        pendingNotices.clear();
    }

    void onAssistantMessageClosedExternally() {
        assistantTurnActive = false;
        paragraphBreakPending = false;
        bubbleVisible = false;
        leadingIndent.setLength(0);
        cancelCapTimer();
        cancelStallTimer();
        continuation = bubblesThisTurn;
        drawPendingNotices();
    }

    boolean isAssistantTurnActive() {
        return assistantTurnActive;
    }

    boolean hasPendingNotices() {
        return !pendingNotices.isEmpty();
    }

    /**
     * Opens a bubble for the delta if it has visible text. Before the first bubble of a turn, blank lines are dropped
     * and only the indent of the final partial line is kept; the first content line keeps its indent. After a split,
     * every leading space, tab and newline is dropped, so a post-split code block loses its first-line indent; fenced
     * code is the norm, so that is accepted. A continuation that starts mid-line is treated as a new line: the state
     * and tail get a newline, so a fence marker or list marker at its start is seen as one.
     */
    private void openBubble(String delta) {
        leadingIndent.append(delta);
        String combined = leadingIndent.toString();
        leadingIndent.setLength(0);
        int first = firstContentIndex(combined);
        String text;
        if (continuation) {
            if (first < 0) {
                return;
            }
            text = combined.substring(first);
            if (!fence.atLineStart()) {
                fence.feed('\n');
                appendTail("\n");
            }
        }
        else {
            if (first < 0) {
                leadingIndent.append(combined.substring(combined.lastIndexOf('\n') + 1));
                return;
            }
            text = combined.substring(combined.lastIndexOf('\n', first) + 1);
        }
        assistantTurnActive = true;
        bubblesThisTurn = true;
        bubbleVisible = false;
        sink.beginAssistantMessage();
        appendToOpenBubble(text);
    }

    private void appendToOpenBubble(String text) {
        if (text.isEmpty()) {
            return;
        }
        for (int index = 0; index < text.length(); index++) {
            fence.feed(text.charAt(index));
        }
        if (!bubbleVisible && !text.isBlank()) {
            bubbleVisible = true;
        }
        appendTail(text);
        sink.appendDelta(text);
        if (capExpired && hasPendingNotices() && endsAtCapTail()) {
            splitNow();
        }
        if (assistantTurnActive && hasPendingNotices()) {
            armStallTimer();
        }
    }

    private void appendTail(String text) {
        if (tail.length() + text.length() > TAIL_LENGTH) {
            tailTruncated = true;
        }
        tail.append(text);
        if (tail.length() > TAIL_LENGTH) {
            tail.delete(0, tail.length() - TAIL_LENGTH);
        }
    }

    /**
     * Finds where to split the reply, given the incoming delta, which is scanned once. Returns the index in the
     * incoming text where the bubble should end, or -1 when there is no split point.
     */
    private int findSplitPoint(String incoming) {
        Scanned view = new Scanned(incoming);
        int offset = view.offset;
        int length = view.length();
        FenceState state = fence.copy();
        int paragraph = -1;
        int newline = -1;
        int sentence = -1;
        int anyBreak = -1;
        int space = -1;
        for (int index = Math.max(0, offset - 1); index < length; index++) {
            if (index >= offset) {
                state.feed(incoming.charAt(index - offset));
            }
            int end = index + 1;
            boolean inWindow = end - offset <= PRIORITY_WINDOW;
            if (!inWindow && anyBreak >= 0) {
                break;
            }
            if (state.inFence()) {
                continue;
            }
            char c = view.at(index);
            if (c == '\n') {
                if (anyBreak < 0) {
                    anyBreak = end;
                }
                if (inWindow) {
                    if (newline < 0) {
                        newline = end;
                    }
                    if (paragraph < 0 && index >= 1 && view.at(index - 1) == '\n') {
                        paragraph = end;
                    }
                }
            }
            else if (Character.isWhitespace(c)) {
                if (space < 0) {
                    space = end;
                }
            }
            else if (isSentenceMark(c) && index + 1 < length && Character.isWhitespace(view.at(index + 1))
                    && !(c == '.' && isNonSentenceDot(view, index))) {
                if (anyBreak < 0) {
                    anyBreak = end;
                }
                if (inWindow && sentence < 0) {
                    sentence = end;
                }
            }
        }
        if (paragraph >= 0) {
            return paragraph - offset;
        }
        if (newline >= 0) {
            return newline - offset;
        }
        if (sentence >= 0) {
            return sentence - offset;
        }
        if (anyBreak >= 0) {
            return anyBreak - offset;
        }
        if (capExpired && space >= 0) {
            return space - offset;
        }
        return -1;
    }

    /**
     * Whether the bubble's tail is a break that can close it immediately: a line end, or a sentence end followed by
     * whitespace, outside a fence.
     */
    private boolean endsAtNaturalBreak() {
        if (fence.inFence() || tail.length() == 0) {
            return false;
        }
        int last = tail.length() - 1;
        char lastChar = tail.charAt(last);
        if (lastChar == '\n') {
            return true;
        }
        if (!Character.isWhitespace(lastChar) || last == 0) {
            return false;
        }
        char before = tail.charAt(last - 1);
        return isSentenceMark(before) && !(before == '.' && isNonSentenceDot(new Scanned(""), last - 1));
    }

    /**
     * Whether the bubble's tail is a break that an expired cap may take: whitespace or a line end, outside a fence. A
     * bare sentence mark is not enough, because {@code 3.14} or {@code Main.java} would split mid-word; a sentence end
     * with a following space is taken by the sentence rule, and a mid-word tail waits for the stall timer.
     */
    private boolean endsAtCapTail() {
        if (fence.inFence() || tail.length() == 0) {
            return false;
        }
        return Character.isWhitespace(tail.charAt(tail.length() - 1));
    }

    /**
     * Whether the dot at {@code dot} is not a sentence end: a list marker such as {@code 1.} or {@code a.} that starts
     * a line, or a common abbreviation.
     */
    private boolean isNonSentenceDot(Scanned view, int dot) {
        int digitStart = dot;
        while (digitStart > 0 && isAsciiDigit(view.at(digitStart - 1))) {
            digitStart--;
        }
        if (digitStart < dot && lineStartBefore(view, digitStart)) {
            return true;
        }

        int letterStart = dot;
        while (letterStart > 0 && Character.isLetter(view.at(letterStart - 1))) {
            letterStart--;
        }
        if (dot - letterStart == 1 && lineStartBefore(view, letterStart)) {
            return true;
        }

        int abbreviationStart = dot;
        while (abbreviationStart > 0 && dot - abbreviationStart < MAX_ABBREVIATION_LENGTH
                && isAbbreviationCharacter(view.at(abbreviationStart - 1))) {
            abbreviationStart--;
        }
        StringBuilder token = new StringBuilder();
        for (int index = abbreviationStart; index < dot; index++) {
            token.append(Character.toLowerCase(view.at(index)));
        }
        return ABBREVIATIONS.contains(token.toString());
    }

    /**
     * Whether only spaces or tabs come between the start of a line and {@code position}. At the very start of the turn
     * that is true, unless the tail has already dropped its first characters.
     */
    private boolean lineStartBefore(Scanned view, int position) {
        int index = position - 1;
        while (index >= 0 && (view.at(index) == ' ' || view.at(index) == '\t')) {
            index--;
        }
        if (index < 0) {
            return !tailTruncated;
        }
        return view.at(index) == '\n';
    }

    private void armCap() {
        cancelCapTimer();
        capExpired = false;
        int generation = capGeneration;
        cancelCap = scheduler.schedule(() -> onCapElapsed(generation), capMillis);
    }

    private void onCapElapsed(int generation) {
        if (generation != capGeneration) {
            return;
        }
        cancelCap = NO_OP;
        capExpired = true;
        if (assistantTurnActive && hasPendingNotices() && endsAtCapTail()) {
            splitNow();
        }
    }

    private void cancelCapTimer() {
        capGeneration++;
        Runnable cancel = cancelCap;
        cancelCap = NO_OP;
        cancel.run();
    }

    /**
     * (Re)arms the stall timer. Every appended delta calls this, so only a stream that has gone quiet for the whole
     * stall period draws the notice early. A superseded timer is cancelled and, if it runs anyway, does nothing.
     */
    private void armStallTimer() {
        cancelStallTimer();
        if (!assistantTurnActive || !hasPendingNotices()) {
            return;
        }
        int generation = stallGeneration;
        cancelStall = scheduler.schedule(() -> onStallElapsed(generation), stallMillis);
    }

    private void onStallElapsed(int generation) {
        if (generation != stallGeneration) {
            return;
        }
        cancelStall = NO_OP;
        // Inside a fence the notice waits for the closer, a tool or question split, or the turn end.
        if (assistantTurnActive && hasPendingNotices() && !fence.inFence()) {
            splitNow();
        }
    }

    private void cancelStallTimer() {
        stallGeneration++;
        Runnable cancel = cancelStall;
        cancelStall = NO_OP;
        cancel.run();
    }

    private void closeAssistantMessage() {
        sink.finaliseAssistantMessage();
        assistantTurnActive = false;
        bubbleVisible = false;
        paragraphBreakPending = false;
        leadingIndent.setLength(0);
        cancelCapTimer();
        cancelStallTimer();
        continuation = true;
    }

    private void drawPendingNotices() {
        for (String notice : pendingNotices) {
            sink.addSystemMessage(notice);
        }
        pendingNotices.clear();
        capExpired = false;
        cancelStallTimer();
    }

    private void clearStreamState() {
        assistantTurnActive = false;
        bubbleVisible = false;
        bubblesThisTurn = false;
        continuation = false;
        paragraphBreakPending = false;
        capExpired = false;
        tail.setLength(0);
        tailTruncated = false;
        leadingIndent.setLength(0);
        endedWithCarriageReturn = false;
        fence.reset();
        cancelCapTimer();
        cancelStallTimer();
    }

    /**
     * Turns CRLF and lone CR into LF. A CR at the end of one delta with an LF at the start of the next is one newline.
     */
    private String normaliseLineEnds(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw;
        if (endedWithCarriageReturn && text.startsWith("\n")) {
            text = text.substring(1);
        }
        endedWithCarriageReturn = text.endsWith("\r");
        return text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static int firstContentIndex(String text) {
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c != ' ' && c != '\t' && c != '\n') {
                return index;
            }
        }
        return -1;
    }

    private static boolean isSentenceMark(char c) {
        return c == '.' || c == '!' || c == '?';
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAbbreviationCharacter(char c) {
        return Character.isLetter(c) || c == '.';
    }

    /**
     * The tail of the turn's text followed by an incoming delta, read by index without concatenating them.
     */
    private final class Scanned {

        private final String incoming;
        private final int offset;

        Scanned(String incoming) {
            this.incoming = incoming;
            this.offset = tail.length();
        }

        char at(int index) {
            return index < offset ? tail.charAt(index) : incoming.charAt(index - offset);
        }

        int length() {
            return offset + incoming.length();
        }
    }

    /**
     * Fence state, updated one character at a time. A fence opens on a line whose first run of non-blank characters is
     * at least three backticks or tildes, indented by any amount. A backtick opener whose line has another backtick
     * after the run is not a fence (a line such as a backtick-quoted word followed by more backticks), though a tilde
     * opener may carry anything. A fence closes on a line that repeats the same character at least as many times, with
     * nothing else on the line but spaces or tabs. Markers in the middle of a line never open or close a fence.
     */
    private static final class FenceState {

        private char fenceMarker;
        private int fenceLength;
        private boolean lineStart = true;
        private char lineMarker;
        private int lineMarkerLength;
        private boolean markerRunOpen;
        private boolean lineHasTextAfterMarker;
        private boolean lineBacktickAfterRun;

        FenceState copy() {
            FenceState copy = new FenceState();
            copy.fenceMarker = fenceMarker;
            copy.fenceLength = fenceLength;
            copy.lineStart = lineStart;
            copy.lineMarker = lineMarker;
            copy.lineMarkerLength = lineMarkerLength;
            copy.markerRunOpen = markerRunOpen;
            copy.lineHasTextAfterMarker = lineHasTextAfterMarker;
            copy.lineBacktickAfterRun = lineBacktickAfterRun;
            return copy;
        }

        void reset() {
            fenceMarker = 0;
            fenceLength = 0;
            startLine();
        }

        boolean inFence() {
            return fenceMarker != 0;
        }

        boolean atLineStart() {
            return lineStart;
        }

        void feed(char c) {
            if (c == '\n') {
                endLine();
                return;
            }
            boolean blank = c == ' ' || c == '\t';
            if (lineStart) {
                if (blank) {
                    return;
                }
                lineStart = false;
                if (c == '`' || c == '~') {
                    lineMarker = c;
                    lineMarkerLength = 1;
                    markerRunOpen = true;
                }
                else {
                    lineHasTextAfterMarker = true;
                }
                return;
            }
            if (markerRunOpen) {
                if (c == lineMarker) {
                    lineMarkerLength++;
                    return;
                }
                markerRunOpen = false;
            }
            if (c == '`' && lineMarker == '`') {
                lineBacktickAfterRun = true;
            }
            if (!blank) {
                lineHasTextAfterMarker = true;
            }
        }

        private void endLine() {
            boolean fenceLine = lineMarker != 0 && lineMarkerLength >= 3 && !lineBacktickAfterRun;
            if (fenceMarker == 0) {
                if (fenceLine) {
                    fenceMarker = lineMarker;
                    fenceLength = lineMarkerLength;
                }
            }
            else if (fenceLine && lineMarker == fenceMarker && lineMarkerLength >= fenceLength
                    && !lineHasTextAfterMarker) {
                fenceMarker = 0;
                fenceLength = 0;
            }
            startLine();
        }

        private void startLine() {
            lineStart = true;
            lineMarker = 0;
            lineMarkerLength = 0;
            markerRunOpen = false;
            lineHasTextAfterMarker = false;
            lineBacktickAfterRun = false;
        }
    }
}
