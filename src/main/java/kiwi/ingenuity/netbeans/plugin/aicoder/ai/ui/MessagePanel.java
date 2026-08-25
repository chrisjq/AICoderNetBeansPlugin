package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.BrowserUtil;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;

public class MessagePanel extends JPanel {

    private static final Logger LOG = Logger.getLogger(MessagePanel.class.getName());
    private static final Parser MD_PARSER = Parser.builder()
            .extensions(List.of(
                    StrikethroughExtension.create(),
                    TablesExtension.create(),
                    TaskListItemsExtension.create()))
            .build();
    private static final Color USER_BORDER = new Color(0x45, 0x47, 0x5a);
    private static final Color ASSIST_BORDER = new Color(0x89, 0xb4, 0xfa);
    private static final Color SYSTEM_BORDER = new Color(0xf5, 0xa6, 0x23);
    private static final Color CODE_BG = new Color(0x18, 0x18, 0x25);
    private static final Color RESTORED_FG = new Color(0x58, 0x5b, 0x70);

    private static final Theme RSTA_DARK_THEME = loadDarkTheme();

    private static final int COLLAPSE_LINE_THRESHOLD = 10;

    private static Theme loadDarkTheme() {
        try (InputStream is = MessagePanel.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/dark.xml")) {
            if (is == null) {
                LOG.log(Level.WARNING, "RSyntaxTextArea dark theme resource not found");
                return null;
            }
            return Theme.load(is);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not load RSyntaxTextArea dark theme", e);
            return null;
        }
    }

    public static MessagePanel forRestored(AiMessage m) {
        MessagePanel r = new MessagePanel(m.role(), true);
        r.accumulatedText.append(m.markdownText());
        r.finalise();
        return r;
    }

