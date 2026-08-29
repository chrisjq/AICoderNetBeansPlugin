package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Source-level wiring tests for the per-backend confirm-button tooltips.
 * <p>
 * {@code AiTopComponent} cannot be instantiated in a unit test — it eagerly builds a real backend via
 * {@code AiTypeRegistry} — so, like the other AiTopComponent wiring tests, these read the source. They pin the two
 * decisions that cannot drift:
 * <ul>
 * <li>the single-file ConfirmEvent render site must supply the session backend's tooltips (so the text reaches the
 * buttons only when the backend supplies it), and</li>
 * <li>the multi-file batch gate must NOT — "Accept Diffs"/"Reject" approves a whole change set, and a per-backend
 * "remind it to use the MCP tools" hint that is advice about rejecting one tool call says nothing there.</li>
 * </ul>
 */
class AiTopComponentConfirmTooltipWiringTest {

    private static final String SOURCE_PATH
            = "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/ui/AiTopComponent.java";
    private static final String DRIVER_DECLARATION = "private final class MultiReviewDriver";

    private String readSource() throws IOException {
        return Files.readString(Path.of(SOURCE_PATH));
    }

    /**
     * The ConfirmEvent handler must render through the tooltip-carrying overload, reading both strings from the
     * session's backend type. Any of the three being dropped silently reverts the feature to a no-op.
     */
    @Test
    void confirmEventSitePassesTheBackendTooltips() throws IOException {
        String source = readSource();
        int idx = source.indexOf("event instanceof ConfirmEvent ce");
        assertTrue(idx >= 0, "AiTopComponent must have a ConfirmEvent branch");
        int windowEnd = Math.min(idx + 1200, source.length());
        String window = source.substring(idx, windowEnd);
        assertTrue(window.contains("conversationPanel.showConfirm(ce,"),
                "the ConfirmEvent handler must render via showConfirm(ce, ...) so tooltips reach the buttons. Window:\n"
                + window);
        assertTrue(window.contains("session.aiType().confirmAcceptTooltip()"),
                "the accept tooltip must be read from the session's backend type. Window:\n" + window);
        assertTrue(window.contains("session.aiType().confirmRejectTooltip()"),
                "the reject tooltip must be read from the session's backend type. Window:\n" + window);
    }

    /**
     * The batch gate renders through showMultiConfirm (no tooltip plumbing) rather than the ConfirmEvent overload, so
     * per-backend tooltips can never reach it. This is what pins the multi-file/batch decision against later drift.
     */
    @Test
    void multiFileBatchGateIsNotGivenTooltips() throws IOException {
        String driver = driverSource();
        assertTrue(driver.contains("conversationPanel.showMultiConfirm("),
                "the batch gate must render through showMultiConfirm");
        assertFalse(driver.contains("confirmAcceptTooltip"),
                "the multi-file gate must not plumb accept tooltips — it is a batch gate, not a tool confirmation");
        assertFalse(driver.contains("confirmRejectTooltip"),
                "the multi-file gate must not plumb reject tooltips — it is a batch gate, not a tool confirmation");
        assertFalse(driver.contains("setToolTipText"),
                "the multi-file gate must not attach button tooltips directly");
    }

    /**
     * The driver's source, bounded by its OWN braces, so member reordering around it cannot break the bound.
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

    /**
     * Index just past the closing brace of the class body that starts after {@code declStart}, or -1 if it never
     * balances. Braces inside string literals, char literals, text blocks and comments are skipped.
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
}
