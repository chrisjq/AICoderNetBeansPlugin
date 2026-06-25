package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.events.DiffDecisionListener;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.windows.TopComponent;

/**
 * Side-by-side diff view. Default shows a compact "changes only" view with
 * configurable context lines; a toggle switches to the full file view. A
 * minimap bar on the right shows change positions and supports click-to-jump.
 * Left and right panes scroll vertically together but independently
 * horizontally.
 */
public class AiDiffTopComponent extends TopComponent {

    private static final Logger LOG = Logger.getLogger(AiDiffTopComponent.class.getName());
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final int GUTTER_W = 52;

    /**
     * ---- Shared color helpers ----
     */
    static boolean isDarkTheme() {
        Color bg = UIManager.getColor("TextArea.background");
        return bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 128;
    }

    static Color uiColorOrFallback(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    static Color shiftColor(Color c, int delta) {
        return new Color(
                clampChannel(c.getRed() + delta),
                clampChannel(c.getGreen() + delta),
                clampChannel(c.getBlue() + delta));
    }

    private static int clampChannel(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String computeDisplayPath(String absolutePath) {
        try {
            Path abs = Path.of(absolutePath);
            for (Project p : OpenProjects.getDefault().getOpenProjects()) {
                File projFile = FileUtil.toFile(p.getProjectDirectory());
                if (projFile != null) {
                    Path projPath = projFile.toPath();
                    if (abs.startsWith(projPath)) {
                        String rel = projPath.relativize(abs).toString().replace(File.separatorChar, '/');
                        return projFile.getName() + ": " + rel;
                    }
                }
            }
        }
        catch (Exception ignored) {
        }
        // No open project matches — show the file's parent directory
        Path parent = Path.of(absolutePath).getParent();
        return parent != null ? parent.toString() : absolutePath;
    }

    private static JTable makeTable(SidedModel model, boolean left) {
        JTable tbl = new JTable(model);
        tbl.setDefaultRenderer(Object.class, new SidedCellRenderer(model, left));
        tbl.setShowGrid(false);
        tbl.setIntercellSpacing(new Dimension(0, 0));
        tbl.setRowSelectionAllowed(false);
        tbl.setColumnSelectionAllowed(false);
        tbl.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tbl.setFont(MONO);
        tbl.setRowHeight(MONO.getSize() + 4);
        tbl.setTableHeader(null);
        return tbl;
    }

    private static void adjustColumnWidths(JTable tbl, JScrollPane sp) {
        int vw = sp.getViewport().getWidth();
        if (vw < GUTTER_W + 10) {
            return;
        }
        java.awt.FontMetrics fm = tbl.getFontMetrics(tbl.getFont());
        int maxLine = 0;
        for (int row = 0; row < tbl.getRowCount(); row++) {
            Object val = tbl.getValueAt(row, 1);
            if (val instanceof String s) {
                int w = fm.stringWidth(s);
                if (w > maxLine) {
                    maxLine = w;
                }
            }
        }
        int contentW = Math.max(vw - GUTTER_W, maxLine + 12);
        TableColumnModel cm = tbl.getColumnModel();
        cm.getColumn(0).setMinWidth(GUTTER_W);
        cm.getColumn(0).setMaxWidth(GUTTER_W);
        cm.getColumn(0).setPreferredWidth(GUTTER_W);
        cm.getColumn(1).setPreferredWidth(contentW);
    }

    /**
     * ---- Row builders ----
     */
    private static List<DiffRow> buildFullRows(String original, String proposed) {
        List<String> origLines = Arrays.asList(original.split("\n", -1));
        List<String> propLines = Arrays.asList(proposed.split("\n", -1));

        Patch<String> patch;
        try {
            patch = DiffUtils.diff(origLines, propLines);
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Could not compute diff", e);
            List<DiffRow> rows = new ArrayList<>();
            int max = Math.max(origLines.size(), propLines.size());
            for (int i = 0; i < max; i++) {
                String l = i < origLines.size() ? origLines.get(i) : "";
                String r = i < propLines.size() ? propLines.get(i) : "";
                rows.add(DiffRow.context(i + 1, l, i + 1, r));
            }
            return rows;
        }

        List<DiffRow> rows = new ArrayList<>();
        int oi = 0, pi = 0;

        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int origPos = delta.getSource().getPosition();
            int propPos = delta.getTarget().getPosition();

            while (oi < origPos && pi < propPos) {
                rows.add(DiffRow.context(oi + 1, origLines.get(oi), pi + 1, propLines.get(pi)));
                oi++;
                pi++;
            }

            List<String> removed = delta.getSource().getLines();
            List<String> added = delta.getTarget().getLines();
            int max = Math.max(removed.size(), added.size());

            for (int i = 0; i < max; i++) {
                boolean hasLeft = i < removed.size();
                boolean hasRight = i < added.size();
                if (hasLeft && hasRight) {
                    rows.add(DiffRow.changed(
                            oi + 1, removed.get(i), RowKind.REMOVED,
                            pi + 1, added.get(i), RowKind.ADDED));
                    oi++;
                    pi++;
                }
                else if (hasLeft) {
                    rows.add(DiffRow.blankLeft(oi + 1, removed.get(i), RowKind.REMOVED));
                    oi++;
                }
                else {
                    rows.add(DiffRow.blank(pi + 1, added.get(i), RowKind.ADDED));
                    pi++;
                }
            }
        }

        while (oi < origLines.size() && pi < propLines.size()) {
            rows.add(DiffRow.context(oi + 1, origLines.get(oi), pi + 1, propLines.get(pi)));
            oi++;
            pi++;
        }

        return rows;
    }

    private static List<DiffRow> buildCompactRows(List<DiffRow> full, int contextLines) {
        int n = full.size();
        boolean[] include = new boolean[n];

        for (int i = 0; i < n; i++) {
            RowKind lk = full.get(i).leftKind();
            RowKind rk = full.get(i).rightKind();
            if (lk == RowKind.ADDED || lk == RowKind.REMOVED
                    || rk == RowKind.ADDED || rk == RowKind.REMOVED) {
                int from = Math.max(0, i - contextLines);
                int to = Math.min(n - 1, i + contextLines);
                for (int j = from; j <= to; j++) {
                    include[j] = true;
                }
            }
        }

        List<DiffRow> result = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (include[i]) {
                result.add(full.get(i));
                i++;
            }
            else {
                int gapStart = i;
                while (i < n && !include[i]) {
                    i++;
                }
                result.add(DiffRow.separator(i - gapStart));
            }
        }
        return result;
    }

