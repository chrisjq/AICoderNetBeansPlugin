package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Component;
import java.util.function.Function;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;

public final class TemplateRenderer<T> extends DefaultListCellRenderer {

    private final Function<T, String> label;
    private final String empty;

    public TemplateRenderer(Function<T, String> label, String empty) {
        this.label = label;
        this.empty = empty;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
        super.getListCellRendererComponent(list, value, index, selected, focus);
        setText(value == null ? empty : label.apply((T) value));
        return this;
    }
}
