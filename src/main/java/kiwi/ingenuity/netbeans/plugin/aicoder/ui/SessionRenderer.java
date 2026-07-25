package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Component;
import java.util.Arrays;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import org.netbeans.api.project.ui.OpenProjects;

public final class SessionRenderer extends DefaultTableCellRenderer {

    private static boolean isProjectOpen(String path) {
        return path != null && Arrays.stream(OpenProjects.getDefault().getOpenProjects()).anyMatch(p -> p.getProjectDirectory().getPath().equals(path));
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
        Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
        int model = table.convertRowIndexToModel(row);
        if (table.getModel() instanceof SessionTableModel sessions) {
            AiSession s = sessions.row(model);
            c.setForeground(!selected && s.projectPath() != null && !isProjectOpen(s.projectPath()) ? UIManager.getColor("Label.disabledForeground") : table.getForeground());
        }
        return c;
    }
}
