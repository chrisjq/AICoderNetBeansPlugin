package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.TableRowSorter;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;

public abstract class TemplatePanel<T> extends JPanel {

    private static final Logger LOG = Logger.getLogger(TemplatePanel.class.getName());

    protected final TemplatePersistenceManager templates;
    protected final Runnable onTemplateChanged;
    private final SimpleTableModel<T> model;
    private final JTable table;
    private final JTextField templateName = new JTextField(22);
    private final Component editorContent;
    private final JButton save = new JButton("Save");
    private final JButton cancel = new JButton("Cancel");
    private final JButton fresh = new JButton("New");
    private final JButton remove = new JButton("Delete");
    private T selected;
    private boolean draft;

    protected TemplatePanel(Component editorContent,
            TemplatePersistenceManager templates,
            Runnable onTemplateChanged,
            String[] columns,
            Function<T, String> name,
            Function<T, Instant> updated,
            Function<T, Instant> created) {
        super(new BorderLayout(6, 6));
        this.editorContent = editorContent;
        this.templates = templates;
        this.onTemplateChanged = onTemplateChanged;
        this.model = new SimpleTableModel<>(columns, name, updated, created);
        this.table = new JTable(model);
        this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.table.setRowSorter(new TableRowSorter<>(model));

        JPanel editor = new ScrollablePanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy = 0;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        editor.add(new JLabel("Name:"), c);
        c.gridx = 1;
        c.weightx = 1;
        editor.add(templateName, c);

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        if (editorContent != null) {
            editor.add(editorContent, c);
        }
        else {
            LOG.log(Level.WARNING, "Editor component is null");
        }

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(fresh);
        actions.add(save);
        actions.add(cancel);
        actions.add(remove);

        JScrollPane tableScrollPane = new JScrollPane(table);
        JScrollPane editorScrollPane = new JScrollPane(editor);
        tableScrollPane.setMinimumSize(new Dimension(0, 0));
        editorScrollPane.setMinimumSize(new Dimension(0, 0));
        // The action row is deliberately OUTSIDE editorScrollPane: New/Save/Cancel/
        // Delete stay pinned to the bottom of the editor side instead of scrolling
        // away with a long template body. Both the config and instruction tabs
        // extend this class, so this applies to each of them.
        JPanel editorSide = new JPanel(new BorderLayout(6, 6));
        editorSide.add(editorScrollPane, BorderLayout.CENTER);
        editorSide.add(actions, BorderLayout.SOUTH);
        editorSide.setMinimumSize(new Dimension(0, 0));
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScrollPane, editorSide);
        splitPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (splitPane.getWidth() > 0) {
                    splitPane.setDividerLocation(0.5);
                    splitPane.removeComponentListener(this);
                }
            }
        });
        add(splitPane, BorderLayout.CENTER);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                select(model.row(table.convertRowIndexToModel(table.getSelectedRow())));
            }
        });
        fresh.addActionListener(e -> startDraft());
        cancel.addActionListener(e -> showEmptyEditor());
        save.addActionListener(e -> persist());
        remove.addActionListener(e -> remove());
    }

    protected final void initPanel() {
        refresh();
        showEmptyEditor();
    }

    protected abstract List<T> loadData() throws IOException;

    protected abstract void saveData(T old, String name) throws IOException;

    protected abstract void deleteData(T value) throws IOException;

    protected abstract void loadEditor(T value);

    protected void clearEditor() {
        templateName.setText("");
    }

    private void select(T value) {
        if (draft) {
            return;
        }
        selected = value;
        templateName.setText(name(value));
        loadEditor(value);
        setEditorState(true, false, true);
    }

    private void startDraft() {
        draft = true;
        selected = null;
        table.clearSelection();
        table.setEnabled(false);
        clearEditor();
        setEditorState(true, true, false);
        templateName.requestFocusInWindow();
    }

    private void showEmptyEditor() {
        draft = false;
        selected = null;
        table.setEnabled(true);
        table.clearSelection();
        clearEditor();
        setEditorState(false, false, false);
    }

    private void setEditorState(boolean editable, boolean creating, boolean canDelete) {
        templateName.setEnabled(editable);
        setEnabledRecursively(editorContent, editable);
        save.setVisible(editable);
        save.setEnabled(editable);
        cancel.setVisible(creating);
        cancel.setEnabled(creating);
        remove.setVisible(canDelete);
        remove.setEnabled(canDelete);
        fresh.setEnabled(!creating);
        revalidate();
        repaint();
    }

    private void setEnabledRecursively(Component component, boolean enabled) {
        if (component == null) {
            return;
        }
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                if (child != null) {
                    setEnabledRecursively(child, enabled);
                }
            }
        }
    }

    private String name(T value) {
        return model.getNameFunction().apply(value);
    }

    private void persist() {
        String name = templateName.getText().trim();
        if (name.isEmpty()) {
            return;
        }
        try {
            saveData(selected, name);
            refresh();
            if (onTemplateChanged != null) {
                onTemplateChanged.run();
            }
            showEmptyEditor();
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not save template", e);
        }
    }

    private void remove() {
        if (selected == null || JOptionPane.showConfirmDialog(this, "Delete template?", "Delete", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            deleteData(selected);
            refresh();
            if (onTemplateChanged != null) {
                onTemplateChanged.run();
            }
            showEmptyEditor();
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete template", e);
        }
    }

    private void refresh() {
        try {
            model.setRows(loadData());
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not load templates", e);
        }
    }
}
