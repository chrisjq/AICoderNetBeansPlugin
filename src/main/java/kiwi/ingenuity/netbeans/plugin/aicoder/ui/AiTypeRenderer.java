package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Component;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;

public final class AiTypeRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
        super.getListCellRendererComponent(list, value, index, selected, focus);
        if (value instanceof AiTypeEnum type) {
            setText(type.displayName());
        }
        return this;
    }
}
