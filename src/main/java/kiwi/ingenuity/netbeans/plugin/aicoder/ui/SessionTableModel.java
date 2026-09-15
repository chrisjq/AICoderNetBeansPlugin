package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.table.AbstractTableModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.ProjectPathUtil;

public final class SessionTableModel extends AbstractTableModel {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final String[] columns = {"Name", "Type", "Project", "Last Use", "Created"};
    private List<AiSession> rows = List.of();
    private Set<String> canonicalOpenProjectDirs = Set.of();
    private Set<String> openSessionIds = Set.of();

    public void setRows(List<AiSession> values) {
        rows = List.copyOf(values);
        recomputeOpenProjectState();
        fireTableDataChanged();
    }

    /**
     * Replaces the set of open project directories, resolving each to its canonical spelling once. Called when the
     * table is populated and again whenever the IDE's open-project set changes — never from a cell renderer, whose only
     * job is to read the precomputed per-session flag.
     *
     * @param projectDirs currently open project directories; null is treated as none
     */
    public void setOpenProjectDirs(List<File> projectDirs) {
        canonicalOpenProjectDirs = ProjectPathUtil.canonicalDirs(projectDirs);
        recomputeOpenProjectState();
        fireTableDataChanged();
    }

    private void recomputeOpenProjectState() {
        Set<String> open = new HashSet<>();
        for (AiSession s : rows) {
            if (ProjectPathUtil.matchesCanonicalDirs(canonicalOpenProjectDirs, s.projectPath())) {
                open.add(s.id());
            }
        }
        openSessionIds = Set.copyOf(open);
    }

    /**
     * Whether the session's project is currently open, precomputed when the rows or the open-project set changed. A
     * session with no project (null path) counts as open so it is never greyed out.
     */
    public boolean isProjectOpen(int row) {
        AiSession s = rows.get(row);
        return s.projectPath() == null || openSessionIds.contains(s.id());
    }

    public AiSession row(int i) {
        return rows.get(i);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int c) {
        return columns[c];
    }

    @Override
    public Object getValueAt(int r, int c) {
        AiSession s = rows.get(r);
        return switch (c) {
            case 0 ->
                s.name();
            case 1 ->
                s.aiType().displayName();
            case 2 ->
                s.projectPath() == null ? "—" : Path.of(s.projectPath()).getFileName().toString();
            case 3 ->
                DATE_FORMAT.format(s.lastUsedAt());
            default ->
                DATE_FORMAT.format(s.createdAt());
        };
    }
}
