package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Source-level wiring tests for the multi-file review path in {@code AiTopComponent}.
 *
 * <p>
 * {@code AiTopComponent} cannot be instantiated in a unit test — it eagerly builds a real backend through
 * {@code AiTypeRegistry} — so, like {@link AiTopComponentAwaitingUserWiringTest}, these read the source. That buys less
 * than a behavioural test, and the Swing rendering itself is not covered at all; what these do pin is the set of wiring
 * decisions where getting it wrong blocks the AI process or silently records the wrong outcome, and which are otherwise
 * invisible until someone runs a live multi-file edit.</p>
 */
class AiTopComponentMultiPermissionWiringTest {

    private static final String SOURCE_PATH
            = "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/ui/AiTopComponent.java";

    private String readSource() throws IOException {
        return Files.readString(Path.of(SOURCE_PATH));
    }

    /**
     * The driver's source, bounded by its OWN braces.
     *
     * <p>This used to end the extent at {@code indexOf("private void checkForFileChanges()")} — the method that
     * happened to follow the class. On 2026-08-29 a member reordering moved the inner class below that method, the
     * marker was no longer ahead of it, and all eight tests in this class died on the bound with an opaque message
     * before asserting anything about behaviour. Reordering members is legitimate; a test that reads source must
     * tolerate it. Bounding by the class's own closing brace is deterministic under any reordering, insertion or
     * rename around it.</p>
     *
     * <p>Do not replace this with another marker declaration. That is the same defect wearing a different name.</p>
     */
    private String driverSource() throws IOException {
        String source = readSource();
        int start = source.indexOf(DRIVER_DECLARATION);
        assertTrue(start >= 0,
                "AiTopComponent must declare '" + DRIVER_DECLARATION + "'. If it was renamed, update this constant.");
        int end = endOfClassBody(source, start);
        assertTrue(end > start,
                "could not find the closing brace of MultiReviewDriver starting at offset " + start
                + " — its braces do not balance, or the declaration is not followed by a class body.");
        return source.substring(start, end);
    }

    private static final String DRIVER_DECLARATION = "private final class MultiReviewDriver";

