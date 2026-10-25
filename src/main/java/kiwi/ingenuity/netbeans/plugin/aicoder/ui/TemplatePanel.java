package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
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
import javax.swing.SwingUtilities;
import javax.swing.table.TableRowSorter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.DialogPlacement;

/**
 * A list of templates on the left and an editor on the right. Everything that touches a template store — which takes a
 * cross-process file lock with no timeout — runs on a single background thread and is applied back on the event
 * dispatch thread, so a busy lock holder can delay the list but never freeze the IDE. A failed load, save or delete is
 * reported to the user and leaves the editor and the table as they were.
 */
public abstract class TemplatePanel<T> extends JPanel {

    private static final Logger LOG = Logger.getLogger(TemplatePanel.class.getName());

    /**
     * One thread for every template panel's store access: operations run in the order they were asked for, so a load
     * issued after a save sees the saved data.
     */
    private static volatile ExecutorService STORE_IO = newStoreExecutor();

    private static ExecutorService newStoreExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "template-store-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Retires the store thread at plugin shutdown, mirroring {@code AiTopComponent.shutdownPersistExecutor()}: lets
     * queued store work finish inside a bounded window, then releases the thread. Called only from the module
     * installer's shutdown path.
     */
    public static void shutdownStoreExecutor() {
        ExecutorService storeIo = STORE_IO;
        storeIo.shutdown();
        try {
            if (!storeIo.awaitTermination(TimeoutEnum.PERSIST_EXECUTOR_SHUTDOWN_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS)) {
                LOG.warning("Template-store tasks still running at plugin shutdown; abandoning the bounded wait");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Restores a usable store executor after a test exercised plugin shutdown.
     */
    static synchronized void resetStoreExecutorForTests() {
        ExecutorService retired = STORE_IO;
        STORE_IO = newStoreExecutor();
        retired.shutdownNow();
    }

    /**
     * The write half of a save, split from capturing the editor's contents so the capture can happen on the event
     * dispatch thread and the write off it.
     */
    @FunctionalInterface
    protected interface StoreWrite {

        void run() throws IOException;
    }

    @FunctionalInterface
    private interface StoreTask {

        void run() throws IOException;
    }

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
    // Last state handed to setEditorState, so the buttons can be recomputed while a store operation is in flight.
    private boolean editorEditable;
    private boolean editorCreating;
    private boolean editorCanDelete;
    private int storeOperationsInFlight;
    private int loadEpoch;
    private volatile CompletableFuture<Void> lastLoad = CompletableFuture.completedFuture(null);

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

    /**
     * Shows the empty editor at once and starts the first load in the background; the list fills in, and the empty
     * editor is reseeded from the loaded Default, when it completes. Call from the subclass constructor, after its own
     * fields are set.
     */
    protected final void initPanel() {
        showEmptyEditor();
        reload();
    }

    /**
     * Completes, on the event dispatch thread, once the most recently started load has been applied to the list. For
     * tests, which must wait for the background load before looking at the table.
     */
    CompletableFuture<Void> whenLoaded() {
        return lastLoad;
    }

    /**
     * Reads the store. Runs on a BACKGROUND thread, so it must not touch Swing components; a subclass that needs the
     * Default for seeding "New" keeps it from here in a volatile field.
     */
    protected abstract List<T> loadData() throws IOException;

    protected abstract void saveData(T old, String name) throws IOException;

    protected abstract void deleteData(T value) throws IOException;

    protected abstract void loadEditor(T value);

    protected void clearEditor() {
        templateName.setText("");
    }

    /**
     * Captures what is to be saved and returns the store write. Called on the event dispatch thread, where the editor
     * may be read; the returned write is run on the background thread. The default does the whole of {@link #saveData}
     * here, on the dispatch thread, for subclasses whose save reads the editor inline and have no separate capture
     * step.
     */
    protected StoreWrite prepareSave(T old, String name) throws IOException {
        saveData(old, name);
        return () -> {
        };
    }

    private void select(T value) {
        if (draft) {
            return;
        }
        selected = value;
        templateName.setText(name(value));
        loadEditor(value);
        setEditorState(true, false, canDelete(value));
    }

    /**
     * Allows specialised template stores to keep built-in entries pinned and undeletable.
     */
    protected boolean canDelete(T value) {
        return true;
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
        editorEditable = editable;
        editorCreating = creating;
        editorCanDelete = canDelete;
        templateName.setEnabled(editable);
        setEnabledRecursively(editorContent, editable);
        save.setVisible(editable);
        cancel.setVisible(creating);
        remove.setVisible(canDelete);
        applyButtonState();
        revalidate();
        repaint();
    }

    /**
     * The action buttons follow the editor state, and are all off while a store operation is in flight so a second Save
     * or Delete cannot overlap the first.
     */
    private void applyButtonState() {
        boolean idle = storeOperationsInFlight == 0;
        save.setEnabled(editorEditable && idle);
        cancel.setEnabled(editorCreating);
        remove.setEnabled(editorCanDelete && idle);
        fresh.setEnabled(!editorCreating && idle);
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
        if (name.isEmpty() || storeOperationsInFlight > 0) {
            return;
        }
        StoreWrite write;
        try {
            write = prepareSave(selected, name);
        }
        catch (IOException e) {
            reportFailure("Could not save template", e);
            return;
        }
        runStoreOperation(write::run, "Could not save template", this::reloadThenResetEditor);
    }

    private void remove() {
        if (selected == null || storeOperationsInFlight > 0
                || DialogPlacement.showConfirmDialog(this, "Delete template?", "Delete", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        T target = selected;
        runStoreOperation(() -> deleteData(target), "Could not delete template", this::reloadThenResetEditor);
    }

    /**
     * After a successful write: refresh the list, tell the owner, and only then leave the editor — so the user never
     * sees the editor reset against a list that still shows the old data.
     */
    private void reloadThenResetEditor() {
        reload().thenRun(() -> {
            try {
                if (onTemplateChanged != null) {
                    onTemplateChanged.run();
                }
                showEmptyEditor();
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Template change follow-up failed", e);
            }
        });
    }

    /**
     * Runs {@code task} on the store thread. On the event dispatch thread afterwards: on success {@code onSuccess}; on
     * failure the error is logged and shown, and nothing else changes, so the editor still holds what the user typed.
     */
    private void runStoreOperation(StoreTask task, String failureTitle, Runnable onSuccess) {
        storeOperationsInFlight++;
        applyButtonState();
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    task.run();
                }
                catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, STORE_IO).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                storeOperationsInFlight--;
                applyButtonState();
                if (error != null) {
                    reportFailure(failureTitle, error);
                }
                else {
                    onSuccess.run();
                }
            }));
        }
        catch (RejectedExecutionException e) {
            storeOperationsInFlight--;
            applyButtonState();
            reportFailure(failureTitle, e);
        }
    }

    /**
     * Starts a background load; its result replaces the list only if no later load has been started. The returned
     * future completes on the event dispatch thread after the list (and, if the editor is empty, its seed) is updated.
     */
    private CompletableFuture<Void> reload() {
        int epoch = ++loadEpoch;
        CompletableFuture<Void> done = new CompletableFuture<>();
        lastLoad = done;
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    return loadData();
                }
                catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, STORE_IO).whenComplete((rows, error) -> SwingUtilities.invokeLater(() -> {
                try {
                    if (epoch == loadEpoch) {
                        if (error != null) {
                            reportFailure("Could not load templates", error);
                        }
                        else {
                            model.setRows(rows);
                            if (!draft && selected == null) {
                                // The empty editor was seeded before the Default was known; seed it from the real one now.
                                showEmptyEditor();
                            }
                        }
                    }
                }
                finally {
                    done.complete(null);
                }
            }));
        }
        catch (RejectedExecutionException e) {
            reportFailure("Could not load templates", e);
            done.complete(null);
        }
        return done;
    }

    /**
     * Test seam: when set, failures go here instead of a modal dialog (which would block a headless test).
     */
    static volatile BiConsumer<String, Throwable> failureSink;

    private void reportFailure(String title, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        LOG.log(Level.WARNING, title, cause);
        BiConsumer<String, Throwable> sink = failureSink;
        if (sink != null) {
            sink.accept(title, cause);
            return;
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        DialogPlacement.showMessageDialog(this, title + ": " + message, "Templates", JOptionPane.ERROR_MESSAGE);
    }
}
