package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.table.AbstractTableModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;

public final class SessionTableModel extends AbstractTableModel {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final String[] columns = {"Name", "Type", "Project", "Last Use", "Created"};
    private List<AiSession> rows = List.of();

    public void setRows(List<AiSession> values) {
        rows = List.copyOf(values);
        fireTableDataChanged();
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
