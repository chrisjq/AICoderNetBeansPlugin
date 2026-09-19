package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * A USER message must render its text literally — no Markdown interpretation — while ASSISTANT/SYSTEM messages keep
 * rendering Markdown exactly as before. {@link MessagePanel#literalToHtml} is the pure conversion the literal path
 * uses; the rest of this class exercises the real, rendered {@link MessagePanel} for the collapse behaviour, which
 * depends on the actual Swing component tree ({@link MessagePanel#contentPanelForTest()}).
 */
class MessagePanelTest {

    // ---- literalToHtml: none of Markdown's syntax may be interpreted ----
    @Test
    void headingMarkerIsNotAHeading() {
        assertEquals("# not a heading", MessagePanel.literalToHtml("# not a heading"));
    }

    @Test
    void asterisksAreNotItalics() {
        assertEquals("*not italics*", MessagePanel.literalToHtml("*not italics*"));
    }

    @Test
    void underscoresAreNotEmphasis() {
        assertEquals("_snake_case_name", MessagePanel.literalToHtml("_snake_case_name"));
    }

    @Test
    void dashIsNotABulletList() {
        assertEquals("- not a list", MessagePanel.literalToHtml("- not a list"));
    }

    @Test
    void numberedLineIsNotAnOrderedList() {
        assertEquals("1. not a numbered list", MessagePanel.literalToHtml("1. not a numbered list"));
    }

    @Test
    void backticksAreNotCode() {
        assertEquals("`not code`", MessagePanel.literalToHtml("`not code`"));
    }

    // ---- literalToHtml: the same bug class must not exist for HTML either ----
    @Test
    void ampersandLessThanAndGreaterThanAreEscaped() {
        assertEquals("&amp;&lt;script&gt;", MessagePanel.literalToHtml("&<script>"));
    }

    // ---- literalToHtml: shape is preserved (newlines, indentation) ----
    @Test
    void newlinesBecomeLineBreaks() {
        assertEquals("line one<br>line two", MessagePanel.literalToHtml("line one\nline two"));
    }

    @Test
    void leadingIndentationSurvives() {
        // Leading indentation is ALL non-breaking, first space included: HTML strips leading whitespace on a
        // line outright (it doesn't merely collapse it to one space), so a plain leading space would vanish
        // completely rather than just lose width.
        assertEquals("&nbsp;&nbsp;&nbsp;&nbsp;indented", MessagePanel.literalToHtml("    indented"));
    }

    @Test
    void singleLeadingSpaceSurvives() {
        // A lone leading space is still leading whitespace — if it stayed a plain space, HTML would
        // strip it entirely and "one" would render with no indent at all.
        assertEquals("&nbsp;one", MessagePanel.literalToHtml(" one"));
    }

    @Test
    void indentationOnAContinuationLineSurvives() {
        // "Line start" means right after a <br> too, not just the very start of the text — the second
        // line's leading spaces must be non-breaking for the same reason as the first line's.
        assertEquals("one<br>&nbsp;&nbsp;&nbsp;two", MessagePanel.literalToHtml("one\n   two"));
    }

    @Test
    void spaceAfterALeadingTabSurvives() {
        // A space directly after a leading tab is still part of the indentation run (the tab doesn't end
        // it) — it must stay non-breaking so mixed tab+space indent keeps its exact column width.
        assertEquals("&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;x", MessagePanel.literalToHtml("\t x"));
    }

    @Test
    void tabsBecomeFourNonBreakingSpaces() {
        assertEquals("&nbsp;&nbsp;&nbsp;&nbsp;x", MessagePanel.literalToHtml("\tx"));
    }

    @Test
    void singleSpacesStayNormalAndBreakable() {
        // No run of 2+ spaces anywhere here, so nothing should turn into &nbsp; at all.
        assertEquals("a perfectly ordinary sentence", MessagePanel.literalToHtml("a perfectly ordinary sentence"));
    }

    @Test
    void midLineSpaceRunStillKeepsItsFirstSpaceBreakable() {
        // Unlike leading indentation, a run of spaces BETWEEN words (not at line start) keeps its first
        // space breakable so long lines can still wrap; only the repeats after it are pinned.
        assertEquals("a &nbsp;&nbsp;b", MessagePanel.literalToHtml("a   b"));
    }

    // ---- MessagePanel: end-to-end collapse for a long USER message ----
    private static JEditorPane paneOf(MessagePanel panel) {
        for (Component c : panel.contentPanelForTest().getComponents()) {
            if (c instanceof JEditorPane pane) {
                return pane;
            }
        }
        return null;
    }

    private static JButton toggleButtonOf(MessagePanel panel) {
        for (Component row : panel.contentPanelForTest().getComponents()) {
            if (row instanceof JPanel rowPanel) {
                for (Component c : rowPanel.getComponents()) {
                    if (c instanceof JButton b) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    /**
     * {@code JEditorPane.getText()} on this pane does NOT return extracted plain text — empirically confirmed by
     * printing it, it returns the editor kit's re-serialized HTML source (head, the full {@code <style>} block, and
     * body), because the content model's characters really do include the markup. Two consequences: (1) a literal
     * marker character like {@code #} or {@code *} is a bad "did Markdown run?" signal, since the {@code <style>} block
     * itself is full of hex colours like {@code #333333}; and (2) the writer word-wraps long runs of body text at its
     * own column width, which can split a literal multi-word substring (e.g. "line 15" became "line \n 15") even though
     * the actual content is intact. Collapsing whitespace runs before a {@code contains} check keeps the check
     * meaningful without being sensitive to where the writer happened to wrap.
     */
    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static MessagePanel buildFinalisedUserPanel(String text) throws Exception {
        MessagePanel[] holder = new MessagePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            MessagePanel panel = new MessagePanel(AiMessage.Role.USER, false);
            panel.appendDelta(text);
            panel.finalise();
            holder[0] = panel;
        });
        return holder[0];
    }

    @Test
    void longUserMessageCollapsesAndTheExpandButtonRevealsTheRest() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 15; i++) {
            if (i > 1) {
                text.append('\n');
            }
            text.append("line ").append(i);
        }
        MessagePanel panel = buildFinalisedUserPanel(text.toString());

        JButton showAll = toggleButtonOf(panel);
        assertNotNull(showAll, "a >10-line user message must show the expand button");
        assertEquals("Show all (15 lines)", showAll.getText());
        String collapsedText = normalizeWhitespace(paneOf(panel).getText());
        assertTrue(collapsedText.contains("line 10"), "the collapsed view must include the 10th line");
        assertFalse(collapsedText.contains("line 11"), "the collapsed view must not include an 11th line");

        SwingUtilities.invokeAndWait(showAll::doClick);

        JButton collapseBtn = toggleButtonOf(panel);
        assertNotNull(collapseBtn, "the button must still be present after expanding");
        assertEquals("Collapse", collapseBtn.getText());
        String expandedText = normalizeWhitespace(paneOf(panel).getText());
        assertTrue(expandedText.contains("line 12"), "expanding must reveal the lines in between, not just the last one");
        assertTrue(expandedText.contains("line 15"), "expanding must reveal every remaining line");
    }

    @Test
    void tenLineUserMessageDoesNotCollapse() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) {
                text.append('\n');
            }
            text.append("line ").append(i);
        }
        MessagePanel panel = buildFinalisedUserPanel(text.toString());

        assertNull(toggleButtonOf(panel), "exactly the threshold's worth of lines must not collapse");
        assertTrue(normalizeWhitespace(paneOf(panel).getText()).contains("line 10"));
    }

    @Test
    void shortUserMessageNeverShowsTheExpandButton() throws Exception {
        MessagePanel panel = buildFinalisedUserPanel("just one short line");

        assertNull(toggleButtonOf(panel));
    }

    // ---- Regression guard: every other role keeps rendering Markdown ----
    //
    // getText() on this pane is the re-serialized HTML source (see normalizeWhitespace's javadoc), so a marker
    // character like "#" or "*" being absent is NOT a reliable "did Markdown run?" signal — the <style> block
    // alone contains plenty of "#" (hex colours). What IS reliable is the actual HTML tag the parser emits for
    // that construct (nodeToHtml/inlineToHtml): a real heading always produces "<h1>", real emphasis always
    // produces "<i>", and the literal (USER) path never emits either, regardless of content.
    @Test
    void assistantMessagesStillRenderMarkdown() throws Exception {
        MessagePanel[] holder = new MessagePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            MessagePanel panel = new MessagePanel(AiMessage.Role.ASSISTANT, false);
            panel.appendDelta("# a real heading");
            panel.finalise();
            holder[0] = panel;
        });

        String text = normalizeWhitespace(paneOf(holder[0]).getText());
        assertTrue(text.contains("a real heading"));
        assertTrue(text.contains("<h1>"), "a real Markdown heading must be parsed into an <h1> tag");
    }

    @Test
    void systemMessagesStillRenderMarkdown() throws Exception {
        MessagePanel[] holder = new MessagePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            MessagePanel panel = new MessagePanel(AiMessage.Role.SYSTEM, false);
            panel.appendDelta("*emphasis*");
            panel.finalise();
            holder[0] = panel;
        });

        String text = normalizeWhitespace(paneOf(holder[0]).getText());
        assertTrue(text.contains("emphasis"));
        assertTrue(text.contains("<i>"), "real Markdown emphasis must be parsed into an <i> tag");
    }

    // ---- End-to-end: the literal path is actually wired up for a real USER-role MessagePanel, not just proven
    // in isolation via literalToHtml above ----
    @Test
    void userMessageHeadingMarkerSurvivesEndToEnd() throws Exception {
        MessagePanel panel = buildFinalisedUserPanel("# not a heading");

        String text = normalizeWhitespace(paneOf(panel).getText());
        assertTrue(text.contains("# not a heading"), "the # marker must survive — a real heading would strip it");
        assertFalse(text.contains("<h1>"), "the literal path must never emit a heading tag");
    }

    @Test
    void userMessageEmphasisMarkersSurviveEndToEnd() throws Exception {
        MessagePanel panel = buildFinalisedUserPanel("*not italics*");

        String text = normalizeWhitespace(paneOf(panel).getText());
        assertTrue(text.contains("*not italics*"), "the * markers must survive — real emphasis would strip them");
        assertFalse(text.contains("<i>"), "the literal path must never emit an emphasis tag");
    }

    @Test
    void userMessageUnderscoreMarkersSurviveEndToEnd() throws Exception {
        // CommonMark disallows intraword "_" emphasis, so this wouldn't produce <i> even on the Markdown path —
        // this test exists purely so every literalToHtml marker case also has an end-to-end counterpart.
        MessagePanel panel = buildFinalisedUserPanel("_snake_case_name");

        String text = normalizeWhitespace(paneOf(panel).getText());
        assertTrue(text.contains("_snake_case_name"), "the _ markers must survive unchanged");
        assertFalse(text.contains("<i>"), "the literal path must never emit an emphasis tag");
    }

    @Test
    void userMessageDashListMarkerSurvivesEndToEnd() throws Exception {
        // A dash-prefixed line is exactly what CommonMark parses as a bullet list — if rebuildContent()'s
        // role==USER dispatch were ever broken, this would silently start rendering as a real <ul> and none
        // of the marker-survival tests above (heading/emphasis/backticks) would catch it, since none of them
        // exercise list syntax.
        MessagePanel panel = buildFinalisedUserPanel("- buy milk");

        String text = normalizeWhitespace(paneOf(panel).getText());
        assertTrue(text.contains("- buy milk"), "the - marker must survive — a real bullet list would strip it");
        assertFalse(text.contains("<ul>"), "the literal path must never emit a bullet list tag");
    }

    @Test
    void userMessageNumberedListMarkerSurvivesEndToEnd() throws Exception {
        MessagePanel panel = buildFinalisedUserPanel("1. first step");

        String text = normalizeWhitespace(paneOf(panel).getText());
        assertTrue(text.contains("1. first step"), "the 1. marker must survive — a real ordered list would strip it");
        assertFalse(text.contains("<ol"), "the literal path must never emit an ordered list tag");
    }

    @Test
    void userMessageBackticksSurviveEndToEnd() throws Exception {
        MessagePanel panel = buildFinalisedUserPanel("`not code`");

        String text = normalizeWhitespace(paneOf(panel).getText());
        assertTrue(text.contains("`not code`"), "the backticks must survive — a real code span would strip them");
        assertFalse(text.contains("<code>"), "the literal path must never emit a code tag");
    }
}