    /**
     * Index just past the closing brace of the class body that starts after {@code declStart}, or -1 if it never
     * balances.
     *
     * <p>Braces inside string literals, char literals, text blocks and comments are skipped — AiTopComponent contains
     * quoted log messages and javadoc, and counting a brace inside either would end the extent in the wrong place and
     * fail in a way that looks like a real defect.</p>
     */
    private static int endOfClassBody(String source, int declStart) {
        int i = source.indexOf('{', declStart);
        if (i < 0) {
            return -1;
        }
        int depth = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    i = endOfLineComment(source, i);
                    continue;
                }
                if (next == '*') {
                    i = endOfBlockComment(source, i);
                    continue;
                }
            }
            if (c == '"') {
                i = source.startsWith("\"\"\"", i) ? endOfTextBlock(source, i) : endOfQuoted(source, i, '"');
                continue;
            }
            if (c == '\'') {
                i = endOfQuoted(source, i, '\'');
                continue;
            }
            if (c == '{') {
                depth++;
            }
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return -1;
    }

    private static int endOfLineComment(String source, int start) {
        int newline = source.indexOf('\n', start);
        return newline < 0 ? source.length() : newline + 1;
    }

    private static int endOfBlockComment(String source, int start) {
        int close = source.indexOf("*/", start + 2);
        return close < 0 ? source.length() : close + 2;
    }

    private static int endOfTextBlock(String source, int start) {
        int close = source.indexOf("\"\"\"", start + 3);
        return close < 0 ? source.length() : close + 3;
    }

    /**
     * Index just past the closing quote, honouring backslash escapes. An unterminated literal stops at the newline
     * rather than consuming the rest of the file, so a malformed source fails as a bound error rather than silently
     * swallowing the class.
     */
    private static int endOfQuoted(String source, int start, char quote) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote || c == '\n') {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /**
     * The event must reach a handler at all. Without this branch a MultiPermissionEvent falls through every
     * {@code instanceof} in handleEvent and its response is never completed — the AI process then waits forever.
     */
    @Test
    void multiPermissionEventIsDispatched() throws IOException {
        String source = readSource();
        assertTrue(source.contains("event instanceof MultiPermissionEvent mpe"),
                "handleEvent must have a MultiPermissionEvent branch");
        assertTrue(source.contains("showMultiPermissionReview(mpe)"),
                "the MultiPermissionEvent branch must call showMultiPermissionReview");
    }

    /**
     * A batch waiting on the user is visible chat output, exactly as a single permission request is — otherwise the tab
     * does not flash and a session blocked on review looks idle.
     */
    @Test
    void multiPermissionEventCountsAsVisibleChatOutput() throws IOException {
        String source = readSource();
        int idx = source.indexOf("private boolean isVisibleChatOutput(AiProcessEvent event)");
        assertTrue(idx >= 0, "isVisibleChatOutput must exist");
        String body = source.substring(idx, Math.min(idx + 1200, source.length()));
        assertTrue(body.contains("event instanceof MultiPermissionEvent"),
                "isVisibleChatOutput must treat a MultiPermissionEvent as awaiting-user output. Body:\n" + body);
    }

    /**
     * The renderer must be the same one the single-file notification uses, or one file reads differently depending on
     * which panel showed it. {@code MultiPermissionReview} takes it injected precisely so this is the only place the
     * IDE-dependent implementation is named.
     */
    @Test
    void theReviewIsBuiltWithTheSharedPathRenderer() throws IOException {
        String source = readSource();
        assertTrue(source.contains("new MultiPermissionReview(mpe, ProjectPathUtil::shortPath)"),
                "the review must be constructed with ProjectPathUtil::shortPath as its path renderer");
    }

    /**
     * Auto-accept must still construct the review and still write its per-file log. The two ways to get this wrong are
     * prompting anyway, and skipping the review entirely and losing the per-file record — which is the exact defect
     * this feature replaces.
     */
    @Test
    void autoAcceptStillLogsEveryFileThroughTheReview() throws IOException {
        String source = readSource();
        int idx = source.indexOf("private void showMultiPermissionReview(MultiPermissionEvent mpe)");
        assertTrue(idx >= 0, "showMultiPermissionReview must exist");
        String body = source.substring(idx, Math.min(idx + 1400, source.length()));
        int autoAcceptIdx = body.indexOf("infoBar.isAutoAccept()");
        assertTrue(autoAcceptIdx >= 0, "the auto-accept branch must be present. Body:\n" + body);
        String branch = body.substring(autoAcceptIdx, Math.min(autoAcceptIdx + 500, body.length()));
        assertTrue(branch.contains("review.autoAcceptAll()"),
                "auto-accept must resolve through the review, not by completing the response directly. Branch:\n"
                + branch);
        assertTrue(branch.contains("review.log()"),
                "auto-accept must still write the per-file log. Branch:\n" + branch);
    }

    /**
     * The critical cancellation wiring. The single-file canceller completes the response directly as denied; if a batch
     * used that shape it would bypass the review — the review would still believe it had resolved the set, the log
     * would be wrong, and the backends would read a deliberate "no" where an interruption actually happened. Only
     * {@code review.cancelled(...)} produces the exceptional completion they read as an interruption.
     */
    @Test
    void theBatchCancellerRoutesThroughReviewCancelled() throws IOException {
        String driver = driverSource();
        assertTrue(driver.contains("pendingResponseCancellers.add(canceller)"),
                "the driver must register a canceller so a stop or turn end tears the batch down");
        assertTrue(driver.contains("review.cancelled("),
                "the batch canceller must route through review.cancelled(...)");
        assertFalse(driver.contains("mpe.response().complete"),
                "the driver must never complete the batch response directly — that bypasses the review");
    }

    /**
     * Teardown must close anything the batch opened and write the record exactly once, on every route. On a timeout or
     * a cancellation a diff panel may still be on screen, and the user must not be left looking at a panel whose
     * decision has already been made.
     */
    @Test
    void teardownClosesTheOpenDiffAndLogsOnce() throws IOException {
        String driver = driverSource();
        int idx = driver.indexOf("private void finish()");
        assertTrue(idx >= 0, "the driver must have a single finish() teardown");
        String finish = driver.substring(idx);
        assertTrue(finish.contains("if (finished)"),
                "finish() must be idempotent so the log is written exactly once. Body:\n" + finish);
        assertTrue(finish.contains("cancelAndClose()"),
                "finish() must close a diff this batch left open. Body:\n" + finish);
        assertTrue(finish.contains("pendingResponseCancellers.remove(canceller)"),
                "finish() must deregister the canceller. Body:\n" + finish);
        assertTrue(finish.contains("conversationPanel.addSystemMessage(review.log())"),
                "finish() must write the review's log to the message panel. Body:\n" + finish);
    }

    /**
     * A file that cannot be read is a file the user cannot review, so it declines the whole set rather than falling
     * back to a blind yes/no on content nobody has seen.
     */
    @Test
    void anUnreadableFileDeclinesTheWholeSet() throws IOException {
        String driver = driverSource();
        assertTrue(driver.contains("review.renderFailed(fp)"),
                "a file that cannot be read or has no proposed content must go through review.renderFailed");
    }

    /**
     * Fail fast on an unrenderable file: the check runs at start(), BEFORE the affordance is shown or any diff opened.
     * The decided behaviour is that a file the user cannot review declines the set, so making them step through the
     * files that did render and only then refusing is a worse version of the same answer.
     */
    @Test
    void anUnrenderableFileIsDetectedBeforeAnyDiffIsOpened() throws IOException {
        String driver = driverSource();
        int idx = driver.indexOf("void start()");
        assertTrue(idx >= 0, "the driver must have a start()");
        int end = driver.indexOf("private MultiPermissionItem firstUnrenderable()", idx);
        assertTrue(end > idx, "could not bound start()");
        String start = driver.substring(idx, end);

        int failFast = start.indexOf("firstUnrenderable()");
        int showAffordance = start.indexOf("conversationPanel.showMultiConfirm(");
        assertTrue(failFast >= 0, "start() must check for an unrenderable item. Body:\n" + start);
        assertTrue(showAffordance > failFast,
                "the check must run BEFORE the affordance is shown, not after. Body:\n" + start);
        assertTrue(start.contains("review.renderFailed("),
                "an unrenderable item must decline through review.renderFailed. Body:\n" + start);
    }

    /**
     * ONE deadline for the batch, armed when the review starts. Arming per file would give an N-file set N times the
     * wait a single diff gets, which is the opposite of the decision: the set shares one budget and later panels
     * inherit what is left.
     */
    @Test
    void oneWholeSetDeadlineIsArmedAtStart() throws IOException {
        String driver = driverSource();
        assertTrue(driver.contains("TimeoutEnum.USER_APPROVAL_WAIT_MILLIS"),
                "the deadline must come from TimeoutEnum, never a literal");
        assertTrue(driver.contains("deadline.setRepeats(false)"),
                "the deadline fires once for the whole set");
        int armed = driver.split("deadline\\.start\\(\\)", -1).length - 1;
        assertEquals(1, armed, "the deadline must be armed exactly once, not per file");
        int constructed = driver.split("new Timer\\(", -1).length - 1;
        assertEquals(1, constructed, "there must be one timer for the batch, not one per file");
    }

    /**
     * Expiry is recorded as a timeout, not as rejectAll(). Both decline the set and produce the same reply, but the log
     * would then read "User rejected the change set without reviewing" for something the user never did.
     */
    @Test
    void expiryIsRecordedAsATimeoutNotAUserRejection() throws IOException {
        String driver = driverSource();
        int idx = driver.indexOf("private void expire()");
        assertTrue(idx >= 0, "the driver must have an expire() hook for the deadline");
        int end = driver.indexOf("private void finish()", idx);
        assertTrue(end > idx, "could not bound expire()");
        String expire = driver.substring(idx, end);
        assertTrue(expire.contains("review.timedOut()"),
                "expiry must go through review.timedOut(). Body:\n" + expire);
        assertFalse(expire.contains("review.rejectAll()"),
                "expiry must not be recorded as a rejection the user chose. Body:\n" + expire);
        assertTrue(expire.contains("finish()"),
                "expiry must tear down through finish() like every other exit. Body:\n" + expire);
    }

    /**
     * The timer is stopped on every exit, not only its own. Leaving it armed is harmless — the review ignores late
     * calls — but relying on that harmlessness to cover a leak is not the same as not leaking.
     */
    @Test
    void theDeadlineIsStoppedOnEveryExitPath() throws IOException {
        String driver = driverSource();
        int idx = driver.indexOf("private void finish()");
        assertTrue(idx >= 0, "the driver must have a single finish() teardown");
        String finish = driver.substring(idx);
        assertTrue(finish.contains("deadline.stop()"),
                "finish() must stop the deadline, so every exit path disposes it. Body:\n" + finish);
    }

    /**
     * The main-panel affordance reuses the existing inline confirm widget rather than a new one, and declines the set
     * without opening any diff.
     */
    @Test
    void theMainPanelAffordanceRejectsWithoutOpeningAnyDiff() throws IOException {
        String driver = driverSource();
        assertTrue(driver.contains("conversationPanel.showMultiConfirm("),
                "the driver must show the inline main-panel affordance");
        assertTrue(driver.contains("review.rejectAll()"),
                "the affordance's reject must decline the whole set without reviewing");
    }
}