    /**
     * ---- Instance state ----
     */
    private final String filePath;
    private final String sessionName;
    private DiffDecisionListener decisionListener;
    private boolean decided = false;
    private boolean compactView = true;
    private boolean syncingScroll = false;
    private List<DiffRow> fullRows;
    private List<DiffRow> compactRows;
    private SidedModel leftModel;
    private SidedModel rightModel;
    private JTable leftTable;
    private JTable rightTable;
    private JScrollPane leftScrollPane;
    private JScrollPane rightScrollPane;
    private DiffOverviewBar overviewBar;

    public AiDiffTopComponent(String filePath, String originalContent, String proposedContent, String sessionName) {
        this.filePath = filePath;
        this.sessionName = sessionName != null ? sessionName : "AI";
        String displayPath = computeDisplayPath(filePath);
        String fileName = Path.of(filePath).getFileName().toString();
        boolean isNewFile = originalContent == null || originalContent.isBlank();
        setName((isNewFile ? "New File: " : "Diff: ") + fileName + " [" + this.sessionName + "]");
        setToolTipText(filePath);
        setLayout(new BorderLayout());
        if (isNewFile) {
            initNewFileComponents(proposedContent != null ? proposedContent : "");
        }
        else {
            initComponents(originalContent, proposedContent, displayPath);
        }
    }

