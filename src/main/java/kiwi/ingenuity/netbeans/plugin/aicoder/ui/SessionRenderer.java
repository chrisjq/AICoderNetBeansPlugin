package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Component;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;

public final class SessionRenderer extends DefaultTableCellRenderer {

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
        Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
        int model = table.convertRowIndexToModel(row);
        if (table.getModel() instanceof SessionTableModel sessions) {
            c.setForeground(!selected && !sessions.isProjectOpen(model) ? UIManager.getColor("Label.disabledForeground") : table.getForeground());
        }
        return c;
    }
}
