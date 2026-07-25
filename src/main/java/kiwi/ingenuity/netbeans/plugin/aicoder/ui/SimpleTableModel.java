package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import javax.swing.table.AbstractTableModel;

public final class SimpleTableModel<T> extends AbstractTableModel {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final String[] columns;
    private final Function<T, String> name;
    private final Function<T, Instant> updated, created;
    private List<T> rows = List.of();

    public SimpleTableModel(String[] c, Function<T, String> n, Function<T, Instant> u, Function<T, Instant> cr) {
        columns = c;
        name = n;
        updated = u;
        created = cr;
    }

    public void setRows(List<T> v) {
        rows = List.copyOf(v);
        fireTableDataChanged();
    }

    public T row(int i) {
        return rows.get(i);
    }

    public Function<T, String> getNameFunction() {
        return name;
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
    public String getColumnName(int i) {
        return columns[i];
    }

    @Override
    public Object getValueAt(int r, int c) {
        return c == 0 ? name.apply(rows.get(r)) : DATE_FORMAT.format((c == 1 ? updated : created).apply(rows.get(r)));
    }
}