    private void openFileInEditor() {
        java.io.File f = new java.io.File(filePath);
        org.openide.filesystems.FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(f));
        if (fo != null) {
            try {
                DataObject dob = DataObject.find(fo);
                OpenCookie oc = dob.getLookup().lookup(OpenCookie.class);
                if (oc != null) {
                    oc.open();
                }
            }
            catch (DataObjectNotFoundException ex) {
                LOG.log(Level.FINE, "Cannot open file in editor", ex);
            }
        }
    }

    private void makeFileLink(JLabel lbl) {
        lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lbl.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openFileInEditor();
            }
        });
    }

    public void addDecisionListener(DiffDecisionListener listener) {
        this.decisionListener = listener;
    }

    /**
     * ---- UI construction ----
     */
    private void initComponents(String originalContent, String proposedContent, String displayPath) {
        fullRows = buildFullRows(originalContent, proposedContent);
        compactRows = buildCompactRows(fullRows, PluginSettings.getDiffContextLines());

        leftModel = new SidedModel(compactRows, true);
        rightModel = new SidedModel(compactRows, false);

        leftTable = makeTable(leftModel, true);
        rightTable = makeTable(rightModel, false);

        // Left pane: no vertical scrollbar (driven by right)
        leftScrollPane = new JScrollPane(leftTable,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // Right pane: vertical scrollbar visible
        rightScrollPane = new JScrollPane(rightTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        // Sync vertical scroll: Y positions stay equal, X stays independent
        leftScrollPane.getViewport().addChangeListener(e -> {
            if (!syncingScroll) {
                syncingScroll = true;
                Point lp = leftScrollPane.getViewport().getViewPosition();
                Point rp = rightScrollPane.getViewport().getViewPosition();
                rightScrollPane.getViewport().setViewPosition(new Point(rp.x, lp.y));
                syncingScroll = false;
            }
        });
        rightScrollPane.getViewport().addChangeListener(e -> {
            if (!syncingScroll) {
                syncingScroll = true;
                Point rp = rightScrollPane.getViewport().getViewPosition();
                Point lp = leftScrollPane.getViewport().getViewPosition();
                leftScrollPane.getViewport().setViewPosition(new Point(lp.x, rp.y));
                syncingScroll = false;
            }
        });

        leftScrollPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                adjustColumnWidths(leftTable, leftScrollPane);
            }
        });
        rightScrollPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                adjustColumnWidths(rightTable, rightScrollPane);
            }
        });
        adjustColumnWidths(leftTable, leftScrollPane);
        adjustColumnWidths(rightTable, rightScrollPane);

        overviewBar = new DiffOverviewBar();

        JPanel diffPanel = new JPanel(new GridLayout(1, 2, 0, 0));
        diffPanel.add(leftScrollPane);
        diffPanel.add(rightScrollPane);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(diffPanel, BorderLayout.CENTER);
        centerPanel.add(overviewBar, BorderLayout.EAST);

        add(buildHeaderPanel(displayPath), BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        JButton rejectBtn = new JButton("Reject");
        JButton acceptBtn = new JButton("Accept");
        rejectBtn.addActionListener(e -> handleReject());
        acceptBtn.addActionListener(e -> handleAccept());
        buttons.add(rejectBtn);
        buttons.add(acceptBtn);
        add(buttons, BorderLayout.SOUTH);
    }

    private void initNewFileComponents(String content) {
        boolean dark = isDarkTheme();
        Color base = uiColorOrFallback("TextArea.background", dark ? new Color(0x1e, 0x1e, 0x1e) : Color.WHITE);
        Color fg = uiColorOrFallback("TextArea.foreground", dark ? Color.WHITE : Color.BLACK);
        Color hdr = shiftColor(base, 20);

        String fileName = Path.of(filePath).getFileName().toString();
        Path parentPath = Path.of(filePath).getParent();
        String displayDir = computeDisplayPath(parentPath != null ? parentPath.toString() : filePath);
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        header.setBackground(hdr);
        header.setOpaque(true);
        header.setToolTipText(filePath);
        header.add(new BadgeLabel("New File:", new Color(0x00, 0xBB, 0x44)));
        header.add(new BadgeLabel(sessionName, new Color(0x40, 0x80, 0xC0)));
        JLabel newFileText = new JLabel(fileName + " @ " + displayDir);
        newFileText.setFont(MONO.deriveFont(Font.BOLD, 11f));
        newFileText.setForeground(fg);
        header.add(newFileText);

        JTextArea contentArea = new JTextArea(content);
        contentArea.setFont(MONO);
        contentArea.setEditable(false);
        contentArea.setBackground(base);
        contentArea.setForeground(fg);
        contentArea.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JScrollPane contentScroll = new JScrollPane(contentArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        JButton rejectBtn = new JButton("Reject");
        JButton acceptBtn = new JButton("Accept");
        rejectBtn.addActionListener(e -> handleReject());
        acceptBtn.addActionListener(e -> handleAccept());
        buttons.add(rejectBtn);
        buttons.add(acceptBtn);

        add(header, BorderLayout.NORTH);
        add(contentScroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    private JPanel buildHeaderPanel(String displayPath) {
        boolean dark = isDarkTheme();
        Color base = uiColorOrFallback("TextArea.background",
                dark ? new Color(0x1e, 0x1e, 0x1e) : Color.WHITE);
        Color fg = uiColorOrFallback("TextArea.foreground",
                dark ? Color.WHITE : Color.BLACK);
        Color hdr = shiftColor(base, 20);

        JToggleButton changesBtn = new JToggleButton("Changes");
        JToggleButton fullBtn = new JToggleButton("Full");
        changesBtn.setFont(changesBtn.getFont().deriveFont(11f));
        fullBtn.setFont(fullBtn.getFont().deriveFont(11f));
        changesBtn.setSelected(true);
        ButtonGroup bg = new ButtonGroup();
        bg.add(changesBtn);
        bg.add(fullBtn);
        changesBtn.addActionListener(e -> switchView(true));
        fullBtn.addActionListener(e -> switchView(false));

        JPanel togglePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        togglePanel.setOpaque(false);
        togglePanel.add(changesBtn);
        togglePanel.add(fullBtn);

        JLabel origLabel = new JLabel("<html>Original&nbsp;&nbsp;<u>" + escapeHtml(displayPath) + "</u></html>");
        JLabel propLabel = new JLabel("<html>Proposed&nbsp;&nbsp;<u>" + escapeHtml(displayPath) + "</u></html>");
        origLabel.setFont(MONO.deriveFont(Font.BOLD, 11f));
        propLabel.setFont(MONO.deriveFont(Font.BOLD, 11f));
        origLabel.setForeground(fg);
        propLabel.setForeground(fg);
        origLabel.setOpaque(false);
        propLabel.setOpaque(false);
        origLabel.setToolTipText(filePath);
        propLabel.setToolTipText(filePath);
        origLabel.setBorder(BorderFactory.createEmptyBorder(2, GUTTER_W + 4, 2, 4));
        propLabel.setBorder(BorderFactory.createEmptyBorder(2, GUTTER_W + 4, 2, 4));
        makeFileLink(origLabel);
        makeFileLink(propLabel);
        int labelH = origLabel.getPreferredSize().height;
        origLabel.setMinimumSize(new java.awt.Dimension(0, labelH));
        propLabel.setMinimumSize(new java.awt.Dimension(0, labelH));

        JPanel labelPanel = new JPanel(new GridLayout(1, 2, 0, 0));
        labelPanel.setBackground(hdr);
        labelPanel.add(origLabel);
        labelPanel.add(propLabel);

        String fileName = Path.of(filePath).getFileName().toString();
        Path parentPath = Path.of(filePath).getParent();
        String displayDir = computeDisplayPath(parentPath != null ? parentPath.toString() : filePath);
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        titlePanel.setBackground(hdr);
        titlePanel.setOpaque(true);
        titlePanel.setToolTipText(filePath);
        titlePanel.add(new BadgeLabel("Diff:", new Color(0xFF, 0x8C, 0x00)));
        titlePanel.add(new BadgeLabel(sessionName, new Color(0x40, 0x80, 0xC0)));
        JLabel diffText = new JLabel(fileName + " @ " + displayDir);
        diffText.setFont(MONO.deriveFont(Font.BOLD, 11f));
        diffText.setForeground(fg);
        diffText.setToolTipText(filePath);
        makeFileLink(diffText);
        titlePanel.add(diffText);

        JPanel columnHeaders = new JPanel(new BorderLayout());
        columnHeaders.setBackground(hdr);
        columnHeaders.add(togglePanel, BorderLayout.WEST);
        columnHeaders.add(labelPanel, BorderLayout.CENTER);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(hdr);
        headerPanel.add(titlePanel, BorderLayout.NORTH);
        headerPanel.add(columnHeaders, BorderLayout.CENTER);
        return headerPanel;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(() -> {
            if (leftTable != null) {
                adjustColumnWidths(leftTable, leftScrollPane);
                adjustColumnWidths(rightTable, rightScrollPane);
            }
            scrollToFirstChange();
        });
    }

    private void switchView(boolean compact) {
        if (compactView == compact) {
            return;
        }
        compactView = compact;
        List<DiffRow> rows = compact ? compactRows : fullRows;
        leftModel.setRows(rows);
        rightModel.setRows(rows);
        adjustColumnWidths(leftTable, leftScrollPane);
        adjustColumnWidths(rightTable, rightScrollPane);
        SwingUtilities.invokeLater(this::scrollToFirstChange);
        overviewBar.repaint();
    }

    private void scrollToFirstChange() {
        if (leftModel == null) {
            return;
        }
        List<DiffRow> rows = leftModel.getRows();
        for (int i = 0; i < rows.size(); i++) {
            DiffRow row = rows.get(i);
            if (row.leftKind() == RowKind.ADDED || row.leftKind() == RowKind.REMOVED
                    || row.rightKind() == RowKind.ADDED || row.rightKind() == RowKind.REMOVED) {
                leftTable.scrollRectToVisible(leftTable.getCellRect(i, 0, true));
                return;
            }
        }
    }

    /**
     * ---- Accept / Reject ----
     */
    private void handleAccept() {
        decided = true;
        try {
            if (decisionListener != null) {
                decisionListener.onAccepted();
            }
        }
        finally {
            close();
        }
    }

    private void handleReject() {
        decided = true;
        try {
            if (decisionListener != null) {
                decisionListener.onRejected();
            }
        }
        finally {
            close();
        }
    }

    /**
     * Closes this diff without firing the decision listener. Called by the host
     * AiTopComponent on teardown so late clicks cannot act on a stopped
     * backend.
     */
    public void cancelAndClose() {
        decided = true;
        close();
    }

    @Override
    public void componentClosed() {
        if (!decided && decisionListener != null) {
            decided = true;
            decisionListener.onRejected();
        }
        decisionListener = null;
        fullRows = null;
        compactRows = null;
        leftModel = null;
        rightModel = null;
    }

    @Override
    public int getPersistenceType() {
        return TopComponent.PERSISTENCE_NEVER;
    }

    /**
     * ---- Table model (one per side) ----
     */
    private static class SidedModel extends AbstractTableModel {

        private List<DiffRow> rows;
        private final boolean left;

        SidedModel(List<DiffRow> rows, boolean left) {
            this.rows = rows;
            this.left = left;
        }

        void setRows(List<DiffRow> newRows) {
            this.rows = newRows;
            fireTableDataChanged();
        }

        List<DiffRow> getRows() {
            return rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int col) {
            return col == 1 ? (left ? "Original" : "Proposed") : "";
        }

        @Override
        public Object getValueAt(int row, int col) {
            DiffRow dr = rows.get(row);
            if (left) {
                return col == 0
                        ? (dr.leftNum() < 0 ? "" : String.valueOf(dr.leftNum()))
                        : dr.leftText();
            }
            else {
                return col == 0
                        ? (dr.rightNum() < 0 ? "" : String.valueOf(dr.rightNum()))
                        : dr.rightText();
            }
        }
    }

    /**
     * ---- Cell renderer ----
     */
    private static class SidedCellRenderer extends DefaultTableCellRenderer {

        private final SidedModel model;
        private final boolean left;
        private final Color gutterBg, gutterFg, contextBg, contentFg;
        private final Color addBg, removeBg, blankBg, separatorBg, separatorFg;

        SidedCellRenderer(SidedModel model, boolean left) {
            this.model = model;
            this.left = left;
            boolean dark = isDarkTheme();
            Color base = uiColorOrFallback("TextArea.background",
                    dark ? new Color(0x1e, 0x1e, 0x1e) : Color.WHITE);
            Color fg = uiColorOrFallback("TextArea.foreground",
                    dark ? Color.WHITE : Color.BLACK);
            this.contextBg = base;
            this.contentFg = fg;
            this.gutterBg = shiftColor(base, dark ? 12 : -12);
            this.gutterFg = dark ? new Color(0x70, 0x85, 0xa0) : new Color(0x88, 0x88, 0x88);
            this.addBg = dark ? new Color(0x18, 0x3d, 0x18) : new Color(0xc8, 0xff, 0xc8);
            this.removeBg = dark ? new Color(0x3d, 0x18, 0x18) : new Color(0xff, 0xc8, 0xc8);
            this.blankBg = shiftColor(base, dark ? -10 : 8);
            this.separatorBg = shiftColor(base, dark ? 28 : -28);
            this.separatorFg = dark ? new Color(0x88, 0x99, 0xaa) : new Color(0x55, 0x66, 0x77);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(
                    table, value, false, false, row, col);
            lbl.setOpaque(true);
            lbl.setFont(MONO);

            DiffRow dr = model.getRows().get(row);
            if (dr.leftKind() == RowKind.SEPARATOR) {
                lbl.setBackground(separatorBg);
                lbl.setForeground(separatorFg);
                lbl.setFont(MONO.deriveFont(Font.ITALIC));
                if (col == 1) {
                    lbl.setHorizontalAlignment(SwingConstants.CENTER);
                    lbl.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 2));
                }
                else {
                    lbl.setText("");
                    lbl.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 6));
                }
                return lbl;
            }

            boolean gutter = (col == 0);
            RowKind kind = left ? dr.leftKind() : dr.rightKind();

            if (gutter) {
                lbl.setBackground(gutterBg);
                lbl.setForeground(gutterFg);
                lbl.setHorizontalAlignment(SwingConstants.RIGHT);
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 6));
            }
            else {
                lbl.setBackground(bgFor(kind));
                lbl.setForeground(contentFg);
                lbl.setHorizontalAlignment(SwingConstants.LEFT);
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 2));
            }
            return lbl;
        }

        private Color bgFor(RowKind k) {
            return switch (k) {
                case ADDED ->
                    addBg;
                case REMOVED ->
                    removeBg;
                case BLANK ->
                    blankBg;
                case CONTEXT ->
                    contextBg;
                case SEPARATOR ->
                    separatorBg;
            };
        }
    }

    /**
     * ---- Rounded badge label ----
     */
    private static class BadgeLabel extends JLabel {

        private final Color badgeColor;

        BadgeLabel(String text, Color badgeColor) {
            super(text);
            this.badgeColor = badgeColor;
            setForeground(Color.BLACK);
            setOpaque(false);
            setFont(MONO.deriveFont(Font.BOLD, 11f));
            setBorder(BorderFactory.createEmptyBorder(1, 8, 1, 8));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(badgeColor);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * ---- Row model ----
     */
    private enum RowKind {
        CONTEXT, ADDED, REMOVED, BLANK, SEPARATOR
    }

    private record DiffRow(
            int leftNum,
            String leftText,
            RowKind leftKind,
            int rightNum,
            String rightText,
            RowKind rightKind) {

        static DiffRow context(int ln, String lt, int rn, String rt) {
            return new DiffRow(ln, tab(lt), RowKind.CONTEXT, rn, tab(rt), RowKind.CONTEXT);
        }

        static DiffRow changed(int ln, String lt, RowKind lk, int rn, String rt, RowKind rk) {
            return new DiffRow(ln, tab(lt), lk, rn, tab(rt), rk);
        }

        static DiffRow blank(int rn, String rt, RowKind rk) {
            return new DiffRow(-1, "", RowKind.BLANK, rn, tab(rt), rk);
        }

        static DiffRow blankLeft(int ln, String lt, RowKind lk) {
            return new DiffRow(ln, tab(lt), lk, -1, "", RowKind.BLANK);
        }

        static DiffRow separator(int linesOmitted) {
            String text = "··· " + linesOmitted + " lines ···";
            return new DiffRow(-1, text, RowKind.SEPARATOR, -1, text, RowKind.SEPARATOR);
        }

        private static String tab(String s) {
            return s.replace("\t", "    ");
        }
    }

    /**
     * ---- Minimap overview bar ----
     */
    private class DiffOverviewBar extends JComponent {

        private static final int BAR_WIDTH = 14;

        DiffOverviewBar() {
            setPreferredSize(new Dimension(BAR_WIDTH, 0));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    handleClick(e.getY());
                }
            });
            leftScrollPane.getViewport().addChangeListener(ce -> repaint());
        }

        private void handleClick(int clickY) {
            int h = getHeight();
            if (h == 0 || fullRows == null || fullRows.isEmpty()) {
                return;
            }
            double frac = (double) clickY / h;
            int fullIdx = (int) (frac * fullRows.size());
            fullIdx = Math.max(0, Math.min(fullRows.size() - 1, fullIdx));
            int visRow = toVisibleRow(fullIdx);
            leftTable.scrollRectToVisible(leftTable.getCellRect(visRow, 0, true));
        }

        private int toVisibleRow(int fullIdx) {
            List<DiffRow> cur = leftModel.getRows();
            if (cur == fullRows) {
                return fullIdx;
            }
            DiffRow target = fullRows.get(fullIdx);
            int targetLine = target.leftNum() > 0 ? target.leftNum()
                    : target.rightNum() > 0 ? target.rightNum() : -1;
            if (targetLine < 0) {
                return 0;
            }
            int bestRow = 0;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < cur.size(); i++) {
                DiffRow cr = cur.get(i);
                int ln = cr.leftNum() > 0 ? cr.leftNum() : cr.rightNum();
                if (ln > 0) {
                    int dist = Math.abs(ln - targetLine);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestRow = i;
                    }
                }
            }
            return bestRow;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth();
            int h = getHeight();
            if (fullRows == null) {
                return;
            }
            int n = fullRows.size();
            if (n == 0 || h == 0) {
                return;
            }

            boolean dark = isDarkTheme();
            Color base = uiColorOrFallback("TextArea.background",
                    dark ? new Color(0x1e, 0x1e, 0x1e) : Color.WHITE);
            g.setColor(shiftColor(base, dark ? 15 : -15));
            g.fillRect(0, 0, w, h);

            Color addColor = dark ? new Color(0x18, 0x6d, 0x18) : new Color(0x88, 0xdd, 0x88);
            Color removeColor = dark ? new Color(0x6d, 0x18, 0x18) : new Color(0xdd, 0x88, 0x88);

            for (int i = 0; i < n; i++) {
                int y1 = (int) ((double) i / n * h);
                int y2 = (int) ((double) (i + 1) / n * h);
                if (y2 <= y1) {
                    y2 = y1 + 1;
                }
                DiffRow row = fullRows.get(i);
                if (row.leftKind() == RowKind.REMOVED) {
                    g.setColor(removeColor);
                    g.fillRect(0, y1, w, y2 - y1);
                }
                else if (row.rightKind() == RowKind.ADDED || row.leftKind() == RowKind.ADDED) {
                    g.setColor(addColor);
                    g.fillRect(0, y1, w, y2 - y1);
                }
            }

            // Viewport thumb
            JViewport vp = leftScrollPane.getViewport();
            int tableH = leftTable.getHeight();
            if (tableH > 0) {
                int vpY = vp.getViewPosition().y;
                int vpH = vp.getHeight();
                int vy1 = (int) ((double) vpY / tableH * h);
                int vy2 = (int) ((double) (vpY + vpH) / tableH * h);
                g.setColor(new Color(128, 128, 128, 60));
                g.fillRect(0, vy1, w, vy2 - vy1);
                g.setColor(new Color(128, 128, 128, 150));
                g.drawRect(0, vy1, w - 1, Math.max(1, vy2 - vy1 - 1));
            }
        }
    }
}