    /**
     * ---- Content building ----
     */
    private static int linesInNode(Node node) {
        if (node instanceof FencedCodeBlock fcb) {
            return fcb.getLiteral().split("\n", -1).length + 1;
        }
        else if (node instanceof IndentedCodeBlock icb) {
            return icb.getLiteral().split("\n", -1).length;
        }
        else if (node instanceof HtmlBlock hb) {
            return hb.getLiteral().split("\n", -1).length;
        }
        else if (node instanceof Heading || node instanceof ThematicBreak) {
            return 1;
        }
        else if (node instanceof BulletList || node instanceof OrderedList
                || node instanceof BlockQuote) {
            int n = 0;
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                n += linesInNode(c);
            }
            return Math.max(1, n);
        }
        else if (node instanceof ListItem) {
            int n = 0;
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                n += linesInNode(c);
            }
            return Math.max(1, n);
        }
        else if (node instanceof TableBlock) {
            int rows = 0;
            for (Node s = node.getFirstChild(); s != null; s = s.getNext()) {
                for (Node r = s.getFirstChild(); r != null; r = r.getNext()) {
                    rows++;
                }
            }
            return rows + 1;
        }
        else {
            // Paragraph and other inline containers
            int breaks = 0;
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof SoftLineBreak || c instanceof HardLineBreak) {
                    breaks++;
                }
            }
            return breaks + 1;
        }
    }

    /**
     * CommonMark's TablesExtension requires a preceding blank line to recognise a table block when it immediately
     * follows a paragraph or list. This adds blank lines where they're missing, skipping fenced code block interiors.
     */
    private static String ensureTableBlankLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```") || line.startsWith("~~~")) {
                inFence = !inFence;
            }
            if (!inFence && line.startsWith("|") && i > 0) {
                String prev = lines[i - 1];
                if (!prev.isEmpty() && !prev.startsWith("|")) {
                    sb.append("\n");
                }
            }
            sb.append(line);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * ---- Markdown → HTML conversion ----
     */
    private static String nodeToHtml(Node node) {
        if (node instanceof Paragraph) {
            return "<p>" + inlineToHtml(node) + "</p>";
        }
        else if (node instanceof Heading h) {
            int lv = h.getLevel();
            return "<h" + lv + ">" + inlineToHtml(h) + "</h" + lv + ">";
        }
        else if (node instanceof BulletList) {
            return "<ul>" + listItemsToHtml(node) + "</ul>";
        }
        else if (node instanceof OrderedList ol) {
            return "<ol start=\"" + ol.getStartNumber() + "\">" + listItemsToHtml(ol) + "</ol>";
        }
        else if (node instanceof BlockQuote) {
            StringBuilder sb = new StringBuilder("<blockquote>");
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                sb.append(nodeToHtml(c));
            }
            return sb.append("</blockquote>").toString();
        }
        else if (node instanceof ThematicBreak) {
            return "<hr noshade>";
        }
        else if (node instanceof TableBlock) {
            return tableToHtml(node);
        }
        else if (node instanceof FencedCodeBlock fcb) {
            return "<pre><code>" + escapeHtml(fcb.getLiteral().stripTrailing()) + "</code></pre>";
        }
        else if (node instanceof IndentedCodeBlock icb) {
            return "<pre><code>" + escapeHtml(icb.getLiteral().stripTrailing()) + "</code></pre>";
        }
        else if (node instanceof HtmlBlock htmlBlock) {
            return "<pre><code>" + escapeHtml(htmlBlock.getLiteral().stripTrailing()) + "</code></pre>";
        }
        else {
            return "<p>" + inlineToHtml(node) + "</p>";
        }
    }

    private static String listItemsToHtml(Node listNode) {
        StringBuilder sb = new StringBuilder();
        for (Node c = listNode.getFirstChild(); c != null; c = c.getNext()) {
            if (!(c instanceof ListItem)) {
                continue;
            }
            sb.append("<li>");
            for (Node ic = c.getFirstChild(); ic != null; ic = ic.getNext()) {
                if (ic instanceof TaskListItemMarker marker) {
                    sb.append(marker.isChecked() ? "☑ " : "☐ ");
                }
                else if (ic instanceof Paragraph) {
                    sb.append(inlineToHtml(ic));
                }
                else {
                    sb.append(nodeToHtml(ic));
                }
            }
            sb.append("</li>");
        }
        return sb.toString();
    }

    private static String inlineToHtml(Node node) {
        StringBuilder sb = new StringBuilder();
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) {
                sb.append(escapeHtml(t.getLiteral()));
            }
            else if (c instanceof Code code) {
                sb.append("<code>").append(escapeHtml(code.getLiteral())).append("</code>");
            }
            else if (c instanceof StrongEmphasis) {
                sb.append("<b>").append(inlineToHtml(c)).append("</b>");
            }
            else if (c instanceof Emphasis) {
                sb.append("<i>").append(inlineToHtml(c)).append("</i>");
            }
            else if (c instanceof Link link) {
                String dest = link.getDestination();
                boolean safeDest = dest != null && (dest.startsWith("http://") || dest.startsWith("https://")
                        || dest.startsWith("file://") || dest.startsWith("/") || dest.startsWith(".")
                        || dest.startsWith("#"));
                if (safeDest) {
                    sb.append("<a href=\"").append(escapeHtml(dest)).append("\">")
                            .append(inlineToHtml(c))
                            .append("</a>");
                }
                else {
                    sb.append(inlineToHtml(c));
                }
            }
            else if (c instanceof Strikethrough) {
                sb.append("<s>").append(inlineToHtml(c)).append("</s>");
            }
            else if (c instanceof SoftLineBreak) {
                sb.append(" ");
            }
            else if (c instanceof HardLineBreak) {
                sb.append("<br>");
            }
            else if (c instanceof HtmlInline htmlInline) {
                sb.append(escapeHtml(htmlInline.getLiteral()));
            }
            else {
                sb.append(inlineToHtml(c));
            }
        }
        return sb.toString();
    }

    private static String tableToHtml(Node tableBlock) {
        StringBuilder sb = new StringBuilder("<table border=\"1\">");
        for (Node section = tableBlock.getFirstChild(); section != null; section = section.getNext()) {
            String tag = section instanceof TableHead ? "thead" : "tbody";
            sb.append("<").append(tag).append(">");
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) {
                    continue;
                }
                sb.append("<tr>");
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (!(cell instanceof TableCell tc)) {
                        continue;
                    }
                    String cellTag = tc.isHeader() ? "th" : "td";
                    TableCell.Alignment alignment = tc.getAlignment();
                    String alignAttr = alignment == TableCell.Alignment.CENTER ? " align=\"center\""
                            : alignment == TableCell.Alignment.RIGHT ? " align=\"right\""
                                    : "";
                    sb.append("<").append(cellTag).append(alignAttr).append(">")
                            .append(inlineToHtml(tc))
                            .append("</").append(cellTag).append(">");
                }
                sb.append("</tr>");
            }
            sb.append("</").append(tag).append(">");
        }
        return sb.append("</table>").toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static boolean isDarkTheme() {
        Color bg = UIManager.getColor("TextArea.background");
        return bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 128;
    }

    private static Color uiColorOrFallback(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static String langToSyntax(String lang) {
        if (lang == null || lang.isBlank()) {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }
        return switch (lang.toLowerCase().trim()) {
            case "java" ->
                SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "python", "py" ->
                SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "javascript", "js" ->
                SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            case "typescript", "ts" ->
                SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
            case "xml", "html" ->
                SyntaxConstants.SYNTAX_STYLE_XML;
            case "json" ->
                SyntaxConstants.SYNTAX_STYLE_JSON;
            case "sql" ->
                SyntaxConstants.SYNTAX_STYLE_SQL;
            case "bash", "sh" ->
                SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
            case "kotlin" ->
                SyntaxConstants.SYNTAX_STYLE_KOTLIN;
            case "groovy" ->
                SyntaxConstants.SYNTAX_STYLE_GROOVY;
            case "yaml", "yml" ->
                SyntaxConstants.SYNTAX_STYLE_YAML;
            default ->
                SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }

    /**
     * True if the file belongs to one of the currently open NetBeans projects.
     */
    private static boolean isInOpenProject(FileObject fo) {
        Project owner = FileOwnerQuery.getOwner(fo);
        if (owner == null) {
            return false;
        }
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            if (p.equals(owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens a project file in a NetBeans editor tab. Returns false if it could not.
     */
    private static boolean openInEditor(FileObject fo, String url) {
        if (fo == null) {
            return false;
        }
        try {
            OpenCookie oc = DataObject.find(fo).getLookup().lookup(OpenCookie.class);
            if (oc != null) {
                oc.open();
                return true;
            }
        }
        catch (DataObjectNotFoundException ex) {
            LOG.log(Level.WARNING, "Cannot open file in editor: " + url, ex);
        }
        return false;
    }

    /**
     * Opens a local file with the OS default application, in a platform-safe way.
     */
    private static void openInDefaultViewer(java.io.File file, String url) {
        if (file == null) {
            openInBrowser(url);
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(file);
                return;
            }
        }
        catch (Exception ex) {
            LOG.log(Level.FINE, "Desktop.open failed, trying platform command: " + url, ex);
        }
        try {
            new ProcessBuilder(BrowserUtil.platformOpenCommand(file.getPath())).start();
        }
        catch (Exception ex) {
            LOG.log(Level.WARNING, "Could not open file: " + url, ex);
        }
    }

    /**
     * Opens a web URL (or any non-file link) in the system browser.
     */
    private static void openInBrowser(String url) {
        BrowserUtil.openUrl(url);
    }

    /**
     * ---- Syntax-highlighted code block (RSyntaxTextArea) ----
     */
    /**
     * Installs a caret that never scrolls its component into view.
     * <p>
     * Every text component in a message sits inside ConversationPanel's viewport, so when a caret asks to be made
     * visible the request propagates up and moves the WHOLE conversation — jumping the user to a message they were not
     * reading, regardless of the auto-scroll setting, because it never goes near {@code scrollToBottom()}.
     * <p>
     * {@code NEVER_UPDATE} is NOT sufficient and was tried first: it only suppresses caret movement caused by DOCUMENT
     * CHANGES. The actual call arrives asynchronously via {@code DefaultCaret.repaintNewCaret} →
     * {@code adjustVisibility}, which no update policy governs. A stack trace from a live session is what established
     * this, after three plausible-sounding fixes had failed:
     * <pre>
     *   JViewport.scrollRectToVisible
     *   JComponent.scrollRectToVisible (×5, propagating up the hierarchy)
     *   DefaultCaret.adjustVisibility(DefaultCaret.java:317)
     *   FlatCaret.adjustVisibility(FlatCaret.java:151)
     *   DefaultCaret.repaintNewCaret(DefaultCaret.java:1402)
     * </pre> Overriding {@code adjustVisibility} to do nothing is the only reliable way to stop it: it cuts the scroll
     * off at its source rather than trying to predict when it fires. Selection and copy still work — only the
     * auto-scrolling is removed, which is what a read-only transcript wants anyway.
     */
    private static void installQuietCaret(JTextComponent component) {
        DefaultCaret quiet = new DefaultCaret() {
            @Override
            protected void adjustVisibility(Rectangle nloc) {
                // Deliberately nothing. See the javadoc above.
            }
        };
        quiet.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        // A read-only transcript has no insertion point to advertise.
        quiet.setBlinkRate(0);
        component.setCaret(quiet);
    }

    private final AiMessage.Role role;
    private final JPanel contentPanel;
    private final boolean restored;
    private final StringBuilder accumulatedText = new StringBuilder();
    private boolean finalised = false;
    private boolean textExpanded = false;

    public MessagePanel(AiMessage.Role role, boolean restored) {
        this.role = role;
        this.restored = restored;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        // The conversation is a vertical BoxLayout, which places each child at
        // (maxAscent - width * alignmentX) where maxAscent is the largest
        // width * alignmentX among siblings. Leaving this at the JComponent
        // default of CENTER_ALIGNMENT gives message panels a large ascent, which
        // indents every LEFT_ALIGNMENT sibling — QuestionPanel and ConfirmPanel —
        // by roughly half a message width while these still sit at x=0. Match
        // them so every child shares an ascent of zero.
        setAlignmentX(Component.LEFT_ALIGNMENT);

        Color borderColor = switch (role) {
            case USER ->
                USER_BORDER;
            case ASSISTANT ->
                ASSIST_BORDER;
            case SYSTEM ->
                SYSTEM_BORDER;
        };

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, borderColor));

        if (null == role) {
            wrapper.setOpaque(false);
        }
        else {
            switch (role) {
                case USER -> {
                    Color base = UIManager.getColor("Panel.background");
                    if (base == null) {
                        base = new Color(0x1e, 0x1e, 0x2e);
                    }
                    wrapper.setBackground(new Color(
                            Math.min(255, base.getRed() + 18),
                            Math.min(255, base.getGreen() + 18),
                            Math.min(255, base.getBlue() + 18)));
                    wrapper.setOpaque(true);
                }
                case SYSTEM -> {
                    Color base = UIManager.getColor("Panel.background");
                    if (base == null) {
                        base = new Color(0x1e, 0x1e, 0x2e);
                    }
                    wrapper.setBackground(new Color(
                            Math.min(255, base.getRed() + 12),
                            Math.min(255, base.getGreen() + 8),
                            Math.min(255, base.getBlue())));
                    wrapper.setOpaque(true);
                }
                default ->
                    wrapper.setOpaque(false);
            }
        }

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setOpaque(false);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 4));

        wrapper.add(contentPanel, BorderLayout.CENTER);
        add(wrapper, BorderLayout.CENTER);
        setOpaque(false);
    }

    public String getAccumulatedText() {
        return accumulatedText.toString();
    }

    public void appendDelta(String delta) {
        if (finalised) {
            return;
        }
        accumulatedText.append(delta);
        rebuildContent();
    }

    public void finalise() {
        finalised = true;
        rebuildContent();
    }

    void rebuildContent() {
        contentPanel.removeAll();
        String fullText = accumulatedText.toString();

        // Parse the full text once so markdown is always interpreted in complete context.
        Node fullDoc = MD_PARSER.parse(ensureTableBlankLines(fullText));

        // Collapse large user messages — only after finalise, not during streaming
        if (finalised && role == AiMessage.Role.USER) {
            String[] lines = fullText.split("\n", -1);
            if (lines.length > COLLAPSE_LINE_THRESHOLD) {
                if (!textExpanded) {
                    // Keep nodes while cumulative line count stays within the threshold;
                    // drop any node that would push it over.
                    int totalLines = 0;
                    Node c = fullDoc.getFirstChild();
                    while (c != null) {
                        Node next = c.getNext();
                        int nodeLines = linesInNode(c);
                        if (totalLines + nodeLines > COLLAPSE_LINE_THRESHOLD) {
                            c.unlink();
                        }
                        else {
                            totalLines += nodeLines;
                        }
                        c = next;
                    }
                }
                renderNodes(fullDoc);
                String btnLabel = textExpanded
                        ? "Collapse"
                        : "Show all (" + lines.length + " lines)";
                JButton toggleBtn = new JButton(btnLabel);
                toggleBtn.setFont(toggleBtn.getFont().deriveFont(10f));
                toggleBtn.setMargin(new Insets(1, 6, 1, 6));
                toggleBtn.addActionListener(e -> {
                    textExpanded = !textExpanded;
                    rebuildContent();
                });
                JPanel btnRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 2));
                btnRow.setOpaque(false);
                btnRow.add(toggleBtn);
                contentPanel.add(btnRow);
                contentPanel.revalidate();
                contentPanel.repaint();
                return;
            }
        }

        renderNodes(fullDoc);
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    private void renderNodes(Node doc) {
        List<Node> htmlBatch = new ArrayList<>();
        for (Node child = doc.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof FencedCodeBlock || child instanceof IndentedCodeBlock) {
                flushHtmlBatch(htmlBatch);
                String code = child instanceof FencedCodeBlock fcb
                        ? fcb.getLiteral() : ((IndentedCodeBlock) child).getLiteral();
                String lang = child instanceof FencedCodeBlock fcb ? fcb.getInfo() : "";
                contentPanel.add(makeCodeBlock(code, lang));
            }
            else {
                htmlBatch.add(child);
            }
        }
        flushHtmlBatch(htmlBatch);
    }

    private void flushHtmlBatch(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        StringBuilder html = new StringBuilder();
        for (Node node : nodes) {
            html.append(nodeToHtml(node));
        }
        nodes.clear();
        if (!html.isEmpty()) {
            contentPanel.add(makeHtmlPane(html.toString()));
        }
    }

    /**
     * ---- HTML pane ----
     */
    private JComponent makeHtmlPane(String bodyHtml) {
        boolean dark = isDarkTheme();
        Color fg = restored ? RESTORED_FG
                : uiColorOrFallback("TextArea.foreground",
                        dark ? new Color(0xcd, 0xd6, 0xf4) : new Color(0x31, 0x32, 0x44));
        String fgHex = toHex(fg);
        // Inline code: cyan-ish in dark, blue in light
        String codeFgHex = dark ? "#89dceb" : "#1e66f5";
        // Blockquote: fade the main text colour toward background
        Color bqFg = dark
                ? new Color(Math.max(0, fg.getRed() - 55), Math.max(0, fg.getGreen() - 55), Math.max(0, fg.getBlue() - 55))
                : new Color(Math.min(255, fg.getRed() + 55), Math.min(255, fg.getGreen() + 55), Math.min(255, fg.getBlue() + 55));

        String borderHex = dark ? "#45475a" : "#cccccc";
        int fs = kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings.getChatFontSize();
        int cfs = Math.max(8, fs - 2);
        String css = "body{font-family:SansSerif;font-size:" + fs + "pt;color:" + fgHex + ";margin:0;padding:0;}"
                + "code{font-family:Monospaced;font-size:" + cfs + "pt;color:" + codeFgHex + ";}"
                + "p{margin:0 0 4pt 0;}"
                + "h1{font-size:" + (fs + 5) + "pt;font-weight:bold;margin:6pt 0 2pt 0;}"
                + "h2{font-size:" + (fs + 3) + "pt;font-weight:bold;margin:5pt 0 2pt 0;}"
                + "h3{font-size:" + (fs + 1) + "pt;font-weight:bold;margin:4pt 0 2pt 0;}"
                + "h4,h5,h6{font-size:" + fs + "pt;font-weight:bold;margin:3pt 0 1pt 0;}"
                + "ul,ol{margin:0 0 4pt 18pt;padding:0;}"
                + "li{margin:1pt 0;}"
                + "blockquote{margin:2pt 0 2pt 12pt;color:" + toHex(bqFg) + ";}"
                + "pre{font-family:Monospaced;font-size:" + cfs + "pt;margin:2pt 0;}"
                + "hr{margin:4pt 0;}"
                + "table{margin:4pt 0;}"
                + "th,td{padding:3pt 6pt;border:1px solid " + borderHex + ";}"
                + "th{font-weight:bold;}"
                + "s,del{text-decoration:line-through;}"
                + "a{color:" + (dark ? "#89b4fa" : "#1e66f5") + ";}";

        String html = "<html><head><style>" + css + "</style></head><body>" + bodyHtml + "</body></html>";

        JEditorPane pane = new JEditorPane("text/html", html) {
            @Override
            public Dimension getPreferredSize() {
                // BoxLayout calls getPreferredSize() before bounds are set, so
                // getWidth() is 0. Walk up to the first ancestor with a known
                // width (the viewport-tracked ScrollablePanel) and subtract all
                // intermediate insets to get our true available width, then force
                // the HTML view to reflow at that width before measuring height.
                int w = getWidth();
                if (w <= 0) {
                    java.awt.Container ancestor = getParent();
                    while (ancestor != null && ancestor.getWidth() <= 0) {
                        ancestor = ancestor.getParent();
                    }
                    if (ancestor != null) {
                        w = ancestor.getWidth();
                        java.awt.Insets ins = ancestor.getInsets();
                        if (ins != null) {
                            w -= ins.left + ins.right;
                        }
                        for (java.awt.Container c = getParent(); c != null && c != ancestor; c = c.getParent()) {
                            ins = c.getInsets();
                            if (ins != null) {
                                w -= ins.left + ins.right;
                            }
                        }
                    }
                }
                if (w > 0) {
                    setSize(w, Short.MAX_VALUE);
                }
                return super.getPreferredSize();
            }
        };
        pane.setEditable(false);
        // Stop the caret dragging the conversation.
        //
        // This pane sits DIRECTLY inside ConversationPanel's viewport — unlike a code block,
        // which has its own RTextScrollPane to absorb the scroll — so a caret update here
        // propagates scrollRectToVisible all the way up and jumps the whole conversation to
        // this message. DefaultCaret's default policy updates on every document change, and
        // an HTMLDocument fires those as it lays out.
        //
        // That is why the view still moved at turn end and on send with auto-scroll OFF: it
        // never went through scrollToBottom(), so the flag never got a say.
        installQuietCaret(pane);
        pane.setFocusable(true);
        pane.setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));

        Color selBg = UIManager.getColor("TextArea.selectionBackground");
        Color selFg = UIManager.getColor("TextArea.selectionForeground");
        if (selBg != null) {
            pane.setSelectionColor(selBg);
        }
        if (selFg != null) {
            pane.setSelectedTextColor(selFg);
        }

        // Non-editable JEditorPane may not receive focus on click in some LaFs,
        // and NetBeans can intercept Ctrl+C before it reaches the pane — wire both.
        pane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pane.requestFocusInWindow();
            }
        });
        int copyMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        pane.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, copyMask), "nb-copy");
        pane.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_A, copyMask), "select-all");
        pane.getActionMap().put("nb-copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pane.copy();
            }
        });
        pane.getActionMap().put("select-all", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pane.selectAll();
            }
        });

        pane.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                    && pane.isShowing()) {
                SwingUtilities.invokeLater(pane::revalidate);
            }
        });

        // Revalidate when width changes (e.g. window resize) so the HTML view
        // reflows and the pane height is recomputed via getPreferredSize().
        pane.addComponentListener(new java.awt.event.ComponentAdapter() {
            private int lastWidth = 0;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int w = pane.getWidth();
                if (w != lastWidth && w > 0) {
                    lastWidth = w;
                    SwingUtilities.invokeLater(pane::revalidate);
                }
            }
        });

        pane.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }
            // Prefer the raw href (getDescription) over the resolved getURL():
            // for file/path links getURL() gets mangled (file:/ single slash, or
            // resolved against a base into http/file), whereas the description is
            // the exact destination the markdown used.
            String desc = e.getDescription();
            String url = (desc != null && !desc.isBlank()) ? desc
                    : (e.getURL() != null ? e.getURL().toString() : null);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "link click: desc={0} url={1}",
                        new Object[]{desc, e.getURL()});
            }
            if (url == null || url.isBlank()) {
                return;
            }
            // Resolve the local file (if any) the link points at, stripping a
            // file:// scheme and any trailing :linenum suffix.
            String path = MarkdownLinkRouter.localFilePath(url);
            java.io.File file = path != null ? new java.io.File(path) : null;
            boolean exists = file != null && file.exists() && file.isFile();
            FileObject fo = exists ? FileUtil.toFileObject(FileUtil.normalizeFile(file)) : null;
            boolean inProject = fo != null && isInOpenProject(fo);

            switch (MarkdownLinkRouter.route(url, exists, inProject)) {
                case EDITOR -> {
                    if (!openInEditor(fo, url)) {
                        openInDefaultViewer(file, url); // editor unavailable → OS viewer
                    }
                }
                case DEFAULT_VIEWER ->
                    openInDefaultViewer(file, url);
                case BROWSER ->
                    openInBrowser(url);
            }
        });

        return pane;
    }

    private JComponent makeCodeBlock(String code, String lang) {
        RSyntaxTextArea rsta = new RSyntaxTextArea(code.stripTrailing());
        // Same treatment as the message panes, and needed for two reasons here.
        //
        // Horizontally: the constructor leaves the caret at the END of the text, so a block that
        // is ONE long line used to open scrolled hard right, showing the tail and hiding the
        // start of the statement. With the caret quiet, nothing scrolls and it opens at 0.
        //
        // Vertically: this scroll pane sets VERTICAL_SCROLLBAR_NEVER, so it cannot satisfy a
        // vertical scrollRectToVisible itself — the request would propagate straight up and
        // move the whole conversation.
        installQuietCaret(rsta);
        rsta.setSyntaxEditingStyle(langToSyntax(lang));
        rsta.setEditable(false);
        rsta.setCodeFoldingEnabled(false);
        rsta.setBackground(CODE_BG);
        if (RSTA_DARK_THEME != null) {
            RSTA_DARK_THEME.apply(rsta);
        }
        int codeFontSize = Math.max(8, PluginSettings.getChatFontSize() - 2);
        rsta.setFont(rsta.getFont().deriveFont((float) codeFontSize));

        org.fife.ui.rtextarea.RTextScrollPane rsp = new org.fife.ui.rtextarea.RTextScrollPane(rsta, false) {
            @Override
            protected void processMouseWheelEvent(java.awt.event.MouseWheelEvent e) {
                java.awt.Container outer = SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
                if (outer instanceof JScrollPane outerSP) {
                    outerSP.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, outerSP));
                }
                else {
                    super.processMouseWheelEvent(e);
                }
            }
        };
        rsp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);

        JLayeredPane layered = new JLayeredPane() {
            @Override
            public void doLayout() {
                int w = getWidth(), h = getHeight();
                rsp.setBounds(0, 0, w, h);
                Component[] palette = getComponentsInLayer(JLayeredPane.PALETTE_LAYER);
                if (palette.length == 0) {
                    return;
                }
                Component btn = palette[0];
                Dimension ps = btn.getPreferredSize();
                btn.setBounds(w - ps.width - 4, 4, ps.width, ps.height);
            }

            @Override
            public Dimension getPreferredSize() {
                Dimension preferred = rsp.getPreferredSize();
                // Reserve a strip at the bottom for the horizontal scrollbar.
                //
                // The scrollbar policy is AS_NEEDED, so the bar only appears for code wider than
                // the panel. When it did appear, ScrollPaneLayout took its height out of the
                // viewport — and because this pane's preferred height was just the scroll pane's,
                // there was no slack to take it from, so the last line of code was clipped behind
                // the bar. Adding the height here means the code keeps its full height and the
                // bar gets its own space; when no bar is showing the strip is simply empty.
                JScrollBar horizontal = rsp.getHorizontalScrollBar();
                int barHeight = horizontal == null ? 0 : horizontal.getPreferredSize().height;
                return new Dimension(preferred.width, preferred.height + barHeight);
            }
        };
        layered.add(rsp, JLayeredPane.DEFAULT_LAYER);

        String codeText = code.stripTrailing();
        JButton copyBtn = new JButton("⎘");
        copyBtn.setToolTipText("Copy code");
        copyBtn.setFont(copyBtn.getFont().deriveFont((float) codeFontSize));
        copyBtn.setMargin(new Insets(1, 4, 1, 4));
        copyBtn.setFocusable(false);
        copyBtn.setOpaque(true);
        copyBtn.setBackground(new Color(0x31, 0x32, 0x44));
        copyBtn.setForeground(new Color(0xcb, 0xd6, 0xf7));
        copyBtn.setBorder(BorderFactory.createLineBorder(new Color(0x45, 0x47, 0x5a)));
        copyBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(codeText), null);
            copyBtn.setText("✓");
            Timer t = new Timer(1200, ev -> copyBtn.setText("⎘"));
            t.setRepeats(false);
            t.start();
        });
        layered.add(copyBtn, JLayeredPane.PALETTE_LAYER);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CODE_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        panel.add(layered, BorderLayout.CENTER);
        return panel;
    }

}
