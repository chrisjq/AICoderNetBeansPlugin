package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SavedServerKeys;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.SavedServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.DialogPlacement;

/**
 * Working-copy editor for the plugin's reusable OpenAI-compatible server entries.
 */
public final class SavedServersEditor extends JPanel {

    private final SavedServerKeys keys;
    private final List<SavedServer> entries = new ArrayList<>();
    private final Set<String> loadedIds = Collections.synchronizedSet(new HashSet<>());
    /**
     * Removed servers whose key is not deleted yet. An id leaves this set only once its delete succeeds, so a failed
     * delete is retried by the next save.
     */
    private final Set<String> pendingRemovedKeyIds = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, char[]> pendingKeys = new HashMap<>();
    private final Map<String, Boolean> keyPresent = new HashMap<>();
    private long loadGeneration;
    private long editGeneration;
    private final Set<String> clearedKeys = new HashSet<>();
    private final List<Consumer<Optional<SavedServer>>> selectionListeners = new ArrayList<>();
    private final List<Runnable> changeListeners = new ArrayList<>();
    private boolean dirty;
    private final EntriesTableModel tableModel = new EntriesTableModel();
    private final JTable table = new JTable(tableModel);
    private String defaultId = "";

    public SavedServersEditor(SavedServerKeys keys) {
        super(new BorderLayout(0, 6));
        this.keys = keys;
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                fireSelection();
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add = new JButton("Add");
        add.addActionListener(e -> edit(null));
        JButton edit = new JButton("Edit");
        edit.addActionListener(e -> selected().ifPresent(this::edit));
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> selected().ifPresent(server -> remove(server.id())));
        JButton makeDefault = new JButton("Set default");
        makeDefault.addActionListener(e -> selected().ifPresent(server -> setDefault(server.id())));
        buttons.add(add);
        buttons.add(edit);
        buttons.add(remove);
        buttons.add(makeDefault);
        add(buttons, BorderLayout.SOUTH);
    }

    public void load() {
        editGeneration++;
        zeroPendingKeys();
        dirty = false;
        entries.clear();
        entries.addAll(OpenAiCompatiblePluginSettings.getSavedServers());
        loadedIds.clear();
        entries.forEach(server -> loadedIds.add(server.id()));
        keyPresent.clear();
        long generation = ++loadGeneration;
        entries.forEach(server -> keys.readAsync(server.id()).thenAccept(value -> {
            boolean present = value.isPresent();
            value.ifPresent(chars -> Arrays.fill(chars, '\0'));
            SwingUtilities.invokeLater(() -> {
                if (generation == loadGeneration
                        && !clearedKeys.contains(server.id())
                        && !pendingKeys.containsKey(server.id())) {
                    keyPresent.put(server.id(), present);
                    tableModel.fireTableDataChanged();
                }
            });
        }));
        defaultId = OpenAiCompatiblePluginSettings.getDefaultServerId();
        tableModel.fireTableDataChanged();
        if (!entries.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
        fireSelection();
    }

    public CompletableFuture<Boolean> save() {
        boolean wasDirty = dirty;
        List<SavedServer> saved = List.copyOf(entries);
        String savedDefault = defaultId;
        Set<String> loadedAtStart;
        synchronized (loadedIds) {
            loadedAtStart = Set.copyOf(loadedIds);
        }
        Map<String, char[]> originalKeys = new HashMap<>(pendingKeys);
        Map<String, char[]> keysToSave = new HashMap<>();
        pendingKeys.forEach((id, value) -> keysToSave.put(id, value.clone()));
        Set<String> clears = Set.copyOf(clearedKeys);
        if (!wasDirty && pendingRemovedKeyIds.isEmpty()) {
            keysToSave.values().forEach(value -> Arrays.fill(value, '\0'));
            return CompletableFuture.completedFuture(true);
        }
        Set<String> removalsToRun;
        synchronized (pendingRemovedKeyIds) {
            if (wasDirty) {
                Set<String> removedIds = new HashSet<>(loadedAtStart);
                saved.forEach(server -> removedIds.remove(server.id()));
                pendingRemovedKeyIds.addAll(removedIds);
                saved.forEach(server -> pendingRemovedKeyIds.remove(server.id()));
            }
            removalsToRun = Set.copyOf(pendingRemovedKeyIds);
        }
        long generation = editGeneration;
        List<CompletableFuture<Boolean>> operations = new ArrayList<>();
        for (Map.Entry<String, char[]> entry : keysToSave.entrySet()) {
            String id = entry.getKey();
            char[] value = entry.getValue();
            operations.add(keys.saveAsync(id, value).handle((ok, error) -> {
                boolean success = error == null && Boolean.TRUE.equals(ok);
                Arrays.fill(value, '\0');
                if (success) {
                    SwingUtilities.invokeLater(() -> {
                        if (editGeneration == generation) {
                            char[] original = originalKeys.get(id);
                            if (original != null && pendingKeys.remove(id, original)) {
                                Arrays.fill(original, '\0');
                                keyPresent.put(id, true);
                            }
                        }
                    });
                }
                return success;
            }));
        }
        for (String id : clears) {
            operations.add(keys.deleteAsync(id).handle((ignored, error) -> {
                boolean success = error == null;
                if (success) {
                    SwingUtilities.invokeLater(() -> {
                        if (editGeneration == generation) {
                            clearedKeys.remove(id);
                            keyPresent.put(id, false);
                        }
                    });
                }
                return success;
            }));
        }
        CompletableFuture<Boolean> staged = CompletableFuture.allOf(operations.toArray(new CompletableFuture<?>[0]))
                .thenApply(ignored -> operations.stream().allMatch(operation -> Boolean.TRUE.equals(operation.join())));
        return staged.thenCompose(stagedOk -> {
            if (!stagedOk) {
                return CompletableFuture.completedFuture(false);
            }
            if (wasDirty) {
                OpenAiCompatiblePluginSettings.setSavedServers(saved);
                OpenAiCompatiblePluginSettings.setDefaultServerId(savedDefault);
                synchronized (loadedIds) {
                    loadedIds.clear();
                    saved.forEach(server -> loadedIds.add(server.id()));
                }
                SwingUtilities.invokeLater(() -> {
                    if (editGeneration == generation) {
                        dirty = false;
                    }
                });
            }
            List<CompletableFuture<Boolean>> removedOperations = removalsToRun.stream()
                    .map(id -> keys.deleteAsync(id).handle((ignored, error) -> {
                        boolean success = error == null;
                        if (success) {
                            synchronized (pendingRemovedKeyIds) {
                                pendingRemovedKeyIds.remove(id);
                            }
                        }
                        return success;
                    }))
                    .toList();
            return CompletableFuture.allOf(removedOperations.toArray(new CompletableFuture<?>[0]))
                    .thenApply(ignored -> removedOperations.stream()
                            .allMatch(operation -> Boolean.TRUE.equals(operation.join())));
        });
    }

    public List<SavedServer> entries() {
        return List.copyOf(entries);
    }

    boolean isModified() {
        return dirty;
    }

    public Optional<SavedServer> selected() {
        int row = table.getSelectedRow();
        return row >= 0 && row < entries.size() ? Optional.of(entries.get(row)) : Optional.empty();
    }

    public void addSelectionListener(Consumer<Optional<SavedServer>> listener) {
        selectionListeners.add(listener);
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    public static Optional<List<SavedServer>> showManageDialog(Component parent, SavedServerKeys keys) {
        SavedServersEditor editor = new SavedServersEditor(keys);
        editor.load();
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Manage servers",
                                     Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(editor, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        final boolean[] accepted = {false};
        ok.addActionListener(e -> {
            ok.setEnabled(false);
            cancel.setEnabled(false);
            editor.save().orTimeout(10, TimeUnit.SECONDS)
                    .whenComplete((saved, error) -> SwingUtilities.invokeLater(() -> {
                        if (error == null && Boolean.TRUE.equals(saved)) {
                            accepted[0] = true;
                            dialog.dispose();
                        }
                        else {
                            DialogPlacement.showMessageDialog(dialog,
                                                              error == null ? "Could not store every server key." : "Could not store server settings.",
                                                              "Manage servers", JOptionPane.ERROR_MESSAGE);
                            ok.setEnabled(true);
                            cancel.setEnabled(true);
                        }
                    }));
        });
        cancel.addActionListener(e -> {
            editor.zeroPendingKeys();
            dialog.dispose();
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                editor.zeroPendingKeys();
            }
        });
        buttons.add(ok);
        buttons.add(cancel);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        DialogPlacement.centre(dialog, parent);
        dialog.setVisible(true);
        return accepted[0] ? Optional.of(editor.entries()) : Optional.empty();
    }

    SavedServer addForTest(String name, String url, ServerTypeEnum type, char[] key) {
        SavedServer server = SavedServer.create(name, url, type);
        add(server, key);
        return server;
    }

    void removeForTest(String id) {
        remove(id);
    }

    void setDefaultForTest(String id) {
        setDefault(id);
    }

    CompletableFuture<Optional<char[]>> keyForConnectionTest(SavedServer server) {
        char[] staged = pendingKeys.get(server.id());
        if (staged != null) {
            return CompletableFuture.completedFuture(Optional.of(staged.clone()));
        }
        return keys.readAsync(server.id());
    }

    private void edit(SavedServer server) {
        SavedServerForm.showDialog(this, server).ifPresent(result -> {
            if (server == null) {
                add(SavedServer.create(result.name(), result.url(), result.serverType()), result.key());
            }
            else {
                int index = indexOf(server.id());
                if (index >= 0) {
                    entries.set(index, server.withValues(result.name(), result.url(), result.serverType()));
                    if (result.clearKey()) {
                        stageClear(server.id());
                    }
                    else {
                        stageKey(server.id(), result.key());
                    }
                    changed();
                }
            }
        });
    }

    private void add(SavedServer server, char[] key) {
        entries.add(server);
        if (defaultId.isBlank()) {
            defaultId = server.id();
        }
        stageKey(server.id(), key);
        changed();
        table.setRowSelectionInterval(entries.size() - 1, entries.size() - 1);
    }

    private void remove(String id) {
        int index = indexOf(id);
        if (index < 0) {
            return;
        }
        entries.remove(index);
        char[] pending = pendingKeys.remove(id);
        if (pending != null) {
            Arrays.fill(pending, '\0');
        }
        clearedKeys.remove(id);
        if (id.equals(defaultId)) {
            defaultId = entries.isEmpty() ? "" : entries.get(0).id();
        }
        changed();
    }

    private void setDefault(String id) {
        if (indexOf(id) >= 0 && !id.equals(defaultId)) {
            defaultId = id;
            changed();
        }
    }

    private void stageKey(String id, char[] key) {
        if (key == null || key.length == 0) {
            return;
        }
        char[] old = pendingKeys.put(id, key.clone());
        if (old != null) {
            Arrays.fill(old, '\0');
        }
        Arrays.fill(key, '\0');
        clearedKeys.remove(id);
        keyPresent.put(id, true);
    }

    private void stageClear(String id) {
        char[] pending = pendingKeys.remove(id);
        if (pending != null) {
            Arrays.fill(pending, '\0');
        }
        clearedKeys.add(id);
        keyPresent.put(id, false);
    }

    private int indexOf(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void changed() {
        editGeneration++;
        dirty = true;
        tableModel.fireTableDataChanged();
        changeListeners.forEach(Runnable::run);
        fireSelection();
    }

    private void fireSelection() {
        Optional<SavedServer> selected = selected();
        selectionListeners.forEach(listener -> listener.accept(selected));
    }

    private void zeroPendingKeys() {
        pendingKeys.values().forEach(key -> Arrays.fill(key, '\0'));
        pendingKeys.clear();
        clearedKeys.clear();
    }

    private final class EntriesTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Name", "URL", "Server type", "Default", "Key"};

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            SavedServer server = entries.get(row);
            return switch (column) {
                case 0 ->
                    server.name();
                case 1 ->
                    server.url();
                case 2 ->
                    server.serverType().displayName();
                case 3 ->
                    server.id().equals(defaultId) ? "✓" : "";
                case 4 ->
                    pendingKeys.containsKey(server.id()) || Boolean.TRUE.equals(keyPresent.get(server.id())) ? "set" : "not set";
                default ->
                    "";
            };
        }
    }
}
